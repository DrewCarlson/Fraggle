package fraggle.agent.loop

/**
 * Structured error types for the agent loop.
 * These represent specific failure modes that callers can handle distinctly.
 */
sealed class AgentError(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    /** LLM communication or response error. */
    data class LlmError(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AgentError(message, cause)

    /** Error during tool execution. */
    data class ToolError(
        val toolName: String,
        override val message: String,
        override val cause: Throwable? = null,
    ) : AgentError(message, cause)

    /** Agent run was cancelled via abort(). */
    data class Aborted(
        override val message: String = "Agent run was cancelled",
    ) : AgentError(message)

    /** Operation timed out. */
    data class Timeout(
        override val message: String,
    ) : AgentError(message)

    /** Tool execution denied by supervision policy. */
    data class PermissionDenied(
        val toolName: String,
        val reason: String,
    ) : AgentError("Permission denied for tool '$toolName': $reason")

    /** Maximum iterations exceeded. */
    data class MaxIterationsReached(
        val iterations: Int,
    ) : AgentError("Maximum iterations ($iterations) reached")
}
