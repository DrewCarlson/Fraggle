package fraggle.agent.loop

import fraggle.agent.message.AgentMessage

/**
 * Options for creating an [Agent] instance.
 */
data class AgentOptions(
    /** Initial system prompt. */
    val systemPrompt: String,

    /** Model identifier. */
    val model: String,

    /** Bridge to LLM for making calls. */
    val llmBridge: LlmBridge,

    /** Tool executor (optional — without it, tool calls are ignored). */
    val toolExecutor: ToolCallExecutor? = null,

    /** Maximum tool-call iterations per run. */
    val maxIterations: Int = 10,

    /** Sequential vs parallel tool execution. */
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,

    /** Hook called after each tool execution. */
    val afterToolCall: (suspend (AfterToolCallContext) -> AfterToolCallResult?)? = null,

    /** Chat ID for supervision context. */
    val chatId: String = "",

    /** Pre-seeded conversation history. Prepended before the first [Agent.prompt]. */
    val initialMessages: List<AgentMessage> = emptyList(),
)
