package fraggle.agent.tracing

import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.TokenUsage
import fraggle.agent.message.ToolCall
import fraggle.tracing.TraceStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentEventTracerTest {

    private fun createTracer(): Pair<TraceStore, AgentEventTracer> {
        val store = TraceStore()
        val tracer = AgentEventTracer(store, eventBus = null, chatId = "test-chat")
        return store to tracer
    }

    @Nested
    inner class SessionLifecycle {
        @Test
        fun `AgentStart creates a trace session`() = runTest {
            val (store, tracer) = createTracer()

            tracer.processEvent(AgentEvent.AgentStart)

            val sessions = store.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("test-chat", sessions[0].chatId)
            assertEquals("running", sessions[0].status)
        }

        @Test
        fun `AgentEnd completes the session`() = runTest {
            val (store, tracer) = createTracer()

            tracer.processEvent(AgentEvent.AgentStart)
            tracer.processEvent(AgentEvent.AgentEnd(emptyList()))

            val sessions = store.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("completed", sessions[0].status)
            assertNotNull(sessions[0].endTime)
        }
    }

    @Nested
    inner class EventRecording {
        @Test
        fun `records turn events`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            tracer.processEvent(AgentEvent.TurnStart)
            tracer.processEvent(AgentEvent.TurnEnd(
                AgentMessage.Assistant(),
                emptyList(),
            ))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            // agent start, turn start, turn end
            assertTrue(events.size >= 3)
            assertTrue(events.any { it.eventType == "turn" && it.phase == "start" })
            assertTrue(events.any { it.eventType == "turn" && it.phase == "end" })
        }

        @Test
        fun `records message events`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            val userMsg = AgentMessage.User("hello")
            tracer.processEvent(AgentEvent.MessageStart(userMsg))
            tracer.processEvent(AgentEvent.MessageEnd(userMsg))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" }
            assertEquals(2, messageEvents.size) // start + end
            assertEquals("user", messageEvents[0].data["type"])
        }

        @Test
        fun `records assistant message with usage`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            val assistant = AgentMessage.Assistant(
                content = listOf(ContentPart.Text("hi")),
                usage = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
            )
            tracer.processEvent(AgentEvent.MessageEnd(assistant))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val endEvent = events.last()
            assertEquals("15", endEvent.data["total_tokens"])
        }

        @Test
        fun `records assistant message with tool calls`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            val assistant = AgentMessage.Assistant(
                toolCalls = listOf(
                    ToolCall("tc-1", "search", "{}"),
                    ToolCall("tc-2", "read", "{}"),
                ),
            )
            tracer.processEvent(AgentEvent.MessageEnd(assistant))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val endEvent = events.last()
            assertEquals("2", endEvent.data["tool_calls"])
        }

        @Test
        fun `records tool execution events`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            tracer.processEvent(AgentEvent.ToolExecutionStart("tc-1", "search", """{"q":"test"}"""))
            tracer.processEvent(AgentEvent.ToolExecutionEnd("tc-1", "search", "found", false))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val toolEvents = events.filter { it.eventType == "tool" }
            assertEquals(2, toolEvents.size)
            assertEquals("search", toolEvents[0].data["tool_name"])
            assertEquals("false", toolEvents[1].data["is_error"])
        }

        @Test
        fun `records tool error`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            tracer.processEvent(AgentEvent.ToolExecutionEnd("tc-1", "fail", "error", true))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val endEvent = events.filter { it.eventType == "tool" }.last()
            assertEquals("true", endEvent.data["is_error"])
        }
    }

    @Nested
    inner class MessageTypeMapping {
        @Test
        fun `maps all message types correctly`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            val messages = listOf(
                AgentMessage.User("hi") to "user",
                AgentMessage.Assistant() to "assistant",
                AgentMessage.ToolResult("tc-1", "t", text = "r") to "tool_result",
                AgentMessage.Platform("test", Unit) to "platform",
            )

            for ((msg, expectedType) in messages) {
                tracer.processEvent(AgentEvent.MessageStart(msg))
            }

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" && it.phase == "start" }
            assertEquals(4, messageEvents.size)
            assertEquals("user", messageEvents[0].data["type"])
            assertEquals("assistant", messageEvents[1].data["type"])
            assertEquals("tool_result", messageEvents[2].data["type"])
            assertEquals("platform", messageEvents[3].data["type"])
        }
    }

    @Nested
    inner class TurnCounting {
        @Test
        fun `tracks turn numbers`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart)

            tracer.processEvent(AgentEvent.TurnStart)
            tracer.processEvent(AgentEvent.TurnEnd(AgentMessage.Assistant(), emptyList()))
            tracer.processEvent(AgentEvent.TurnStart)
            tracer.processEvent(AgentEvent.TurnEnd(AgentMessage.Assistant(), emptyList()))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val turnStarts = events.filter { it.eventType == "turn" && it.phase == "start" }
            assertEquals(2, turnStarts.size)
            assertEquals("1", turnStarts[0].data["turn"])
            assertEquals("2", turnStarts[1].data["turn"])
        }
    }
}
