package org.drewcarlson.fraggle.skills.shell

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.sandbox.SandboxResult
import kotlin.time.Duration.Companion.seconds

class ExecuteCommandTool(private val sandbox: Sandbox) : SimpleTool<ExecuteCommandTool.Args>(
    argsSerializer = Args.serializer(),
    name = "execute_command",
    description = """Execute a shell command and return the output.
The command runs in a sandboxed environment with restricted permissions.
Use this for running scripts, system commands, or other shell operations.""",
) {
    @Serializable
    data class Args(
        @LLMDescription("The shell command to execute")
        val command: String,
        @LLMDescription("Maximum time to wait for the command to complete. Defaults to 30 seconds.")
        val timeout_seconds: Int = 30,
    )

    override suspend fun execute(args: Args): String {
        val dangerousPatterns = listOf(
            "rm -rf /",
            "rm -rf /*",
            ":(){ :|:& };:",
            "> /dev/sda",
            "mkfs.",
            "dd if=/dev/zero",
        )

        for (pattern in dangerousPatterns) {
            if (args.command.contains(pattern)) {
                return "Error: Command contains potentially dangerous pattern: $pattern"
            }
        }

        return when (val result = sandbox.execute(args.command, timeout = args.timeout_seconds.seconds)) {
            is SandboxResult.Success -> {
                val exec = result.value
                buildString {
                    appendLine("Exit code: ${exec.exitCode}")

                    if (exec.timedOut) {
                        appendLine("Status: TIMED OUT after ${args.timeout_seconds} seconds")
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
                }
            }
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}
