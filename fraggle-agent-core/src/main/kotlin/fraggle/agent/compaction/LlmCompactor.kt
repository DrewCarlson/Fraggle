package fraggle.agent.compaction

import fraggle.agent.loop.LlmBridge
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import org.slf4j.LoggerFactory

/**
 * Default LLM-backed [Compactor] implementation. Reusable across any Fraggle
 * application that has an [LlmBridge] in scope (messenger assistant, coding
 * agent, future variants).
 *
 * Algorithm:
 * 1. Consult [policy]. If it returns false, return [CompactionResult.NotNeeded].
 * 2. If the message list has fewer than [keepRecentMessages] + 1 entries there
 *    is nothing to compact (the tail would BE the entire list), so return
 *    NotNeeded regardless of what the policy said.
 * 3. Split: the last [keepRecentMessages] are kept verbatim, everything before
 *    them is serialized into a single user-message summarization prompt.
 * 4. Call [llmBridge.call] with [summarizerSystemPrompt] + the summarization
 *    request + no tools. A previous summary (if provided) is prepended so
 *    cumulative compactions build on each other without losing context.
 * 5. Extract the assistant's text content, trim, return [CompactionResult.Compacted].
 *    Empty or whitespace-only responses become [CompactionResult.Failed].
 * 6. Any exception from [llmBridge.call] becomes [CompactionResult.Failed]
 *    (compaction is opportunistic; an LLM outage should not break the turn).
 *
 * The prompts are constructor-configurable so each app can customize tone and
 * focus (a messenger assistant wants "preserve user preferences" while a
 * coding agent wants "preserve file edits and decisions"). Defaults work for
 * generic use.
 */
class LlmCompactor(
    private val llmBridge: LlmBridge,
    private val policy: CompactionPolicy,
    /**
     * Number of most-recent messages to keep verbatim. The rest of the head
     * is summarized. Must be at least 1. Typical values: 6-20.
     */
    private val keepRecentMessages: Int = 12,
    /** System prompt used for the summarization LLM call. */
    private val summarizerSystemPrompt: String = DEFAULT_SUMMARIZER_SYSTEM_PROMPT,
    /**
     * Instruction appended after the serialized history in the user message.
     * This is what tells the model what to extract.
     */
    private val summarizerInstruction: String = DEFAULT_SUMMARIZER_INSTRUCTION,
    /**
     * Model identifier passed in as context to [llmBridge] — mostly for
     * logging; bridges that multiplex across models can ignore this if the
     * compaction should use the same model as the main turn.
     */
    private val modelHint: String = "",
) : Compactor {
    init {
        require(keepRecentMessages >= 1) { "keepRecentMessages must be >= 1, was $keepRecentMessages" }
    }

    private val logger = LoggerFactory.getLogger(LlmCompactor::class.java)

    override suspend fun compact(
        messages: List<AgentMessage>,
        usage: ContextUsage,
        previousSummary: String?,
    ): CompactionResult {
        if (!policy.shouldCompact(messages, usage)) {
            return CompactionResult.NotNeeded
        }
        if (messages.size <= keepRecentMessages) {
            // Nothing to elide — the entire list would be kept verbatim.
            return CompactionResult.NotNeeded
        }

        val recent = messages.takeLast(keepRecentMessages)
        val older = messages.dropLast(keepRecentMessages)
        if (older.isEmpty()) return CompactionResult.NotNeeded

        logger.info(
            "Compacting conversation: {} total messages, keeping {} recent, summarizing {}",
            messages.size, recent.size, older.size,
        )

        val summaryRequest = buildSummaryRequest(older, previousSummary)

        val response = try {
            llmBridge.call(
                systemPrompt = summarizerSystemPrompt,
                messages = summaryRequest,
                tools = emptyList(),
            )
        } catch (e: Exception) {
            logger.warn("Compaction LLM call failed: ${e.message}")
            return CompactionResult.Failed("LLM call failed: ${e.message}")
        }

        val summary = response.textContent.trim()
        if (summary.isBlank()) {
            logger.warn("Compaction LLM returned empty summary")
            return CompactionResult.Failed("Summarizer returned an empty response")
        }

        logger.info("Compaction succeeded: elided {} messages into {} chars of summary", older.size, summary.length)
        return CompactionResult.Compacted(
            recentMessages = recent,
            compactedCount = older.size,
            summary = summary,
        )
    }

    /**
     * Build the user-message body sent to the summarizer. A single
     * [AgentMessage.User] is enough — the summarizer is a one-shot call, not
     * an interactive conversation.
     *
     * Format:
     * ```
     * (optional) Previous summary:
     * <existing summary text>
     *
     * Messages to compact:
     * [User] <text>
     * [Assistant] <text>
     * [Tool result: name] <text>
     * ...
     *
     * <summarizerInstruction>
     * ```
     */
    private fun buildSummaryRequest(
        older: List<AgentMessage>,
        previousSummary: String?,
    ): List<AgentMessage> {
        val body = buildString {
            if (!previousSummary.isNullOrBlank()) {
                appendLine("Previous conversation summary:")
                appendLine(previousSummary.trim())
                appendLine()
            }
            appendLine("Messages to compact:")
            for (msg in older) {
                appendLine(renderMessage(msg))
            }
            appendLine()
            append(summarizerInstruction.trimEnd())
            if (modelHint.isNotBlank()) {
                append("\n\n(model context: ")
                append(modelHint)
                append(')')
            }
        }
        return listOf(AgentMessage.User(text = body))
    }

    /**
     * Render a single message as a single prompt line. Tool calls and tool
     * results are flattened to text — the summarizer doesn't need structured
     * call metadata, just enough signal to produce a narrative summary.
     */
    private fun renderMessage(msg: AgentMessage): String = when (msg) {
        is AgentMessage.User -> "[User] ${extractText(msg.content)}"
        is AgentMessage.Assistant -> buildString {
            append("[Assistant] ")
            append(msg.textContent)
            if (msg.toolCalls.isNotEmpty()) {
                append(" (tool calls: ")
                append(msg.toolCalls.joinToString(", ") { it.name })
                append(')')
            }
        }
        is AgentMessage.ToolResult -> "[Tool result: ${msg.toolName}] ${msg.textContent}"
        is AgentMessage.Platform -> "[Platform: ${msg.platform}]"
    }

    private fun extractText(parts: List<ContentPart>): String =
        parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        /**
         * Default summarizer system prompt. Deliberately generic — apps can
         * override with a more domain-specific role.
         */
        const val DEFAULT_SUMMARIZER_SYSTEM_PROMPT: String =
            "You are a conversation summarizer. Produce a concise but comprehensive summary " +
                "of the conversation history provided. Preserve all important context, decisions, " +
                "and facts. Output only the summary, with no preamble, no markdown headers, and " +
                "no meta-commentary about your task."

        /**
         * Default summarizer instruction appended after the messages.
         * Generic enough to work for either a messenger assistant or a
         * coding agent; apps can override with tighter focus.
         */
        const val DEFAULT_SUMMARIZER_INSTRUCTION: String =
            "Create a TL;DR summary that captures:\n" +
                "- Key topics discussed and decisions made\n" +
                "- Important facts, preferences, or constraints established\n" +
                "- Status of ongoing tasks, open questions, or unresolved issues\n" +
                "- Any context someone would need to continue the conversation naturally\n" +
                "\n" +
                "Be compact. Prefer short sentences over prose."
    }
}
