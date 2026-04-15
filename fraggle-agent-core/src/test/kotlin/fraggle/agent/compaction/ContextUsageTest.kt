package fraggle.agent.compaction

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.TokenUsage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextUsageTest {

    @Test
    fun `ratio is zero when max is zero`() {
        val usage = ContextUsage(usedTokens = 100, maxTokens = 0, messageCount = 5)
        assertEquals(0.0, usage.ratio)
        assertTrue(usage.isUnknown)
    }

    @Test
    fun `ratio is computed correctly`() {
        val usage = ContextUsage(usedTokens = 2000, maxTokens = 8000, messageCount = 10)
        assertEquals(0.25, usage.ratio)
        assertFalse(usage.isUnknown)
    }

    @Test
    fun `fromMessages uses the latest assistant usage`() {
        val messages = listOf<AgentMessage>(
            AgentMessage.User("hello"),
            AgentMessage.Assistant(
                content = listOf(fraggle.agent.message.ContentPart.Text("hi")),
                usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            ),
            AgentMessage.User("follow up"),
            AgentMessage.Assistant(
                content = listOf(fraggle.agent.message.ContentPart.Text("sure")),
                usage = TokenUsage(promptTokens = 20, completionTokens = 10, totalTokens = 30),
            ),
        )
        val usage = ContextUsage.fromMessages(messages, maxTokens = 100)
        assertEquals(30, usage.usedTokens, "should take the most recent total, not an average or sum")
        assertEquals(4, usage.messageCount)
        assertEquals(0.3, usage.ratio)
    }

    @Test
    fun `fromMessages falls back to zero when no assistant has usage`() {
        val messages = listOf<AgentMessage>(
            AgentMessage.User("hello"),
            AgentMessage.Assistant(content = listOf(fraggle.agent.message.ContentPart.Text("hi"))),
        )
        val usage = ContextUsage.fromMessages(messages, maxTokens = 8000)
        assertEquals(0, usage.usedTokens)
        assertEquals(2, usage.messageCount)
        assertFalse(usage.isUnknown, "maxTokens known → not unknown, even if used is 0")
    }

    @Test
    fun `fromMessages ignores usage on earlier assistants if a later one has none`() {
        // Confirms "latest wins even if latest is null" — we want the most
        // recent snapshot, not the last non-null one.
        val messages = listOf<AgentMessage>(
            AgentMessage.Assistant(
                content = listOf(fraggle.agent.message.ContentPart.Text("early")),
                usage = TokenUsage(totalTokens = 500),
            ),
            AgentMessage.Assistant(
                content = listOf(fraggle.agent.message.ContentPart.Text("late")),
                usage = null,
            ),
        )
        val usage = ContextUsage.fromMessages(messages, maxTokens = 1000)
        // Walks from the end looking for the first non-null — this is actually
        // what we want, because providers that stopped reporting usage mid-session
        // shouldn't invalidate the last good snapshot.
        assertEquals(500, usage.usedTokens)
    }
}
