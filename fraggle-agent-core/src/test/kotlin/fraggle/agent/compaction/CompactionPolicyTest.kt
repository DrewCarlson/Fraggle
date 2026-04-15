package fraggle.agent.compaction

import fraggle.agent.message.AgentMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionPolicyTest {

    @Nested
    inner class Ratio {
        @Test
        fun `fires at or above the trigger ratio`() {
            val policy = RatioCompactionPolicy(triggerRatio = 0.70)
            assertFalse(policy.shouldCompact(msgs(5), usage(used = 690, max = 1000, count = 5)))
            assertTrue(policy.shouldCompact(msgs(5), usage(used = 700, max = 1000, count = 5)))
            assertTrue(policy.shouldCompact(msgs(5), usage(used = 950, max = 1000, count = 5)))
        }

        @Test
        fun `does not fire when context size is unknown`() {
            val policy = RatioCompactionPolicy(triggerRatio = 0.50)
            val usage = ContextUsage(usedTokens = 9999, maxTokens = 0, messageCount = 50)
            assertFalse(policy.shouldCompact(msgs(50), usage))
        }

        @Test
        fun `respects minMessages floor`() {
            val policy = RatioCompactionPolicy(triggerRatio = 0.50, minMessages = 10)
            // Ratio says compact but we only have 5 messages — don't fire.
            assertFalse(policy.shouldCompact(msgs(5), usage(used = 900, max = 1000, count = 5)))
            // Same ratio, 15 messages — fire.
            assertTrue(policy.shouldCompact(msgs(15), usage(used = 900, max = 1000, count = 15)))
        }

        @Test
        fun `invalid parameters throw at construction`() {
            assertThrows<IllegalArgumentException> { RatioCompactionPolicy(triggerRatio = -0.1) }
            assertThrows<IllegalArgumentException> { RatioCompactionPolicy(triggerRatio = 1.5) }
            assertThrows<IllegalArgumentException> { RatioCompactionPolicy(minMessages = -1) }
        }
    }

    @Nested
    inner class MessageCount {
        @Test
        fun `fires strictly above the threshold`() {
            val policy = MessageCountCompactionPolicy(maxMessages = 20)
            assertFalse(policy.shouldCompact(msgs(19), usage()))
            assertFalse(policy.shouldCompact(msgs(20), usage()), "equals threshold is not 'above'")
            assertTrue(policy.shouldCompact(msgs(21), usage()))
        }

        @Test
        fun `ignores ContextUsage entirely`() {
            val policy = MessageCountCompactionPolicy(maxMessages = 10)
            // Should fire regardless of what usage says
            assertTrue(policy.shouldCompact(msgs(20), ContextUsage(0, 0, 20)))
        }

        @Test
        fun `rejects zero or negative threshold`() {
            assertThrows<IllegalArgumentException> { MessageCountCompactionPolicy(0) }
            assertThrows<IllegalArgumentException> { MessageCountCompactionPolicy(-5) }
        }
    }

    @Nested
    inner class Combinators {
        private val alwaysYes = CompactionPolicy { _, _ -> true }
        private val alwaysNo = CompactionPolicy { _, _ -> false }

        @Test
        fun `AnyOf fires on any match`() {
            assertTrue(AnyOfCompactionPolicy(listOf(alwaysNo, alwaysYes)).shouldCompact(msgs(1), usage()))
            assertFalse(AnyOfCompactionPolicy(listOf(alwaysNo, alwaysNo)).shouldCompact(msgs(1), usage()))
            assertFalse(AnyOfCompactionPolicy(emptyList()).shouldCompact(msgs(1), usage()))
        }

        @Test
        fun `AllOf fires only when every policy matches`() {
            assertTrue(AllOfCompactionPolicy(listOf(alwaysYes, alwaysYes)).shouldCompact(msgs(1), usage()))
            assertFalse(AllOfCompactionPolicy(listOf(alwaysYes, alwaysNo)).shouldCompact(msgs(1), usage()))
            assertFalse(AllOfCompactionPolicy(emptyList()).shouldCompact(msgs(1), usage()), "empty AllOf is false")
        }

        @Test
        fun `Never never fires`() {
            assertFalse(NeverCompactionPolicy.shouldCompact(msgs(1000), usage(used = 999_999, max = 1000)))
        }
    }

    private fun msgs(count: Int): List<AgentMessage> =
        List(count) { AgentMessage.User("msg $it") }

    private fun usage(used: Int = 0, max: Int = 1000, count: Int = 0): ContextUsage =
        ContextUsage(usedTokens = used, maxTokens = max, messageCount = count)
}
