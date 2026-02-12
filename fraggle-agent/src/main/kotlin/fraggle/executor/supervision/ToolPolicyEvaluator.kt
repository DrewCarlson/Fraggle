package fraggle.executor.supervision

import fraggle.models.ApprovalPolicy
import fraggle.models.ArgMatcher
import fraggle.models.CommandPattern
import fraggle.models.ToolPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Evaluates tool calls against policy rules and returns the effective policy.
 *
 * Rules are evaluated top-to-bottom, first match wins. A rule matches if the tool
 * name matches AND (no args OR all arg matchers match). The effective policy is
 * the most restrictive arg-level policy (deny > ask > allow), falling back to the
 * tool-level policy (default: ALLOW).
 *
 * How argument values are matched depends on the `@ToolArg` annotation on the
 * corresponding tool Args property (looked up via [argTypes]):
 * - `PATH`: normalize path, then glob-match against patterns
 * - `SHELL_COMMAND`: parse shell string, match each command against patterns
 * - No annotation: plain glob-match against patterns
 *
 * Returns `null` when no rule matches (caller should delegate to interactive handler).
 */
class ToolPolicyEvaluator(
    private val rules: List<ToolPolicy>,
    private val argTypes: ToolArgTypes = ToolArgTypes(emptyMap()),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Evaluate the tool call against rules and return the effective policy,
     * or null if no rule matches.
     */
    fun evaluate(toolName: String, argsJson: String): ApprovalPolicy? {
        for (rule in rules) {
            val result = matches(rule, toolName, argsJson)
            if (result != null) return result
        }
        return null
    }

    private fun matches(rule: ToolPolicy, toolName: String, argsJson: String): ApprovalPolicy? {
        if (rule.tool != toolName) return null
        if (rule.args.isEmpty()) return rule.policy

        val argsObject = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            return null
        }

        val toolArgKinds = argTypes.types[toolName] ?: emptyMap()

        val argPolicies = mutableListOf<ApprovalPolicy>()
        for (matcher in rule.args) {
            val kind = toolArgKinds[matcher.name]
            if (!matchArg(matcher, argsObject, kind)) return null
            matcher.policy?.let { argPolicies.add(it) }
        }

        return if (argPolicies.isEmpty()) {
            rule.policy
        } else {
            mostRestrictive(argPolicies, rule.policy)
        }
    }

    private fun matchArg(matcher: ArgMatcher, argsObject: JsonObject, kind: ToolArgKind?): Boolean {
        val element = argsObject[matcher.name] ?: return false
        val actualValue = (element as? JsonPrimitive)?.content ?: return false

        return when (kind) {
            ToolArgKind.PATH -> {
                if (matcher.value.isEmpty()) return false
                val normalized = Path.of(actualValue).normalize().toString()
                matcher.value.any { globMatches(it, normalized) }
            }
            ToolArgKind.SHELL_COMMAND -> when {
                matcher.commands.isNotEmpty() -> matchShellCommandStructured(actualValue, matcher.commands)
                matcher.value.isNotEmpty() -> matchShellCommand(actualValue, matcher.value)
                else -> false
            }
            null -> {
                if (matcher.value.isEmpty()) return false
                matcher.value.any { globMatches(it, actualValue) }
            }
        }
    }

    // Parse the shell command string and verify that every parsed command matches
    // at least one value pattern. Each pattern is "executable [argPattern...]" split
    // on whitespace. E.g., "ls" allows ls with any args; "cat /workspace/\*\*" allows
    // cat only with matching args.
    private fun matchShellCommand(commandString: String, patterns: List<String>): Boolean {
        val parsed = ShellCommandParser.parse(commandString)
        if (parsed.isEmpty()) return false
        return parsed.all { cmd ->
            patterns.any { pattern -> matchCommandPattern(cmd, pattern) }
        }
    }

    private fun matchCommandPattern(cmd: ParsedCommand, pattern: String): Boolean {
        val parts = pattern.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return false
        val patternExec = parts[0]
        val patternArgs = parts.drop(1)

        if (cmd.executable != patternExec) return false
        if (patternArgs.isEmpty()) return true

        // Skip flags (args starting with -) and only match positional args against patterns
        val positionalArgs = cmd.args.filter { !it.startsWith("-") }
        return positionalArgs.all { arg ->
            val normalized = Path.of(arg).normalize().toString()
            patternArgs.any { globMatches(it, normalized) }
        }
    }

    // -- Structured command matching (CommandPattern) --

    private fun matchShellCommandStructured(commandString: String, patterns: List<CommandPattern>): Boolean {
        val parsed = ShellCommandParser.parse(commandString)
        if (parsed.isEmpty()) return false
        return parsed.all { cmd ->
            patterns.any { pattern -> matchStructuredCommandPattern(cmd, pattern) }
        }
    }

    private fun matchStructuredCommandPattern(cmd: ParsedCommand, pattern: CommandPattern): Boolean {
        if (cmd.executable != pattern.command) return false

        val flags = cmd.args.filter { it.startsWith("-") }
        val positional = cmd.args.filter { !it.startsWith("-") }

        if (!matchFlags(flags, pattern)) return false
        if (!matchPositionalArgs(positional, pattern)) return false

        return true
    }

    private fun matchFlags(flags: List<String>, pattern: CommandPattern): Boolean {
        // Deny flags take precedence — if any actual flag matches a deny pattern, fail
        val denyFlags = pattern.denyFlags
        if (denyFlags.isNotEmpty()) {
            for (flag in flags) {
                if (denyFlags.any { globMatches(it, flag) }) return false
            }
        }
        // Allow flags — if non-null, every actual flag must match at least one allow pattern
        val allowFlags = pattern.allowFlags
        if (allowFlags != null) {
            for (flag in flags) {
                if (allowFlags.none { globMatches(it, flag) }) return false
            }
        }
        return true
    }

    private fun matchPositionalArgs(positional: List<String>, pattern: CommandPattern): Boolean {
        // If both paths and args are empty, allow any positional args (constraint is on executable/flags only)
        if (pattern.paths.isEmpty() && pattern.args.isEmpty()) return true

        for (arg in positional) {
            if (isPathLike(arg)) {
                if (pattern.paths.isEmpty()) return false
                val normalized = Path.of(arg).normalize().toString()
                if (pattern.paths.none { globMatches(it, normalized) }) return false
            } else {
                if (pattern.args.isEmpty()) return false
                if (pattern.args.none { globMatches(it, arg) }) return false
            }
        }
        return true
    }

    companion object {

        /**
         * Heuristic to classify a positional argument as path-like or value-like.
         * - Starts with `/`, `./`, `../`, or `~` → path-like
         * - Contains `/` but NOT `://` → path-like (catches `src/main/App.kt` but not URLs)
         * - Everything else → value-like
         */
        internal fun isPathLike(arg: String): Boolean {
            if (arg.startsWith("/") || arg.startsWith("./") || arg.startsWith("../") || arg.startsWith("~")) {
                return true
            }
            return "/" in arg && "://" !in arg
        }
        private val GLOB_CHARS = charArrayOf('*', '?', '[', '{')

        /**
         * Returns the most restrictive policy from the list of arg-level policies,
         * falling back to the tool-level policy. Order: DENY > ASK > ALLOW.
         */
        internal fun mostRestrictive(argPolicies: List<ApprovalPolicy>, toolPolicy: ApprovalPolicy): ApprovalPolicy {
            var result = toolPolicy
            for (p in argPolicies) {
                if (p.ordinal > result.ordinal) result = p
            }
            return result
        }

        internal fun globMatches(pattern: String, value: String): Boolean {
            if (GLOB_CHARS.none { it in pattern }) {
                return pattern == value
            }
            return try {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                matcher.matches(Path.of(value))
            } catch (_: Exception) {
                pattern == value
            }
        }
    }
}
