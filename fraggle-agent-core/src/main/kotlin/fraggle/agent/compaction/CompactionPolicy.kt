package fraggle.agent.compaction

import fraggle.agent.message.AgentMessage

/**
 * Decides whether a message list should be compacted right now.
 *
 * The policy is consulted by a [Compactor] at the start of every compaction
 * attempt and must be cheap — no I/O, no LLM calls. All the expensive work
 * (generating the summary) happens afterward only if the policy returns true.
 *
 * Compaction policies are deliberately single-method and combinator-friendly:
 * apps can compose [AnyOfCompactionPolicy] / [AllOfCompactionPolicy] to
 * express rules like "compact when either we're over 70% context OR we have
 * more than 100 messages."
 */
fun interface CompactionPolicy {
    fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean
}

/**
 * Compact when the context-window ratio meets or exceeds [triggerRatio].
 * [minMessages] is a floor that prevents compaction from firing on very short
 * conversations even if a model's usage count briefly spikes — there's
 * nothing meaningful to summarize in the first few messages.
 *
 * When [ContextUsage.isUnknown] this policy returns false (we don't know
 * how close to the limit we are, so don't guess).
 */
class RatioCompactionPolicy(
    private val triggerRatio: Double = 0.70,
    private val minMessages: Int = 0,
) : CompactionPolicy {
    init {
        require(triggerRatio in 0.0..1.0) { "triggerRatio must be in 0..1, was $triggerRatio" }
        require(minMessages >= 0) { "minMessages must be non-negative, was $minMessages" }
    }

    override fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean {
        if (usage.isUnknown) return false
        if (messages.size < minMessages) return false
        return usage.ratio >= triggerRatio
    }
}

/**
 * Compact when the raw message count exceeds [maxMessages]. The simplest
 * possible policy and the one the messenger assistant has used historically
 * (via `maxHistoryMessages`). Useful for apps that don't have reliable
 * token-count reporting from the provider.
 */
class MessageCountCompactionPolicy(
    private val maxMessages: Int,
) : CompactionPolicy {
    init {
        require(maxMessages > 0) { "maxMessages must be positive, was $maxMessages" }
    }

    override fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean =
        messages.size > maxMessages
}

/** Fires when any underlying policy fires. Short-circuits on the first match. */
class AnyOfCompactionPolicy(private val policies: List<CompactionPolicy>) : CompactionPolicy {
    override fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean =
        policies.any { it.shouldCompact(messages, usage) }
}

/** Fires only when every underlying policy fires. */
class AllOfCompactionPolicy(private val policies: List<CompactionPolicy>) : CompactionPolicy {
    override fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean =
        policies.isNotEmpty() && policies.all { it.shouldCompact(messages, usage) }
}

/** Never compacts. Useful as a default when an app hasn't opted in yet. */
object NeverCompactionPolicy : CompactionPolicy {
    override fun shouldCompact(messages: List<AgentMessage>, usage: ContextUsage): Boolean = false
}
