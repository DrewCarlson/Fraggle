package fraggle.agent.compaction

import fraggle.agent.message.AgentMessage

/**
 * A snapshot of how much of the LLM's context window is currently in use.
 *
 * [usedTokens] is the most recent total-tokens figure the provider reported;
 * [maxTokens] is the configured context window for the current model. Both
 * come from the calling application — the agent-core layer doesn't know
 * anything about specific models or providers.
 *
 * [ratio] is the primary signal a ratio-based compaction policy reads. When
 * [maxTokens] is zero (unknown context window), [ratio] is 0.0 and
 * [isUnknown] is true, which downstream policies typically treat as
 * "don't compact proactively."
 */
data class ContextUsage(
    val usedTokens: Int,
    val maxTokens: Int,
    val messageCount: Int,
) {
    /** Fraction of the context window in use; 0.0 when [maxTokens] is 0. */
    val ratio: Double get() = if (maxTokens > 0) usedTokens.toDouble() / maxTokens else 0.0

    /** True when we have no reliable context-window size to reason about. */
    val isUnknown: Boolean get() = maxTokens <= 0

    companion object {
        /**
         * Derive a usage snapshot from the message list alone. We look for
         * the most recent [AgentMessage.Assistant] with a non-null [usage]
         * (providers report totals cumulatively, so the latest assistant
         * turn's number is the "current" context size). Absent any reported
         * usage, [usedTokens] falls back to 0.
         *
         * This helper exists so callers that don't track token counts
         * independently can still get a reasonable ContextUsage without
         * building their own bookkeeping.
         */
        fun fromMessages(messages: List<AgentMessage>, maxTokens: Int): ContextUsage {
            val lastReported = messages
                .asReversed()
                .asSequence()
                .filterIsInstance<AgentMessage.Assistant>()
                .mapNotNull { it.usage }
                .firstOrNull()
            return ContextUsage(
                usedTokens = lastReported?.totalTokens ?: 0,
                maxTokens = maxTokens,
                messageCount = messages.size,
            )
        }
    }
}
