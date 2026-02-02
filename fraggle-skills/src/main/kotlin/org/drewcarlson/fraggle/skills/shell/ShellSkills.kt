package org.drewcarlson.fraggle.skills.shell

import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.sandbox.SandboxResult
import org.drewcarlson.fraggle.skill.*
import kotlin.time.Duration.Companion.seconds

/**
 * Shell execution skills using the sandbox for safe execution.
 */
object ShellSkills {

    /**
     * Create all shell skills with the given sandbox.
     */
    fun create(sandbox: Sandbox): List<Skill> {
        return listOf(
            executeCommand(sandbox),
        )
    }

    /**
     * Skill to execute a shell command.
     */
    fun executeCommand(sandbox: Sandbox) = skill("execute_command") {
        description = """Execute a shell command and return the output.
            |The command runs in a sandboxed environment with restricted permissions.
            |Use this for running scripts, system commands, or other shell operations.""".trimMargin()

        parameter<String>("command") {
            description = "The shell command to execute"
            required = true
        }

        parameter<Int>("timeout_seconds") {
            description = "Maximum time to wait for the command to complete. Defaults to 30 seconds."
            default = 30
        }

        execute { params ->
            val command = params.get<String>("command")
            val timeoutSeconds = params.getOrDefault("timeout_seconds", 30)

            // Basic command validation
            val dangerousPatterns = listOf(
                "rm -rf /",
                "rm -rf /*",
                ":(){ :|:& };:",  // Fork bomb
                "> /dev/sda",
                "mkfs.",
                "dd if=/dev/zero",
            )

            for (pattern in dangerousPatterns) {
                if (command.contains(pattern)) {
                    return@execute SkillResult.Error("Command contains potentially dangerous pattern: $pattern")
                }
            }

            when (val result = sandbox.execute(command, timeout = timeoutSeconds.seconds)) {
                is SandboxResult.Success -> {
                    val exec = result.value
                    buildString {
                        appendLine("Exit code: ${exec.exitCode}")

                        if (exec.timedOut) {
                            appendLine("Status: TIMED OUT after $timeoutSeconds seconds")
                        }

                        if (exec.stdout.isNotBlank()) {
                            appendLine()
                            appendLine("=== STDOUT ===")
                            appendLine(exec.stdout.trim())
                        }

                        if (exec.stderr.isNotBlank()) {
                            appendLine()
                            appendLine("=== STDERR ===")
                            appendLine(exec.stderr.trim())
                        }

                        if (exec.stdout.isBlank() && exec.stderr.isBlank()) {
                            appendLine("(no output)")
                        }
                    }.let { SkillResult.Success(it) }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to run a Python script.
     */
    fun runPython(sandbox: Sandbox) = skill("run_python") {
        description = "Execute Python code and return the output."

        parameter<String>("code") {
            description = "Python code to execute"
            required = true
        }

        parameter<Int>("timeout_seconds") {
            description = "Maximum time to wait. Defaults to 30 seconds."
            default = 30
        }

        execute { params ->
            val code = params.get<String>("code")
            val timeoutSeconds = params.getOrDefault("timeout_seconds", 30)

            // Create a temporary script approach
            val escapedCode = code.replace("'", "'\"'\"'")
            val command = "python3 -c '$escapedCode'"

            when (val result = sandbox.execute(command, timeout = timeoutSeconds.seconds)) {
                is SandboxResult.Success -> {
                    val exec = result.value
                    if (exec.exitCode == 0) {
                        SkillResult.Success(exec.stdout.ifBlank { "(no output)" })
                    } else {
                        SkillResult.Error("Python error (exit ${exec.exitCode}):\n${exec.stderr}")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to run a Node.js script.
     */
    fun runNode(sandbox: Sandbox) = skill("run_node") {
        description = "Execute JavaScript code using Node.js and return the output."

        parameter<String>("code") {
            description = "JavaScript code to execute"
            required = true
        }

        parameter<Int>("timeout_seconds") {
            description = "Maximum time to wait. Defaults to 30 seconds."
            default = 30
        }

        execute { params ->
            val code = params.get<String>("code")
            val timeoutSeconds = params.getOrDefault("timeout_seconds", 30)

            val escapedCode = code.replace("'", "'\"'\"'")
            val command = "node -e '$escapedCode'"

            when (val result = sandbox.execute(command, timeout = timeoutSeconds.seconds)) {
                is SandboxResult.Success -> {
                    val exec = result.value
                    if (exec.exitCode == 0) {
                        SkillResult.Success(exec.stdout.ifBlank { "(no output)" })
                    } else {
                        SkillResult.Error("Node.js error (exit ${exec.exitCode}):\n${exec.stderr}")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }
}
