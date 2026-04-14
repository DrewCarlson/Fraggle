package fraggle.agent.message

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class AgentMessageTest {

    @Nested
    inner class UserMessage {
        @Test
        fun `create with text convenience constructor`() {
            val msg = AgentMessage.User("hello")
            assertEquals(1, msg.content.size)
            assertIs<ContentPart.Text>(msg.content.first())
            assertEquals("hello", (msg.content.first() as ContentPart.Text).text)
        }

        @Test
        fun `create with content parts list`() {
            val parts = listOf(
                ContentPart.Text("look at this"),
                ContentPart.Image(byteArrayOf(1, 2, 3), "image/png"),
            )
            val msg = AgentMessage.User(parts)
            assertEquals(2, msg.content.size)
            assertIs<ContentPart.Text>(msg.content[0])
            assertIs<ContentPart.Image>(msg.content[1])
        }

        @Test
        fun `timestamp defaults to now`() {
            val before = Clock.System.now()
            val msg = AgentMessage.User("test")
            val after = Clock.System.now()
            assertTrue(msg.timestamp >= before)
            assertTrue(msg.timestamp <= after)
        }
    }

    @Nested
    inner class AssistantMessage {
        @Test
        fun `textContent joins text parts`() {
            val msg = AgentMessage.Assistant(
                content = listOf(
                    ContentPart.Text("Hello"),
                    ContentPart.Text(" world"),
                ),
            )
            assertEquals("Hello world", msg.textContent)
        }

        @Test
        fun `textContent ignores non-text parts`() {
            val msg = AgentMessage.Assistant(
                content = listOf(
                    ContentPart.Text("text"),
                    ContentPart.Image(byteArrayOf(), "image/png"),
                ),
            )
            assertEquals("text", msg.textContent)
        }

        @Test
        fun `textContent returns empty for no text parts`() {
            val msg = AgentMessage.Assistant(content = emptyList())
            assertEquals("", msg.textContent)
        }

        @Test
        fun `defaults to STOP reason and no tool calls`() {
            val msg = AgentMessage.Assistant()
            assertEquals(StopReason.STOP, msg.stopReason)
            assertTrue(msg.toolCalls.isEmpty())
            assertNull(msg.errorMessage)
            assertNull(msg.usage)
        }

        @Test
        fun `with tool calls`() {
            val calls = listOf(
                ToolCall(id = "tc-1", name = "get_weather", arguments = """{"city":"Paris"}"""),
            )
            val msg = AgentMessage.Assistant(toolCalls = calls)
            assertEquals(1, msg.toolCalls.size)
            assertEquals("get_weather", msg.toolCalls.first().name)
        }

        @Test
        fun `with usage statistics`() {
            val usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
            val msg = AgentMessage.Assistant(usage = usage)
            assertEquals(10, msg.usage?.promptTokens)
            assertEquals(15, msg.usage?.totalTokens)
        }
    }

    @Nested
    inner class ToolResultMessage {
        @Test
        fun `create with text convenience constructor`() {
            val msg = AgentMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "my_tool",
                text = "result here",
            )
            assertEquals("tc-1", msg.toolCallId)
            assertEquals("my_tool", msg.toolName)
            assertEquals("result here", msg.textContent)
            assertEquals(false, msg.isError)
        }

        @Test
        fun `create with error flag`() {
            val msg = AgentMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "fail_tool",
                text = "something went wrong",
                isError = true,
            )
            assertTrue(msg.isError)
            assertEquals("something went wrong", msg.textContent)
        }

        @Test
        fun `create with content parts`() {
            val msg = AgentMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "screenshot",
                content = listOf(
                    ContentPart.Text("screenshot taken"),
                    ContentPart.Image(byteArrayOf(0xFF.toByte()), "image/png"),
                ),
            )
            assertEquals(2, msg.content.size)
            assertEquals("screenshot taken", msg.textContent)
        }
    }

    @Nested
    inner class PlatformMessage {
        @Test
        fun `stores platform name and arbitrary data`() {
            val msg = AgentMessage.Platform(platform = "signal", data = mapOf("key" to "value"))
            assertEquals("signal", msg.platform)
        }
    }

    @Nested
    inner class ContentPartTests {
        @Test
        fun `image equality uses content comparison`() {
            val img1 = ContentPart.Image(byteArrayOf(1, 2, 3), "image/png", "test.png")
            val img2 = ContentPart.Image(byteArrayOf(1, 2, 3), "image/png", "test.png")
            val img3 = ContentPart.Image(byteArrayOf(4, 5, 6), "image/png", "test.png")

            assertEquals(img1, img2)
            assertNotEquals(img1, img3)
        }

        @Test
        fun `image hashCode uses content comparison`() {
            val img1 = ContentPart.Image(byteArrayOf(1, 2, 3), "image/png")
            val img2 = ContentPart.Image(byteArrayOf(1, 2, 3), "image/png")
            assertEquals(img1.hashCode(), img2.hashCode())
        }
    }

    @Nested
    inner class ToolCallTests {
        @Test
        fun `tool call holds id, name, and arguments`() {
            val tc = ToolCall(id = "call_123", name = "search", arguments = """{"query":"test"}""")
            assertEquals("call_123", tc.id)
            assertEquals("search", tc.name)
            assertEquals("""{"query":"test"}""", tc.arguments)
        }
    }

    @Nested
    inner class TokenUsageTests {
        @Test
        fun `defaults to zero`() {
            val usage = TokenUsage()
            assertEquals(0, usage.promptTokens)
            assertEquals(0, usage.completionTokens)
            assertEquals(0, usage.totalTokens)
        }
    }

    @Nested
    inner class SealedHierarchy {
        @Test
        fun `exhaustive when matching on AgentMessage`() {
            val messages: List<AgentMessage> = listOf(
                AgentMessage.User("hi"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("hello"))),
                AgentMessage.ToolResult(toolCallId = "1", toolName = "t", text = "r"),
                AgentMessage.Platform("test", Unit),
            )

            val types = messages.map { msg ->
                when (msg) {
                    is AgentMessage.User -> "user"
                    is AgentMessage.Assistant -> "assistant"
                    is AgentMessage.ToolResult -> "tool_result"
                    is AgentMessage.Platform -> "platform"
                }
            }

            assertEquals(listOf("user", "assistant", "tool_result", "platform"), types)
        }

        @Test
        fun `exhaustive when matching on StopReason`() {
            val reasons = StopReason.entries.map { reason ->
                when (reason) {
                    StopReason.STOP -> "stop"
                    StopReason.ERROR -> "error"
                    StopReason.ABORTED -> "aborted"
                }
            }
            assertEquals(3, reasons.size)
        }
    }
}
