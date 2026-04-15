package fraggle.agent.state

import fraggle.agent.message.AgentMessage

/**
 * Mutable wrapper around [AgentState] for internal use.
 * Never exposed to callers — use [snapshot] to get an immutable view.
 */
class MutableAgentState(initial: AgentState) {
    var systemPrompt: String = initial.systemPrompt
    var model: String = initial.model

    private var _messages: List<AgentMessage> = initial.messages.toList()
    var messages: List<AgentMessage>
        get() = _messages
        set(value) { _messages = value.toList() }

    var isStreaming: Boolean = initial.isStreaming
    var streamingMessage: AgentMessage.Assistant? = initial.streamingMessage
    var pendingToolCalls: Set<String> = initial.pendingToolCalls.toSet()
    var errorMessage: String? = initial.errorMessage

    fun snapshot(): AgentState = AgentState(
        systemPrompt = systemPrompt,
        model = model,
        messages = _messages,
        isStreaming = isStreaming,
        streamingMessage = streamingMessage,
        pendingToolCalls = pendingToolCalls,
        errorMessage = errorMessage,
    )

    fun pushMessage(msg: AgentMessage) {
        _messages = _messages + msg
    }

    fun replaceLastMessage(msg: AgentMessage) {
        require(_messages.isNotEmpty()) { "Cannot replace last message: messages list is empty" }
        _messages = _messages.dropLast(1) + msg
    }

    fun reset() {
        _messages = emptyList()
        isStreaming = false
        streamingMessage = null
        pendingToolCalls = emptySet()
        errorMessage = null
    }
}
