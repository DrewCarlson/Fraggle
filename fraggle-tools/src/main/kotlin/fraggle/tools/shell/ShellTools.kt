package fraggle.tools.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import fraggle.agent.skill.SkillExecutionContext
import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.LLMDescription
import fraggle.executor.ToolExecutor
import fraggle.executor.supervision.ToolArg
import fraggle.executor.supervision.ToolArgKind
import kotlin.time.Duration.Companion.seconds

class ExecuteCommandTool(
    private val toolExecutor: ToolExecutor,
    private val skillContext: SkillExecutionContext? = null,
) : AgentToolDef<ExecuteCommandTool.Args>(
    name = "execute_command",
    description = $$"""Execute a shell command and return the output.
By default the command runs in the workspace directory.
When `skill` is provided, the command runs in that skill's directory instead; the
workspace path is available to the command as the `WORKSPACE_DIR` env var and the
skill's own directory as `SKILL_DIR`. Reference user-supplied files via
`$WORKSPACE_DIR/<path>` so they resolve regardless of CWD.
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
        @param:LLMDescription("Optional skill name. When provided, the skill's Python venv is activated and its configured environment variables are injected automatically.")
        val skill: String? = null,
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
                val skillEnv = args.skill?.let { skillContext?.resolveEnvironment(it) }
                val workspaceDir = toolExecutor.workDir()
                val workDir = skillEnv?.workDir?.toFile() ?: workspaceDir.toFile()
                val processBuilder = ProcessBuilder("sh", "-c", args.command)
                    .directory(workDir)
                    .redirectErrorStream(false)

                if (skillEnv != null) {
                    val env = processBuilder.environment()
                    env.putAll(skillEnv.envVars)
                    env["WORKSPACE_DIR"] = workspaceDir.toString()
                    env["SKILL_DIR"] = skillEnv.workDir.toString()
                    if (skillEnv.venvBinDir != null) {
                        env["PATH"] = "${skillEnv.venvBinDir}:${env["PATH"] ?: ""}"
                    }
                }

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
