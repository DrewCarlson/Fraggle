package fraggle.agent.state

import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentStateTest {

    @Nested
    inner class AgentStateSnapshot {
        @Test
        fun `defaults are sensible`() {
            val state = AgentState(
                systemPrompt = "You are helpful.",
                model = "test-model",
                messages = emptyList(),
            )
            assertFalse(state.isStreaming)
            assertNull(state.streamingMessage)
            assertTrue(state.pendingToolCalls.isEmpty())
            assertNull(state.errorMessage)
        }

        @Test
        fun `data class copy works for immutable updates`() {
            val state = AgentState(
                systemPrompt = "prompt",
                model = "model-a",
                messages = emptyList(),
            )
            val updated = state.copy(model = "model-b", isStreaming = true)
            assertEquals("model-b", updated.model)
            assertTrue(updated.isStreaming)
            assertEquals("prompt", updated.systemPrompt)
        }

        @Test
        fun `equality checks work`() {
            val state1 = AgentState("p", "m", emptyList())
            val state2 = AgentState("p", "m", emptyList())
            assertEquals(state1, state2)
        }
    }

    @Nested
    inner class MutableAgentStateTests {
        @Test
        fun `snapshot returns immutable copy`() {
            val initial = AgentState("system", "model", emptyList())
            val mutable = MutableAgentState(initial)
            mutable.systemPrompt = "updated system"
            mutable.model = "new-model"

            val snapshot = mutable.snapshot()
            assertEquals("updated system", snapshot.systemPrompt)
            assertEquals("new-model", snapshot.model)
        }

        @Test
        fun `pushMessage appends to messages`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            mutable.pushMessage(AgentMessage.User("first"))
            mutable.pushMessage(AgentMessage.User("second"))

            val snapshot = mutable.snapshot()
            assertEquals(2, snapshot.messages.size)
            assertEquals("first", (snapshot.messages[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
            assertEquals("second", (snapshot.messages[1] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
        }

        @Test
        fun `replaceLastMessage replaces last message`() {
            val initial = AgentState("s", "m", listOf(AgentMessage.User("original")))
            val mutable = MutableAgentState(initial)

            mutable.replaceLastMessage(AgentMessage.User("replaced"))

            val snapshot = mutable.snapshot()
            assertEquals(1, snapshot.messages.size)
            assertEquals("replaced", (snapshot.messages[0] as AgentMessage.User).content.first().let { (it as ContentPart.Text).text })
        }

        @Test
        fun `replaceLastMessage throws on empty list`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            assertFailsWith<IllegalArgumentException> {
                mutable.replaceLastMessage(AgentMessage.User("oops"))
            }
        }

        @Test
        fun `reset clears transient state but preserves config`() {
            val initial = AgentState(
                systemPrompt = "sys",
                model = "mdl",
                messages = listOf(AgentMessage.User("msg")),
                isStreaming = true,
                pendingToolCalls = setOf("tc-1"),
                errorMessage = "some error",
            )
            val mutable = MutableAgentState(initial)
            mutable.reset()

            val snapshot = mutable.snapshot()
            assertEquals("sys", snapshot.systemPrompt)
            assertEquals("mdl", snapshot.model)
            assertTrue(snapshot.messages.isEmpty())
            assertFalse(snapshot.isStreaming)
            assertNull(snapshot.streamingMessage)
            assertTrue(snapshot.pendingToolCalls.isEmpty())
            assertNull(snapshot.errorMessage)
        }

        @Test
        fun `messages setter creates defensive copy`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            val externalList = mutableListOf<AgentMessage>(AgentMessage.User("a"))
            mutable.messages = externalList

            // Modifying the external list should not affect internal state
            externalList.add(AgentMessage.User("b"))
            assertEquals(1, mutable.messages.size)
        }

        @Test
        fun `snapshot is independent of further mutations`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)
            mutable.pushMessage(AgentMessage.User("first"))

            val snapshot1 = mutable.snapshot()
            mutable.pushMessage(AgentMessage.User("second"))

            val snapshot2 = mutable.snapshot()
            assertEquals(1, snapshot1.messages.size)
            assertEquals(2, snapshot2.messages.size)
        }

        @Test
        fun `pending tool calls tracking`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            mutable.pendingToolCalls = mutable.pendingToolCalls + "tc-1"
            mutable.pendingToolCalls = mutable.pendingToolCalls + "tc-2"
            assertEquals(setOf("tc-1", "tc-2"), mutable.snapshot().pendingToolCalls)

            mutable.pendingToolCalls = mutable.pendingToolCalls - "tc-1"
            assertEquals(setOf("tc-2"), mutable.snapshot().pendingToolCalls)
        }

        @Test
        fun `streaming message tracking`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            val streaming = AgentMessage.Assistant(content = listOf(ContentPart.Text("partial")))
            mutable.isStreaming = true
            mutable.streamingMessage = streaming

            val snapshot = mutable.snapshot()
            assertTrue(snapshot.isStreaming)
            assertEquals("partial", snapshot.streamingMessage?.textContent)
        }

        @Test
        fun `error message tracking`() {
            val initial = AgentState("s", "m", emptyList())
            val mutable = MutableAgentState(initial)

            mutable.errorMessage = "LLM timeout"
            assertEquals("LLM timeout", mutable.snapshot().errorMessage)
        }
    }
}
