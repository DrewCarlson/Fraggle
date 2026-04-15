package fraggle.coding.context

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A snapshot of the working directory at agent-start time. Used by
 * `SystemPromptBuilder` to inject cwd + git context into the system prompt.
 *
 * All git fields are best-effort — `null` means "couldn't determine," not "no
 * git." This keeps the coding agent usable in non-git directories without
 * error handling at every call site.
 */
data class WorkspaceSnapshot(
    val cwd: Path,
    val gitBranch: String? = null,
    val gitHead: String? = null,
    val gitStatusShort: String? = null,
) {
    companion object {
        /**
         * Capture the current state of [cwd]. Runs `git` subprocesses with a
         * short timeout; any failure (git missing, not a repo, timeout) is
         * silently treated as null for that field.
         *
         * This is intentionally synchronous — agent startup runs once per
         * invocation and users will pay the few-millisecond git cost gladly
         * for the context it buys them.
         */
        fun capture(cwd: Path): WorkspaceSnapshot = WorkspaceSnapshot(
            cwd = cwd,
            gitBranch = runGit(cwd, "rev-parse", "--abbrev-ref", "HEAD"),
            gitHead = runGit(cwd, "rev-parse", "--short", "HEAD"),
            gitStatusShort = runGit(cwd, "status", "--short"),
        )

        private fun runGit(cwd: Path, vararg args: String): String? {
            return try {
                val pb = ProcessBuilder(listOf("git") + args.toList())
                    .directory(cwd.toFile())
                    .redirectErrorStream(false)
                val proc = pb.start()
                val completed = proc.waitFor(2, TimeUnit.SECONDS)
                if (!completed) {
                    proc.destroyForcibly()
                    return null
                }
                if (proc.exitValue() != 0) return null
                proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
