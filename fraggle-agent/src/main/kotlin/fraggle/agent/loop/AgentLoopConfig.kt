package fraggle.agent.loop

import fraggle.agent.message.AgentMessage

/**
 * Configuration for a single agent loop run.
 */
data class AgentLoopConfig(
    /** Bridge to make LLM calls. */
    val llmBridge: LlmBridge,

    /** Maximum number of tool-call iterations before stopping. */
    val maxIterations: Int = 10,

    /** Tool execution mode. */
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,

    /** Tool executor that knows how to find and run tools. */
    val toolExecutor: ToolCallExecutor? = null,

    /** Optional hook called after each tool execution. */
    val afterToolCall: (suspend (AfterToolCallContext) -> AfterToolCallResult?)? = null,

    /** Supplier of steering messages (injected after current turn). */
    val getSteeringMessages: suspend () -> List<AgentMessage> = { emptyList() },

    /** Supplier of follow-up messages (processed after agent would stop). */
    val getFollowUpMessages: suspend () -> List<AgentMessage> = { emptyList() },
)

enum class ToolExecutionMode { SEQUENTIAL, PARALLEL }

/**
 * Context provided to the afterToolCall hook.
 */
data class AfterToolCallContext(
    val assistantMessage: AgentMessage.Assistant,
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val isError: Boolean,
)

/**
 * Result from afterToolCall hook — allows overriding the result.
 */
data class AfterToolCallResult(
    val content: String? = null,
    val isError: Boolean? = null,
)
