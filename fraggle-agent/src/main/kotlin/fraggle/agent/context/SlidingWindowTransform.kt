package fraggle.agent.context

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart

/**
 * Estimates token count for a message.
 * Default implementation uses a simple character-based heuristic (~4 chars per token).
 */
fun interface TokenEstimator {
    fun estimate(message: AgentMessage): Int

    companion object {
        /** Simple estimator: ~4 characters per token. */
        val CharBased = TokenEstimator { message ->
            val text = when (message) {
                is AgentMessage.User -> message.content.filterIsInstance<ContentPart.Text>()
                    .sumOf { it.text.length }
                is AgentMessage.Assistant -> message.textContent.length +
                    message.toolCalls.sumOf { it.name.length + it.arguments.length }
                is AgentMessage.ToolResult -> message.textContent.length
                is AgentMessage.Platform -> 0
            }
            (text / 4).coerceAtLeast(1)
        }
    }
}

/**
 * Token-budget-aware sliding window that keeps the most recent messages
 * within a token budget.
 *
 * Always preserves the first message (typically the user's initial prompt)
 * and keeps as many recent messages as fit within the budget.
 */
class SlidingWindowTransform(
    private val maxTokens: Int,
    private val estimator: TokenEstimator = TokenEstimator.CharBased,
) : ContextTransform {

    override suspend fun transform(messages: List<AgentMessage>): List<AgentMessage> {
        if (messages.isEmpty()) return messages

        val totalTokens = messages.sumOf { estimator.estimate(it) }
        if (totalTokens <= maxTokens) return messages

        // Always keep the first message
        val first = messages.first()
        val firstTokens = estimator.estimate(first)
        var remainingBudget = maxTokens - firstTokens

        if (remainingBudget <= 0) return listOf(first)

        // Walk backwards from the end, keeping messages that fit
        val kept = mutableListOf<AgentMessage>()
        for (i in messages.indices.reversed()) {
            if (i == 0) continue // first message handled separately
            val tokens = estimator.estimate(messages[i])
            if (remainingBudget >= tokens) {
                kept.add(0, messages[i])
                remainingBudget -= tokens
            } else {
                break // stop at the first message that doesn't fit
            }
        }

        return listOf(first) + kept
    }
}

/**
 * Simple message count limit — keeps only the most recent N messages.
 */
class MaxMessagesTransform(
    private val maxMessages: Int,
) : ContextTransform {
    override suspend fun transform(messages: List<AgentMessage>): List<AgentMessage> {
        if (messages.size <= maxMessages) return messages
        return messages.takeLast(maxMessages)
    }
}
