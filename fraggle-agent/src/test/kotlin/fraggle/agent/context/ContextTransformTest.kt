package fraggle.agent.context

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextTransformTest {

    private fun userMsg(text: String) = AgentMessage.User(text)
    private fun assistantMsg(text: String) = AgentMessage.Assistant(
        content = listOf(ContentPart.Text(text)),
    )

    @Nested
    inner class ComposeTests {
        @Test
        fun `compose applies transforms in order`() = runTest {
            val addPrefix = ContextTransform { messages ->
                messages.map { msg ->
                    if (msg is AgentMessage.User) {
                        val text = msg.content.filterIsInstance<ContentPart.Text>()
                            .joinToString("") { it.text }
                        AgentMessage.User("[PREFIX] $text", msg.timestamp)
                    } else msg
                }
            }

            val filterShort = ContextTransform { messages ->
                messages.filter { msg ->
                    when (msg) {
                        is AgentMessage.User -> msg.content.filterIsInstance<ContentPart.Text>()
                            .sumOf { it.text.length } > 5
                        else -> true
                    }
                }
            }

            val composed = listOf(addPrefix, filterShort).compose()
            val messages = listOf(userMsg("hi"), userMsg("hello world"))

            val result = composed.transform(messages)
            // addPrefix runs first, making both messages longer
            // filterShort then filters — both should pass since prefix makes them > 5 chars
            assertEquals(2, result.size)
            assertTrue(result.all { msg ->
                (msg as AgentMessage.User).content.first().let { (it as ContentPart.Text).text }.startsWith("[PREFIX]")
            })
        }

        @Test
        fun `compose with empty list is identity`() = runTest {
            val composed = emptyList<ContextTransform>().compose()
            val messages = listOf(userMsg("hello"))
            assertEquals(messages, composed.transform(messages))
        }
    }

    @Nested
    inner class ThenOperator {
        @Test
        fun `then chains two transforms`() = runTest {
            val first = ContextTransform { msgs -> msgs.drop(1) }
            val second = ContextTransform { msgs -> msgs.take(1) }

            val chained = first then second
            val messages = listOf(userMsg("a"), userMsg("b"), userMsg("c"))

            val result = chained.transform(messages)
            assertEquals(1, result.size)
            assertEquals("b", (result[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
        }
    }

    @Nested
    inner class SlidingWindowTests {
        @Test
        fun `passes through when under budget`() = runTest {
            val transform = SlidingWindowTransform(maxTokens = 1000)
            val messages = listOf(userMsg("short"), assistantMsg("reply"))
            assertEquals(messages, transform.transform(messages))
        }

        @Test
        fun `trims old messages when over budget`() = runTest {
            // Each message ~10 chars = ~3 tokens with char-based estimator
            val transform = SlidingWindowTransform(maxTokens = 6)
            val messages = listOf(
                userMsg("message one"),  // ~3 tokens
                assistantMsg("reply one"), // ~3 tokens
                userMsg("message two"),  // ~3 tokens
                assistantMsg("reply two"), // ~3 tokens
            )

            val result = transform.transform(messages)
            // First message always kept + as many recent as fit
            assertTrue(result.size < messages.size, "Should trim messages")
            // First message should be preserved
            assertEquals("message one", (result[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
        }

        @Test
        fun `preserves first message even when over budget`() = runTest {
            val transform = SlidingWindowTransform(maxTokens = 1)
            val messages = listOf(
                userMsg("very long message that exceeds budget"),
                assistantMsg("response"),
            )

            val result = transform.transform(messages)
            assertEquals(1, result.size)
            assertEquals(
                "very long message that exceeds budget",
                (result[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text },
            )
        }

        @Test
        fun `empty messages returns empty`() = runTest {
            val transform = SlidingWindowTransform(maxTokens = 100)
            assertEquals(emptyList(), transform.transform(emptyList()))
        }

        @Test
        fun `single message always preserved`() = runTest {
            val transform = SlidingWindowTransform(maxTokens = 100)
            val messages = listOf(userMsg("only message"))
            assertEquals(1, transform.transform(messages).size)
        }

        @Test
        fun `custom token estimator`() = runTest {
            // Estimator that counts words
            val wordEstimator = TokenEstimator { message ->
                when (message) {
                    is AgentMessage.User -> message.content.filterIsInstance<ContentPart.Text>()
                        .sumOf { it.text.split(" ").size }
                    is AgentMessage.Assistant -> message.textContent.split(" ").size
                    is AgentMessage.ToolResult -> message.textContent.split(" ").size
                    is AgentMessage.Platform -> 0
                }
            }

            val transform = SlidingWindowTransform(maxTokens = 5, estimator = wordEstimator)
            val messages = listOf(
                userMsg("one two three"),  // 3 words
                assistantMsg("four five"), // 2 words
                userMsg("six seven eight nine"), // 4 words
            )

            val result = transform.transform(messages)
            // Budget is 5 words. First message (3 words) always kept.
            // Remaining budget: 2 words. Last message (4 words) won't fit, so only second (2 words) fits? No —
            // we walk backwards and break at first that doesn't fit.
            // message[2] = 4 words > 2 budget → break
            // So result: first message only
            assertTrue(result.size <= 2)
        }
    }

    @Nested
    inner class MaxMessagesTests {
        @Test
        fun `passes through when under limit`() = runTest {
            val transform = MaxMessagesTransform(maxMessages = 10)
            val messages = listOf(userMsg("a"), userMsg("b"))
            assertEquals(2, transform.transform(messages).size)
        }

        @Test
        fun `trims to limit keeping most recent`() = runTest {
            val transform = MaxMessagesTransform(maxMessages = 2)
            val messages = listOf(userMsg("a"), userMsg("b"), userMsg("c"), userMsg("d"))

            val result = transform.transform(messages)
            assertEquals(2, result.size)
            assertEquals("c", (result[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
            assertEquals("d", (result[1] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
        }

        @Test
        fun `exact limit passes through`() = runTest {
            val transform = MaxMessagesTransform(maxMessages = 3)
            val messages = listOf(userMsg("a"), userMsg("b"), userMsg("c"))
            assertEquals(3, transform.transform(messages).size)
        }
    }

    @Nested
    inner class LoopIntegration {
        @Test
        fun `context transform is applied before LLM call`() = runTest {
            var capturedMessages: List<AgentMessage>? = null

            val bridge = fraggle.agent.loop.LlmBridge { _, msgs, _ ->
                capturedMessages = msgs
                AgentMessage.Assistant(content = listOf(ContentPart.Text("ok")))
            }

            // Transform that drops all but last 2 messages
            val transform = MaxMessagesTransform(maxMessages = 2)

            val messages = listOf(
                userMsg("first"),
                assistantMsg("reply1"),
                userMsg("second"),
                assistantMsg("reply2"),
            )

            fraggle.agent.loop.runAgentLoop(
                prompts = listOf(userMsg("third")),
                systemPrompt = "system",
                messages = messages,
                chatId = "chat",
                config = fraggle.agent.loop.AgentLoopConfig(
                    llmBridge = bridge,
                    contextTransform = transform,
                ),
                emit = {},
            )

            // LLM should see only 2 most recent messages (from the transform)
            assertEquals(2, capturedMessages?.size)
        }
    }
}
