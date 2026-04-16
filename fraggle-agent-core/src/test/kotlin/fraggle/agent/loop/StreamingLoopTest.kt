package fraggle.agent.loop

import fraggle.agent.event.AgentEvent
import fraggle.agent.event.StreamDelta
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamingLoopTest {

    private fun eventCollector(): Pair<MutableList<AgentEvent>, EventSink> {
        val events = mutableListOf<AgentEvent>()
        return events to { events.add(it) }
    }

    /** A streaming bridge that simulates token-by-token delivery. */
    private fun streamingBridge(
        tokens: List<String>,
    ): StreamingLlmBridge = object : StreamingLlmBridge {
        override suspend fun call(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
        ): AgentMessage.Assistant {
            val text = tokens.joinToString("")
            return AgentMessage.Assistant(content = listOf(ContentPart.Text(text)))
        }

        override suspend fun callStreaming(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
            onDelta: suspend (StreamDelta, AgentMessage.Assistant) -> Unit,
        ): AgentMessage.Assistant {
            val builder = StringBuilder()
            for (token in tokens) {
                builder.append(token)
                val partial = AgentMessage.Assistant(
                    content = listOf(ContentPart.Text(builder.toString())),
                )
                onDelta(StreamDelta.TextDelta(token), partial)
            }
            return AgentMessage.Assistant(
                content = listOf(ContentPart.Text(builder.toString())),
            )
        }
    }

    /** A streaming bridge that simulates tool call streaming. */
    private fun streamingToolBridge(): StreamingLlmBridge {
        var callCount = 0
        return object : StreamingLlmBridge {
            override suspend fun call(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<ToolDefinition>,
            ): AgentMessage.Assistant {
                callCount++
                return if (callCount == 1) {
                    AgentMessage.Assistant(
                        toolCalls = listOf(ToolCall("tc-1", "tool", """{"a":1}""")),
                    )
                } else {
                    AgentMessage.Assistant(content = listOf(ContentPart.Text("done")))
                }
            }

            override suspend fun callStreaming(
                systemPrompt: String,
                messages: List<AgentMessage>,
                tools: List<ToolDefinition>,
                onDelta: suspend (StreamDelta, AgentMessage.Assistant) -> Unit,
            ): AgentMessage.Assistant {
                callCount++
                if (callCount == 1) {
                    // Simulate streaming tool call
                    val partial1 = AgentMessage.Assistant(
                        toolCalls = listOf(ToolCall("tc-1", "tool", """{"a""")),
                    )
                    onDelta(StreamDelta.ToolCallDelta("tc-1", """{"a"""), partial1)

                    val partial2 = AgentMessage.Assistant(
                        toolCalls = listOf(ToolCall("tc-1", "tool", """{"a":1}""")),
                    )
                    onDelta(StreamDelta.ToolCallDelta("tc-1", """:1}"""), partial2)

                    return AgentMessage.Assistant(
                        toolCalls = listOf(ToolCall("tc-1", "tool", """{"a":1}""")),
                    )
                } else {
                    val partial = AgentMessage.Assistant(
                        content = listOf(ContentPart.Text("done")),
                    )
                    onDelta(StreamDelta.TextDelta("done"), partial)
                    return partial
                }
            }
        }
    }

    @Nested
    inner class TextStreaming {
        @Test
        fun `emits MessageUpdate deltas during streaming`() = runTest {
            val (events, sink) = eventCollector()

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hello")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = streamingBridge(listOf("Hello", " ", "world", "!")),
                ),
                emit = sink,
            )

            // Should have MessageUpdate events
            val updates = events.filterIsInstance<AgentEvent.MessageUpdate>()
            assertEquals(4, updates.size, "Should have 4 streaming updates")

            // Verify deltas
            assertEquals("Hello", (updates[0].delta as StreamDelta.TextDelta).text)
            assertEquals(" ", (updates[1].delta as StreamDelta.TextDelta).text)
            assertEquals("world", (updates[2].delta as StreamDelta.TextDelta).text)
            assertEquals("!", (updates[3].delta as StreamDelta.TextDelta).text)

            // Verify progressive partial messages
            assertEquals("Hello", updates[0].message.textContent)
            assertEquals("Hello ", updates[1].message.textContent)
            assertEquals("Hello world", updates[2].message.textContent)
            assertEquals("Hello world!", updates[3].message.textContent)

            // Final message should have full text
            val assistant = newMessages.filterIsInstance<AgentMessage.Assistant>().first()
            assertEquals("Hello world!", assistant.textContent)
        }

        @Test
        fun `streaming emits correct event sequence`() = runTest {
            val (events, sink) = eventCollector()

            runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = streamingBridge(listOf("Hi", "!")),
                ),
                emit = sink,
            )

            // Find the assistant message lifecycle events (skip the user MessageRecord)
            val assistantEvents = events.dropWhile { event ->
                event !is AgentEvent.MessageStart || event.message !is AgentMessage.Assistant
            }
            assertTrue(assistantEvents.isNotEmpty(), "Should have assistant events")

            assertIs<AgentEvent.MessageStart>(assistantEvents[0])
            assertIs<AgentEvent.MessageUpdate>(assistantEvents[1])
            assertIs<AgentEvent.MessageUpdate>(assistantEvents[2])
            assertIs<AgentEvent.MessageEnd>(assistantEvents[3])
        }
    }

    @Nested
    inner class ToolCallStreaming {
        @Test
        fun `streaming tool calls emit ToolCallDelta events`() = runTest {
            val (events, sink) = eventCollector()

            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String) =
                    ToolCallResult("result")
                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = streamingToolBridge(),
                    toolExecutor = executor,
                ),
                emit = sink,
            )

            // Should have ToolCallDelta events in the MessageUpdate events
            val updates = events.filterIsInstance<AgentEvent.MessageUpdate>()
            val toolCallDeltas = updates.filter { it.delta is StreamDelta.ToolCallDelta }
            assertTrue(toolCallDeltas.isNotEmpty(), "Should have tool call delta events")
        }
    }

    @Nested
    inner class NonStreamingFallback {
        @Test
        fun `non-streaming bridge still works correctly`() = runTest {
            val (events, sink) = eventCollector()

            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(content = listOf(ContentPart.Text("response")))
            }

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = bridge),
                emit = sink,
            )

            // Should NOT have MessageUpdate events (non-streaming)
            val updates = events.filterIsInstance<AgentEvent.MessageUpdate>()
            assertTrue(updates.isEmpty(), "Non-streaming bridge should not emit MessageUpdate")

            // Should still have MessageStart and MessageEnd for assistant, MessageRecord for user
            val starts = events.filterIsInstance<AgentEvent.MessageStart>()
            val ends = events.filterIsInstance<AgentEvent.MessageEnd>()
            val records = events.filterIsInstance<AgentEvent.MessageRecord>()
            assertTrue(starts.isNotEmpty(), "Should have assistant MessageStart")
            assertTrue(ends.isNotEmpty(), "Should have assistant MessageEnd")
            assertTrue(records.isNotEmpty(), "Should have user MessageRecord")

            assertEquals("response", (newMessages[1] as AgentMessage.Assistant).textContent)
        }
    }
}
