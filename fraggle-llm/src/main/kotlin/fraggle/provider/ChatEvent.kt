package fraggle.provider

/**
 * Why an assistant message stopped generating.
 */
enum class StopReason {
    /** Natural end-of-turn. */
    STOP,

    /** Hit the max-token budget. */
    LENGTH,

    /** Stopped to emit tool calls for the caller to execute. */
    TOOL_USE,

    /** Stream failed with an upstream or transport error. */
    ERROR,

    /** Caller cancelled the request. */
    ABORTED,
}

/**
 * Event protocol for [LLMProvider.stream].
 *
 * A well-formed stream emits:
 *
 * 1. [Start] once
 * 2. Zero or more content-block lifecycles: (`*Start` → `*Delta`* → `*End`)
 *    for text, thinking, and tool-call blocks
 * 3. Exactly one terminal event: [Done] on success, or [Error] on failure/abort
 *
 * [contentIndex] identifies which block in the partial assistant [Message]
 * the event pertains to — the same index can be used to mutate the partial
 * in-place when reconstructing the full message.
 *
 * After [Start] has been emitted, providers must never throw: any downstream
 * failure — including HTTP errors, malformed chunks, and cancellation — must
 * be encoded as an [Error] event and end the flow normally.
 */
sealed interface ChatEvent {

    /** Stream has begun. [partial] holds the initial empty assistant message. */
    data class Start(val partial: Message) : ChatEvent

    data class TextStart(val contentIndex: Int, val partial: Message) : ChatEvent

    data class TextDelta(
        val contentIndex: Int,
        val delta: String,
        val partial: Message,
    ) : ChatEvent

    data class TextEnd(
        val contentIndex: Int,
        val content: String,
        val partial: Message,
    ) : ChatEvent

    data class ThinkingStart(val contentIndex: Int, val partial: Message) : ChatEvent

    data class ThinkingDelta(
        val contentIndex: Int,
        val delta: String,
        val partial: Message,
    ) : ChatEvent

    data class ThinkingEnd(
        val contentIndex: Int,
        val content: String,
        val partial: Message,
    ) : ChatEvent

    data class ToolCallStart(val contentIndex: Int, val partial: Message) : ChatEvent

    /**
     * A chunk of a tool call's argument JSON has arrived. [argumentsDelta]
     * may be an incremental fragment (OpenAI / Anthropic) or the complete
     * JSON string (Google, which does not stream tool-call arguments).
     */
    data class ToolCallDelta(
        val contentIndex: Int,
        val argumentsDelta: String,
        val partial: Message,
    ) : ChatEvent

    data class ToolCallEnd(
        val contentIndex: Int,
        val toolCall: ToolCall,
        val partial: Message,
    ) : ChatEvent

    /** Terminal success. [message] is the fully reconstructed assistant message. */
    data class Done(
        val reason: StopReason,
        val message: Message,
        val usage: Usage? = null,
    ) : ChatEvent

    /**
     * Terminal failure. [reason] is one of [StopReason.ERROR] or [StopReason.ABORTED].
     * [partial] contains whatever was reconstructed before the failure, and
     * [errorMessage] describes what went wrong.
     */
    data class Error(
        val reason: StopReason,
        val partial: Message,
        val errorMessage: String,
    ) : ChatEvent
}
