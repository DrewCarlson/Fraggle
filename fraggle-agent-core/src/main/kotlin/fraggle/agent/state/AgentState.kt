package fraggle.agent.state

import fraggle.agent.message.AgentMessage

/**
 * Immutable snapshot of agent state — the single source of truth for a running agent.
 */
data class AgentState(
    val systemPrompt: String,
    val model: String,
    val messages: List<AgentMessage>,
    val isStreaming: Boolean = false,
    val streamingMessage: AgentMessage.Assistant? = null,
    val pendingToolCalls: Set<String> = emptySet(),
    val errorMessage: String? = null,
)
