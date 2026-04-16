package fraggle.agent.event

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentEventTest {

    @Nested
    inner class AgentLifecycle {
        @Test
        fun `AgentStart with same prompt are equal`() {
            val a = AgentEvent.AgentStart("prompt")
            val b = AgentEvent.AgentStart("prompt")
            assertEquals(a, b)
        }

        @Test
        fun `AgentStart carries system prompt`() {
            val event = AgentEvent.AgentStart("You are helpful.")
            assertEquals("You are helpful.", event.systemPrompt)
        }

        @Test
        fun `AgentStart defaults to null system prompt`() {
            val event = AgentEvent.AgentStart()
            assertEquals(null, event.systemPrompt)
        }

        @Test
        fun `AgentEnd carries messages`() {
            val messages = listOf(
                AgentMessage.User("hi"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("hello"))),
            )
            val event = AgentEvent.AgentEnd(messages)
            assertEquals(2, event.messages.size)
        }
    }

    @Nested
    inner class TurnLifecycle {
        @Test
        fun `TurnStart is a singleton`() {
            assertEquals(AgentEvent.TurnStart, AgentEvent.TurnStart)
        }

        @Test
        fun `TurnEnd carries message and tool results`() {
            val assistant = AgentMessage.Assistant(
                content = listOf(ContentPart.Text("result")),
                stopReason = StopReason.STOP,
            )
            val toolResults = listOf(
                AgentMessage.ToolResult(toolCallId = "tc-1", toolName = "search", text = "found it"),
            )
            val event = AgentEvent.TurnEnd(assistant, toolResults)
            assertIs<AgentMessage.Assistant>(event.message)
            assertEquals(1, event.toolResults.size)
        }
    }

    @Nested
    inner class MessageLifecycle {
        @Test
        fun `MessageStart with user message`() {
            val msg = AgentMessage.User("test")
            val event = AgentEvent.MessageStart(msg)
            assertIs<AgentMessage.User>(event.message)
        }

        @Test
        fun `MessageUpdate carries delta`() {
            val msg = AgentMessage.Assistant(content = listOf(ContentPart.Text("partial")))
            val delta = StreamDelta.TextDelta("more text")
            val event = AgentEvent.MessageUpdate(msg, delta)
            assertEquals("partial", event.message.textContent)
            assertIs<StreamDelta.TextDelta>(event.delta)
        }

        @Test
        fun `MessageEnd with assistant message`() {
            val msg = AgentMessage.Assistant(content = listOf(ContentPart.Text("done")))
            val event = AgentEvent.MessageEnd(msg)
            assertIs<AgentMessage.Assistant>(event.message)
        }

        @Test
        fun `MessageRecord with user message`() {
            val msg = AgentMessage.User("hello")
            val event = AgentEvent.MessageRecord(msg)
            assertIs<AgentMessage.User>(event.message)
        }

        @Test
        fun `MessageRecord with tool result`() {
            val msg = AgentMessage.ToolResult(toolCallId = "tc-1", toolName = "search", text = "found")
            val event = AgentEvent.MessageRecord(msg)
            assertIs<AgentMessage.ToolResult>(event.message)
        }
    }

    @Nested
    inner class ToolExecutionLifecycle {
        @Test
        fun `ToolExecutionStart has tool info`() {
            val event = AgentEvent.ToolExecutionStart(
                toolCallId = "tc-1",
                toolName = "get_weather",
                args = """{"city":"Tokyo"}""",
            )
            assertEquals("tc-1", event.toolCallId)
            assertEquals("get_weather", event.toolName)
            assertTrue(event.args.contains("Tokyo"))
        }

        @Test
        fun `ToolExecutionEnd with success`() {
            val event = AgentEvent.ToolExecutionEnd(
                toolCallId = "tc-1",
                toolName = "search",
                result = "found 5 results",
                isError = false,
            )
            assertEquals(false, event.isError)
        }

        @Test
        fun `ToolExecutionEnd with error`() {
            val event = AgentEvent.ToolExecutionEnd(
                toolCallId = "tc-1",
                toolName = "fail_tool",
                result = "connection refused",
                isError = true,
            )
            assertTrue(event.isError)
        }
    }

    @Nested
    inner class StreamDeltaTests {
        @Test
        fun `TextDelta carries text`() {
            val delta = StreamDelta.TextDelta("hello")
            assertEquals("hello", delta.text)
        }

        @Test
        fun `ThinkingDelta carries text`() {
            val delta = StreamDelta.ThinkingDelta("let me think...")
            assertEquals("let me think...", delta.text)
        }

        @Test
        fun `ToolCallDelta carries tool call info`() {
            val delta = StreamDelta.ToolCallDelta("tc-1", """{"par""")
            assertEquals("tc-1", delta.toolCallId)
            assertEquals("""{"par""", delta.argumentsDelta)
        }

        @Test
        fun `exhaustive when matching on StreamDelta`() {
            val deltas: List<StreamDelta> = listOf(
                StreamDelta.TextDelta("a"),
                StreamDelta.ThinkingDelta("b"),
                StreamDelta.ToolCallDelta("c", "d"),
            )
            val types = deltas.map { delta ->
                when (delta) {
                    is StreamDelta.TextDelta -> "text"
                    is StreamDelta.ThinkingDelta -> "thinking"
                    is StreamDelta.ToolCallDelta -> "tool_call"
                }
            }
            assertEquals(listOf("text", "thinking", "tool_call"), types)
        }
    }

    @Nested
    inner class SealedHierarchy {
        @Test
        fun `exhaustive when matching on AgentEvent`() {
            val events: List<AgentEvent> = listOf(
                AgentEvent.AgentStart(),
                AgentEvent.AgentEnd(emptyList()),
                AgentEvent.TurnStart,
                AgentEvent.TurnEnd(AgentMessage.Assistant(), emptyList()),
                AgentEvent.MessageStart(AgentMessage.Assistant()),
                AgentEvent.MessageUpdate(AgentMessage.Assistant(), StreamDelta.TextDelta("")),
                AgentEvent.MessageEnd(AgentMessage.Assistant()),
                AgentEvent.MessageRecord(AgentMessage.User("x")),
                AgentEvent.ToolExecutionStart("1", "t", "{}"),
                AgentEvent.ToolExecutionEnd("1", "t", "", false),
            )

            val types = events.map { event ->
                when (event) {
                    is AgentEvent.AgentStart -> "agent_start"
                    is AgentEvent.AgentEnd -> "agent_end"
                    is AgentEvent.TurnStart -> "turn_start"
                    is AgentEvent.TurnEnd -> "turn_end"
                    is AgentEvent.MessageStart -> "message_start"
                    is AgentEvent.MessageUpdate -> "message_update"
                    is AgentEvent.MessageEnd -> "message_end"
                    is AgentEvent.MessageRecord -> "message_record"
                    is AgentEvent.ToolExecutionStart -> "tool_exec_start"
                    is AgentEvent.ToolExecutionEnd -> "tool_exec_end"
                }
            }
            assertEquals(10, types.size)
        }
    }
}
