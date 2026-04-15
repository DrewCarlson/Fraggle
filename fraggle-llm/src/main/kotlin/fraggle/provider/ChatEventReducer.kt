package fraggle.provider

/**
 * State machine that translates a flat stream of per-chunk updates from an
 * OpenAI-style chat completion into the lifecycle [ChatEvent] protocol.
 *
 * Providers feed this reducer as they parse their wire format:
 * - [onTextDelta] for plain content chunks
 * - [onThinkingDelta] for reasoning/thinking chunks (closes any open text block first)
 * - [onToolCallDelta] for each per-index tool-call fragment
 * - assign [usage] and [finishReason] directly from terminal chunks
 * - call [finish] once the wire stream has ended to emit bookends and a
 *   terminal [ChatEvent.Done]
 *
 * The reducer keeps a growing [Message] snapshot that can be observed via
 * [partial] — useful for emitting `ChatEvent.Start` with an empty partial and
 * for populating the `partial` field on each lifecycle event.
 *
 * Not thread-safe. Each request should use its own instance.
 */
internal class ChatEventReducer {
    private val blocks = mutableListOf<ContentBlock>()
    var usage: Usage? = null
    var finishReason: String? = null

    /**
     * The currently open text block's index in [blocks], if any. Mutually
     * exclusive with [openThinkingIndex] — the reducer closes one before
     * opening the other.
     */
    private var openTextIndex: Int? = null
    private var openThinkingIndex: Int? = null

    /** Per-stream-index tool-call accumulator. */
    private val toolCalls = linkedMapOf<Int, ToolCallState>()

    private data class ToolCallState(
        var blockIndex: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    fun partial(): Message = Message(
        role = Role.ASSISTANT,
        blocks = blocks.toList(),
    )

    fun onTextDelta(delta: String): List<ChatEvent> {
        if (delta.isEmpty()) return emptyList()
        val out = mutableListOf<ChatEvent>()
        closeThinkingIfOpen(out)
        val idx = openTextIndex ?: run {
            val i = blocks.size
            blocks.add(ContentBlock.Text(""))
            openTextIndex = i
            out.add(ChatEvent.TextStart(contentIndex = i, partial = partial()))
            i
        }
        val current = blocks[idx] as ContentBlock.Text
        blocks[idx] = ContentBlock.Text(current.text + delta)
        out.add(ChatEvent.TextDelta(contentIndex = idx, delta = delta, partial = partial()))
        return out
    }

    fun onThinkingDelta(delta: String): List<ChatEvent> {
        if (delta.isEmpty()) return emptyList()
        val out = mutableListOf<ChatEvent>()
        closeTextIfOpen(out)
        val idx = openThinkingIndex ?: run {
            val i = blocks.size
            blocks.add(ContentBlock.Thinking(thinking = ""))
            openThinkingIndex = i
            out.add(ChatEvent.ThinkingStart(contentIndex = i, partial = partial()))
            i
        }
        val current = blocks[idx] as ContentBlock.Thinking
        blocks[idx] = current.copy(thinking = current.thinking + delta)
        out.add(ChatEvent.ThinkingDelta(contentIndex = idx, delta = delta, partial = partial()))
        return out
    }

    fun onToolCallDelta(
        index: Int,
        id: String?,
        name: String?,
        argumentsDelta: String?,
    ): List<ChatEvent> {
        val out = mutableListOf<ChatEvent>()
        closeTextIfOpen(out)
        closeThinkingIfOpen(out)

        val existing = toolCalls[index]
        val tcState = if (existing == null) {
            val blockIdx = blocks.size
            blocks.add(ContentBlock.ToolCallBlock(id = id ?: "", name = name ?: "", arguments = ""))
            val s = ToolCallState(blockIndex = blockIdx, id = id, name = name)
            toolCalls[index] = s
            out.add(ChatEvent.ToolCallStart(contentIndex = blockIdx, partial = partial()))
            s
        } else {
            if (id != null) existing.id = id
            if (name != null) existing.name = name
            existing
        }

        if (!argumentsDelta.isNullOrEmpty()) {
            tcState.arguments.append(argumentsDelta)
        }

        // Refresh the stored block with the latest accumulated state.
        blocks[tcState.blockIndex] = ContentBlock.ToolCallBlock(
            id = tcState.id ?: "",
            name = tcState.name ?: "",
            arguments = tcState.arguments.toString(),
        )

        if (!argumentsDelta.isNullOrEmpty() || id != null || name != null) {
            out.add(
                ChatEvent.ToolCallDelta(
                    contentIndex = tcState.blockIndex,
                    argumentsDelta = argumentsDelta ?: "",
                    partial = partial(),
                ),
            )
        }
        return out
    }

    /**
     * Close any open blocks and emit the terminal [ChatEvent.Done]. Callers
     * that hit an error before the natural end should emit [ChatEvent.Error]
     * themselves instead of calling this.
     */
    fun finish(): List<ChatEvent> {
        val out = mutableListOf<ChatEvent>()
        closeTextIfOpen(out)
        closeThinkingIfOpen(out)

        for ((_, state) in toolCalls) {
            val block = blocks[state.blockIndex] as ContentBlock.ToolCallBlock
            out.add(
                ChatEvent.ToolCallEnd(
                    contentIndex = state.blockIndex,
                    toolCall = ToolCall(
                        id = block.id,
                        function = FunctionCall(block.name, block.arguments),
                    ),
                    partial = partial(),
                ),
            )
        }

        val reason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.LENGTH
            "stop", null -> if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.STOP
            "content_filter" -> StopReason.ERROR
            else -> StopReason.STOP
        }
        out.add(ChatEvent.Done(reason = reason, message = partial(), usage = usage))
        return out
    }

    private fun closeTextIfOpen(out: MutableList<ChatEvent>) {
        val idx = openTextIndex ?: return
        val block = blocks[idx] as ContentBlock.Text
        out.add(ChatEvent.TextEnd(contentIndex = idx, content = block.text, partial = partial()))
        openTextIndex = null
    }

    private fun closeThinkingIfOpen(out: MutableList<ChatEvent>) {
        val idx = openThinkingIndex ?: return
        val block = blocks[idx] as ContentBlock.Thinking
        out.add(ChatEvent.ThinkingEnd(contentIndex = idx, content = block.thinking, partial = partial()))
        openThinkingIndex = null
    }
}
