package fraggle.tools.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.LLMDescription
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArg
import fraggle.executor.supervision.ToolArgKind
import kotlin.time.Duration.Companion.seconds

class ExecuteCommandTool(private val toolExecutor: ToolExecutor) : AgentToolDef<ExecuteCommandTool.Args>(
    name = "execute_command",
    description = """Execute a shell command and return the output.
The command runs in the workspace directory.
Use this for running scripts, system commands, or other shell operations.""",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(
        @ToolArg(ToolArgKind.SHELL_COMMAND)
        @param:LLMDescription("The shell command to execute")
        val command: String,
        @param:LLMDescription("Maximum time to wait for the command to complete. Defaults to 30 seconds.")
        val timeout_seconds: Int = 30,
    )

    private val dangerousPatterns = listOf(
        "rm -rf /",
        "rm -rf /*",
        ":(){ :|:& };:",
        "> /dev/sda",
        "mkfs.",
        "dd if=/dev/zero",
    )

    override suspend fun execute(args: Args): String {
        for (pattern in dangerousPatterns) {
            if (args.command.contains(pattern)) {
                return "Error: Command contains potentially dangerous pattern: $pattern"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val maxOutputSize = 100_000
                val processBuilder = ProcessBuilder("sh", "-c", args.command)
                    .directory(toolExecutor.workDir().toFile())
                    .redirectErrorStream(false)

                val process = processBuilder.start()

                val result = withTimeoutOrNull(args.timeout_seconds.seconds) {
                    // Read stdout and stderr concurrently to avoid deadlock.
                    // Sequential reads can deadlock when the process fills one pipe buffer
                    // while we're blocked reading the other.
                    val stdoutDeferred = async { process.inputStream.bufferedReader().readText() }
                    val stderrDeferred = async { process.errorStream.bufferedReader().readText() }
                    val exitCode = process.waitFor()
                    val stdout = stdoutDeferred.await()
                    val stderr = stderrDeferred.await()

                    Triple(exitCode, stdout.take(maxOutputSize), stderr.take(maxOutputSize))
                }

                if (result == null) {
                    process.destroyForcibly()
                    buildString {
                        appendLine("Exit code: -1")
                        appendLine("Status: TIMED OUT after ${args.timeout_seconds} seconds")
                    }
                } else {
                    val (exitCode, stdout, stderr) = result
                    buildString {
                        appendLine("Exit code: $exitCode")

                        if (stdout.isNotBlank()) {
                            appendLine()
                            appendLine("=== STDOUT ===")
                            appendLine(stdout.trim())
                        }

                        if (stderr.isNotBlank()) {
                            appendLine()
                            appendLine("=== STDERR ===")
                            appendLine(stderr.trim())
                        }

                        if (stdout.isBlank() && stderr.isBlank()) {
                            appendLine("(no output)")
                        }
                    }
                }
            } catch (e: Exception) {
                "Error: Failed to execute command: ${e.message}"
            }
        }
    }
}
