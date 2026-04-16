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

            tracer.processEvent(AgentEvent.AgentStart())

            val sessions = store.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("test-chat", sessions[0].chatId)
            assertEquals("running", sessions[0].status)
        }

        @Test
        fun `AgentStart records system prompt in detail`() = runTest {
            val (store, tracer) = createTracer()

            tracer.processEvent(AgentEvent.AgentStart("You are helpful."))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val agentStart = events.first { it.eventType == "agent" && it.phase == "start" }
            assertEquals("16", agentStart.data["system_prompt_length"])
            assertNotNull(agentStart.detail)
            assertTrue(agentStart.detail!!.contains("You are helpful."))
        }

        @Test
        fun `AgentEnd completes the session`() = runTest {
            val (store, tracer) = createTracer()

            tracer.processEvent(AgentEvent.AgentStart())
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
            tracer.processEvent(AgentEvent.AgentStart())

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
        fun `records instant message event for user`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart())

            val userMsg = AgentMessage.User("hello")
            tracer.processEvent(AgentEvent.MessageRecord(userMsg))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" }
            assertEquals(1, messageEvents.size)
            assertEquals("instant", messageEvents[0].phase)
            assertEquals("user", messageEvents[0].data["type"])
        }

        @Test
        fun `records start and end for assistant message`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart())

            val assistant = AgentMessage.Assistant(
                content = listOf(ContentPart.Text("hi")),
            )
            tracer.processEvent(AgentEvent.MessageStart(assistant))
            tracer.processEvent(AgentEvent.MessageEnd(assistant))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" }
            assertEquals(2, messageEvents.size)
            assertEquals("start", messageEvents[0].phase)
            assertEquals("end", messageEvents[1].phase)
        }

        @Test
        fun `tool result instant message references tool call id without full payload`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart())

            val toolResult = AgentMessage.ToolResult(
                toolCallId = "tc-1",
                toolName = "search",
                text = "very long result that should not be duplicated",
            )
            tracer.processEvent(AgentEvent.MessageRecord(toolResult))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" }
            assertEquals(1, messageEvents.size)
            assertEquals("instant", messageEvents[0].phase)
            assertEquals("tool_result", messageEvents[0].data["type"])
            assertEquals("tc-1", messageEvents[0].data["tool_call_id"])
            assertEquals("search", messageEvents[0].data["tool_name"])
            // Detail should NOT contain the full result text
            assertNotNull(messageEvents[0].detail)
            assertTrue(!messageEvents[0].detail!!.contains("very long result"))
        }

        @Test
        fun `records assistant message with usage`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart())

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
            tracer.processEvent(AgentEvent.AgentStart())

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
            tracer.processEvent(AgentEvent.AgentStart())

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
            tracer.processEvent(AgentEvent.AgentStart())

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
            tracer.processEvent(AgentEvent.AgentStart())

            // Instant messages (user, tool_result, platform)
            tracer.processEvent(AgentEvent.MessageRecord(AgentMessage.User("hi")))
            tracer.processEvent(AgentEvent.MessageRecord(AgentMessage.ToolResult("tc-1", "t", text = "r")))
            tracer.processEvent(AgentEvent.MessageRecord(AgentMessage.Platform("test", Unit)))
            // Streaming message (assistant)
            tracer.processEvent(AgentEvent.MessageStart(AgentMessage.Assistant()))

            val sessions = store.listSessions()
            val events = store.getSessionEvents(sessions[0].id)
            val messageEvents = events.filter { it.eventType == "message" }
            assertEquals(4, messageEvents.size)
            assertEquals("user", messageEvents[0].data["type"])
            assertEquals("instant", messageEvents[0].phase)
            assertEquals("tool_result", messageEvents[1].data["type"])
            assertEquals("instant", messageEvents[1].phase)
            assertEquals("platform", messageEvents[2].data["type"])
            assertEquals("instant", messageEvents[2].phase)
            assertEquals("assistant", messageEvents[3].data["type"])
            assertEquals("start", messageEvents[3].phase)
        }
    }

    @Nested
    inner class TurnCounting {
        @Test
        fun `tracks turn numbers`() = runTest {
            val (store, tracer) = createTracer()
            tracer.processEvent(AgentEvent.AgentStart())

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

    @Nested
    inner class MultipleSessions {
        /**
         * Regression: every message in a chat produces its own fresh tracer + session.
         * Used to silently drop events on the second session when tracer listeners
         * subscribed asynchronously via [Agent.events] and raced past the initial
         * `AgentStart`. The assistant path now uses direct listener registration via
         * [fraggle.agent.Agent.addEventListener], which this test mirrors by driving
         * two fresh tracers against a shared [TraceStore].
         */
        @Test
        fun `two sequential tracer cycles both record events`() = runTest {
            val store = TraceStore()

            // Cycle 1 — first message.
            val tracer1 = AgentEventTracer(store, eventBus = null, chatId = "chat-1")
            tracer1.processEvent(AgentEvent.AgentStart())
            tracer1.processEvent(AgentEvent.TurnStart)
            tracer1.processEvent(AgentEvent.MessageRecord(AgentMessage.User("hello")))
            tracer1.processEvent(AgentEvent.MessageStart(AgentMessage.Assistant()))
            tracer1.processEvent(AgentEvent.MessageEnd(AgentMessage.Assistant()))
            tracer1.processEvent(AgentEvent.TurnEnd(AgentMessage.Assistant(), emptyList()))
            tracer1.processEvent(AgentEvent.AgentEnd(emptyList()))

            // Cycle 2 — second message. Fresh tracer, same store.
            val tracer2 = AgentEventTracer(store, eventBus = null, chatId = "chat-1")
            tracer2.processEvent(AgentEvent.AgentStart())
            tracer2.processEvent(AgentEvent.TurnStart)
            tracer2.processEvent(AgentEvent.MessageRecord(AgentMessage.User("again")))
            tracer2.processEvent(AgentEvent.MessageStart(AgentMessage.Assistant()))
            tracer2.processEvent(AgentEvent.MessageEnd(AgentMessage.Assistant()))
            tracer2.processEvent(AgentEvent.TurnEnd(AgentMessage.Assistant(), emptyList()))
            tracer2.processEvent(AgentEvent.AgentEnd(emptyList()))

            val sessions = store.listSessions()
            assertEquals(2, sessions.size)
            for (session in sessions) {
                assertEquals("completed", session.status)
                val events = store.getSessionEvents(session.id)
                assertTrue(events.any { it.eventType == "agent" && it.phase == "start" })
                assertTrue(events.any { it.eventType == "agent" && it.phase == "end" })
                assertTrue(events.any { it.eventType == "turn" && it.phase == "start" })
                assertTrue(events.any { it.eventType == "turn" && it.phase == "end" })
                assertTrue(events.filter { it.eventType == "message" }.size >= 3)
            }
        }
    }
}
