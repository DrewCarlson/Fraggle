package fraggle.agent.event

import fraggle.agent.message.AgentMessage

/**
 * Structural events emitted at every lifecycle boundary of the agent loop.
 * Sealed class enables exhaustive `when` matching.
 */
sealed class AgentEvent {
    // Agent lifecycle
    data class AgentStart(val systemPrompt: String? = null) : AgentEvent()
    data class AgentEnd(val messages: List<AgentMessage>) : AgentEvent()

    // Turn lifecycle (one LLM call + its tool executions)
    data object TurnStart : AgentEvent()
    data class TurnEnd(
        val message: AgentMessage,
        val toolResults: List<AgentMessage.ToolResult>,
    ) : AgentEvent()

    // Message lifecycle — streaming (assistant only)
    data class MessageStart(val message: AgentMessage) : AgentEvent()
    data class MessageUpdate(
        val message: AgentMessage.Assistant,
        val delta: StreamDelta,
    ) : AgentEvent()
    data class MessageEnd(val message: AgentMessage) : AgentEvent()

    // Message lifecycle — instant (user, tool_result, platform)
    data class MessageRecord(val message: AgentMessage) : AgentEvent()

    // Tool execution lifecycle
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: String,
    ) : AgentEvent()
    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
    ) : AgentEvent()
}

sealed class StreamDelta {
    data class TextDelta(val text: String) : StreamDelta()
    data class ThinkingDelta(val text: String) : StreamDelta()
    data class ToolCallDelta(val toolCallId: String, val argumentsDelta: String) : StreamDelta()
}
