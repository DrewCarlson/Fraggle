package fraggle.agent.compaction

import fraggle.agent.message.AgentMessage

/**
 * Compacts an agent's message history when the conversation is getting long.
 *
 * The compactor's job is to:
 *  1. Consult a [CompactionPolicy] to decide whether compaction is needed.
 *  2. If so, pick a split point ("keep the last N verbatim, summarize the rest").
 *  3. Produce a [CompactionResult.Compacted] with the kept-verbatim tail plus
 *     a text summary of the elided head.
 *
 * What the caller does with the result is up to the caller:
 *  - The messenger assistant threads the summary into its system prompt and
 *    shortens its stored conversation history.
 *  - A coding-agent session might splice a synthetic user message containing
 *    the summary into the message list and write a session `Meta` entry.
 *  - A test fixture might just inspect the result.
 *
 * Compaction is expected to be called between turns, not mid-turn. The loop
 * doesn't interrupt itself to compact — instead the orchestrator decides at
 * the boundary whether to mutate the state before the next `agent.prompt()`.
 */
interface Compactor {
    /**
     * Decide whether to compact [messages] and, if so, return a compacted
     * version. [usage] is the current context-window snapshot (callers that
     * don't track usage can pass `ContextUsage.fromMessages(messages, 0)`
     * and rely on a message-count-based policy).
     *
     * [previousSummary] is the summary produced by a prior compaction round,
     * if any — the compactor prepends it to the summarization input so
     * cumulative compactions don't forget earlier context.
     *
     * Must not throw; failures surface as [CompactionResult.Failed]. This
     * keeps the call site simple at the orchestrator boundary where a
     * compaction failure should never crash the turn.
     */
    suspend fun compact(
        messages: List<AgentMessage>,
        usage: ContextUsage,
        previousSummary: String? = null,
    ): CompactionResult
}

/**
 * Result of a [Compactor.compact] call. Sealed so callers must handle all
 * three cases explicitly.
 */
sealed class CompactionResult {
    /**
     * The policy decided no compaction is warranted right now. The caller
     * should continue with the original message list unchanged.
     */
    data object NotNeeded : CompactionResult()

    /**
     * Compaction completed successfully.
     *
     * [recentMessages] is the tail of the original list that was kept
     * verbatim — the caller splices this back in wherever it's holding the
     * live conversation.
     *
     * [compactedCount] is how many messages from the head were elided and
     * summarized. Useful for logging and for cost/savings reporting in the UI.
     *
     * [summary] is the text of the LLM-generated summary. Callers decide
     * whether to put it into the system prompt, inline as a synthetic
     * message, into a session Meta record, etc.
     */
    data class Compacted(
        val recentMessages: List<AgentMessage>,
        val compactedCount: Int,
        val summary: String,
    ) : CompactionResult()

    /**
     * Compaction was attempted but failed. Callers should typically log the
     * [reason] and fall back to the original (uncompacted) message list
     * rather than aborting the turn — a failed compaction is a warning, not
     * an error.
     */
    data class Failed(val reason: String) : CompactionResult()
}
