package fraggle.coding.tools

import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.LLMDescription
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArg
import fraggle.executor.supervision.ToolArgKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Edit a file by replacing an exact string. Modeled on Claude Code's `Edit`
 * tool — the LLM supplies `old_string` plus enough surrounding context to
 * make it unique, and the tool either replaces it or reports what went wrong.
 *
 * Behaviour:
 * - `old_string` must match exactly once in the file (byte-for-byte). If it
 *   matches zero times the tool returns a "no match" error so the model can
 *   re-read the file and adjust. If it matches more than once the tool
 *   returns an "ambiguous match" error asking for more context, unless
 *   `replace_all` is true — in which case every occurrence is replaced.
 * - `new_string` may be empty (deletes the matched text).
 * - `old_string == new_string` is a no-op; returns success without writing.
 * - Newlines are treated literally; tabs vs. spaces matter. The LLM is
 *   expected to provide a byte-accurate `old_string`.
 *
 * The tool operates through a [ToolExecutor] so paths resolve relative to
 * the coding agent's workspace (same convention as every other file tool).
 */
class EditFileTool(private val toolExecutor: ToolExecutor) : AgentToolDef<EditFileTool.Args>(
    name = "edit_file",
    description = """Edit a file by replacing an exact string with a new string.

The `old_string` must appear exactly once in the file unless `replace_all` is true. Include enough surrounding context in `old_string` to make it unique — whitespace and line breaks matter.

Use this tool for targeted changes (fixing a bug, renaming an identifier, adjusting a value). Use `write_file` only when rewriting a whole file is genuinely necessary.""",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(
        @ToolArg(ToolArgKind.PATH)
        @param:LLMDescription("Path to the file to edit (relative to workspace or absolute)")
        val path: String,

        @SerialName("old_string")
        @param:LLMDescription(
            "The exact text to replace. Must match byte-for-byte including whitespace and newlines. " +
                "Include enough surrounding context that the match is unique within the file.",
        )
        val oldString: String,

        @SerialName("new_string")
        @param:LLMDescription("The text to substitute in place of old_string. May be empty to delete.")
        val newString: String,

        @SerialName("replace_all")
        @param:LLMDescription(
            "If true, replace every occurrence of old_string instead of failing on multiple matches. " +
                "Defaults to false.",
        )
        val replaceAll: Boolean = false,
    )

    override suspend fun execute(args: Args): String = withContext(Dispatchers.IO) {
        try {
            val resolved = toolExecutor.resolvePath(args.path)
            if (!resolved.exists()) return@withContext "Error: File not found: ${args.path}"
            if (!resolved.isRegularFile()) return@withContext "Error: Not a regular file: ${args.path}"

            if (args.oldString == args.newString) {
                return@withContext "No change: old_string and new_string are identical."
            }

            val original = resolved.readText()
            val occurrences = countOccurrences(original, args.oldString)

            when {
                occurrences == 0 -> buildString {
                    appendLine("Error: old_string not found in ${args.path}.")
                    appendLine("The file exists but does not contain the exact text you provided.")
                    appendLine("Re-read the file and adjust old_string to match byte-for-byte (whitespace and newlines included).")
                }

                occurrences > 1 && !args.replaceAll -> buildString {
                    appendLine("Error: old_string matches $occurrences locations in ${args.path}.")
                    appendLine("Include more surrounding context to make the match unique, or set replace_all=true to replace all occurrences.")
                }

                else -> {
                    val updated = if (args.replaceAll) {
                        original.replace(args.oldString, args.newString)
                    } else {
                        // Exactly one occurrence — use indexOf to avoid Regex overhead.
                        val idx = original.indexOf(args.oldString)
                        original.substring(0, idx) + args.newString + original.substring(idx + args.oldString.length)
                    }
                    resolved.writeText(updated)
                    val replaced = if (args.replaceAll) occurrences else 1
                    "Edited ${args.path}: replaced $replaced occurrence${if (replaced == 1) "" else "s"}."
                }
            }
        } catch (e: Exception) {
            "Error: Failed to edit file: ${e.message}"
        }
    }

    /**
     * Count non-overlapping occurrences of [needle] in [haystack]. [indexOf]
     * with advancing offset is fast enough and avoids allocating a Regex for
     * arbitrary patterns (since `old_string` may contain regex metacharacters).
     */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) return count
            count++
            from = idx + needle.length
        }
    }
}
