package fraggle.agent.loop

import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {

    /** Simple LLM bridge that returns a canned response. */
    private fun staticLlmBridge(text: String) = LlmBridge { _, _, _ ->
        AgentMessage.Assistant(content = listOf(ContentPart.Text(text)))
    }

    /** LLM bridge that uses a sequence of responses. */
    private fun sequenceLlmBridge(vararg responses: AgentMessage.Assistant): LlmBridge {
        val iter = responses.iterator()
        return LlmBridge { _, _, _ ->
            if (iter.hasNext()) iter.next()
            else AgentMessage.Assistant(content = listOf(ContentPart.Text("done")))
        }
    }

    /** Collects events into a list. */
    private fun eventCollector(): Pair<MutableList<AgentEvent>, EventSink> {
        val events = mutableListOf<AgentEvent>()
        val sink: EventSink = { events.add(it) }
        return events to sink
    }

    /** Simple tool executor that returns a static result. */
    private fun staticToolExecutor(
        result: String = "tool result",
        isError: Boolean = false,
        definitions: List<ToolDefinition> = emptyList(),
    ) = object : ToolCallExecutor {
        override suspend fun execute(toolCall: ToolCall, chatId: String) =
            ToolCallResult(content = result, isError = isError)
        override fun getToolDefinitions() = definitions
    }

    @Nested
    inner class BasicFlow {
        @Test
        fun `simple prompt produces expected event sequence`() = runTest {
            val (events, sink) = eventCollector()

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hello")),
                systemPrompt = "You are helpful.",
                messages = emptyList(),
                chatId = "test-chat",
                config = AgentLoopConfig(llmBridge = staticLlmBridge("Hi there!")),
                emit = sink,
            )

            // Should have user message + assistant message
            assertEquals(2, newMessages.size)
            assertIs<AgentMessage.User>(newMessages[0])
            assertIs<AgentMessage.Assistant>(newMessages[1])
            assertEquals("Hi there!", (newMessages[1] as AgentMessage.Assistant).textContent)

            // Check event sequence
            assertIs<AgentEvent.AgentStart>(events[0])
            assertIs<AgentEvent.TurnStart>(events[1])
            assertIs<AgentEvent.MessageRecord>(events[2]) // user (instant)
            assertIs<AgentEvent.MessageStart>(events[3])  // assistant
            assertIs<AgentEvent.MessageEnd>(events[4])    // assistant
            assertIs<AgentEvent.TurnEnd>(events[5])
            assertIs<AgentEvent.AgentEnd>(events[6])
        }

        @Test
        fun `preserves existing conversation history`() = runTest {
            val (_, sink) = eventCollector()
            var capturedMessages: List<AgentMessage>? = null

            val bridge = LlmBridge { _, msgs, _ ->
                capturedMessages = msgs
                AgentMessage.Assistant(content = listOf(ContentPart.Text("response")))
            }

            val history = listOf(
                AgentMessage.User("old message"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("old response"))),
            )

            runAgentLoop(
                prompts = listOf(AgentMessage.User("new message")),
                systemPrompt = "system",
                messages = history,
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = bridge),
                emit = sink,
            )

            // LLM should see history + new prompt
            assertEquals(3, capturedMessages?.size)
        }

        @Test
        fun `passes system prompt to LLM bridge`() = runTest {
            val (_, sink) = eventCollector()
            var capturedSystemPrompt: String? = null

            val bridge = LlmBridge { sys, _, _ ->
                capturedSystemPrompt = sys
                AgentMessage.Assistant(content = listOf(ContentPart.Text("ok")))
            }

            runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "Be helpful",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = bridge),
                emit = sink,
            )

            assertEquals("Be helpful", capturedSystemPrompt)
        }
    }

    @Nested
    inner class ToolExecution {
        @Test
        fun `executes tool calls and feeds results back`() = runTest {
            val (events, sink) = eventCollector()

            // First response has a tool call, second has final text
            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "search", """{"q":"test"}""")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("Found it!"))),
            )
            val bridge = sequenceLlmBridge(*responses)
            val executor = staticToolExecutor("search result")

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("search for test")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = bridge,
                    toolExecutor = executor,
                ),
                emit = sink,
            )

            // user, assistant(tool call), tool result, assistant(final)
            assertEquals(4, newMessages.size)
            assertIs<AgentMessage.User>(newMessages[0])
            assertIs<AgentMessage.Assistant>(newMessages[1])
            assertIs<AgentMessage.ToolResult>(newMessages[2])
            assertIs<AgentMessage.Assistant>(newMessages[3])

            assertEquals("search result", (newMessages[2] as AgentMessage.ToolResult).textContent)
            assertEquals("Found it!", (newMessages[3] as AgentMessage.Assistant).textContent)

            // Check tool execution events
            val toolStartEvents = events.filterIsInstance<AgentEvent.ToolExecutionStart>()
            val toolEndEvents = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
            assertEquals(1, toolStartEvents.size)
            assertEquals(1, toolEndEvents.size)
            assertEquals("search", toolStartEvents[0].toolName)
            assertEquals("search result", toolEndEvents[0].result)
        }

        @Test
        fun `handles tool execution errors`() = runTest {
            val (events, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "fail_tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("I see the error."))),
            )
            val executor = staticToolExecutor("connection refused", isError = true)

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("try it")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = sequenceLlmBridge(*responses),
                    toolExecutor = executor,
                ),
                emit = sink,
            )

            val toolResult = newMessages.filterIsInstance<AgentMessage.ToolResult>().first()
            assertTrue(toolResult.isError)
            assertEquals("connection refused", toolResult.textContent)

            val endEvent = events.filterIsInstance<AgentEvent.ToolExecutionEnd>().first()
            assertTrue(endEvent.isError)
        }

        @Test
        fun `handles tool executor throwing exception`() = runTest {
            val (_, sink) = eventCollector()

            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
                    throw RuntimeException("boom")
                }
                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "crash_tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("ok"))),
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = sequenceLlmBridge(*responses),
                    toolExecutor = executor,
                ),
                emit = sink,
            )

            val toolResult = newMessages.filterIsInstance<AgentMessage.ToolResult>().first()
            assertTrue(toolResult.isError)
            assertTrue(toolResult.textContent.contains("boom"))
        }

        @Test
        fun `multiple tool calls in one response`() = runTest {
            val (events, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(
                        ToolCall("tc-1", "tool_a", "{}"),
                        ToolCall("tc-2", "tool_b", "{}"),
                    ),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("done"))),
            )

            val callOrder = mutableListOf<String>()
            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
                    callOrder.add(toolCall.name)
                    return ToolCallResult("result_${toolCall.name}")
                }
                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = sequenceLlmBridge(*responses),
                    toolExecutor = executor,
                    toolExecution = ToolExecutionMode.SEQUENTIAL,
                ),
                emit = sink,
            )

            // Sequential mode should execute in order
            assertEquals(listOf("tool_a", "tool_b"), callOrder)

            val toolEndEvents = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()
            assertEquals(2, toolEndEvents.size)
        }

        @Test
        fun `no executor means tool calls are ignored`() = runTest {
            val (_, sink) = eventCollector()

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = LlmBridge { _, _, _ ->
                        AgentMessage.Assistant(
                            toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                        )
                    },
                    toolExecutor = null,
                ),
                emit = sink,
            )

            // Should have user + assistant (no tool results, loop stops)
            assertEquals(2, newMessages.size)
        }
    }

    @Nested
    inner class MaxIterations {
        @Test
        fun `stops after maxIterations`() = runTest {
            val (events, sink) = eventCollector()

            // LLM always requests a tool call
            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "infinite", "{}")),
                )
            }
            val executor = staticToolExecutor("ok")

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = bridge,
                    toolExecutor = executor,
                    maxIterations = 3,
                ),
                emit = sink,
            )

            // Should stop with an error message after 3 iterations
            val lastAssistant = newMessages.filterIsInstance<AgentMessage.Assistant>().last()
            assertEquals(StopReason.ERROR, lastAssistant.stopReason)
            assertTrue(lastAssistant.errorMessage?.contains("Maximum iterations") == true)

            // Should have emitted AgentEnd
            assertTrue(events.any { it is AgentEvent.AgentEnd })
        }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `stops on ERROR stop reason`() = runTest {
            val (events, sink) = eventCollector()

            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    content = listOf(ContentPart.Text("error occurred")),
                    stopReason = StopReason.ERROR,
                    errorMessage = "LLM error",
                )
            }

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = bridge),
                emit = sink,
            )

            val assistant = newMessages.filterIsInstance<AgentMessage.Assistant>().last()
            assertEquals(StopReason.ERROR, assistant.stopReason)
            assertTrue(events.last() is AgentEvent.AgentEnd)
        }

        @Test
        fun `stops on ABORTED stop reason`() = runTest {
            val (events, sink) = eventCollector()

            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    stopReason = StopReason.ABORTED,
                )
            }

            runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = bridge),
                emit = sink,
            )

            assertTrue(events.last() is AgentEvent.AgentEnd)
        }
    }

    @Nested
    inner class ContinueFlow {
        @Test
        fun `continue from tool result`() = runTest {
            val (_, sink) = eventCollector()

            val existingMessages = listOf(
                AgentMessage.User("hello"),
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "search", "{}")),
                ),
                AgentMessage.ToolResult(toolCallId = "tc-1", toolName = "search", text = "found"),
            )

            val newMessages = runAgentLoopContinue(
                systemPrompt = "system",
                messages = existingMessages,
                chatId = "chat",
                config = AgentLoopConfig(llmBridge = staticLlmBridge("continued")),
                emit = sink,
            )

            assertEquals(1, newMessages.size)
            assertEquals("continued", (newMessages[0] as AgentMessage.Assistant).textContent)
        }

        @Test
        fun `cannot continue from empty messages`() = runTest {
            val (_, sink) = eventCollector()

            assertFailsWith<IllegalArgumentException> {
                runAgentLoopContinue(
                    systemPrompt = "system",
                    messages = emptyList(),
                    chatId = "chat",
                    config = AgentLoopConfig(llmBridge = staticLlmBridge("x")),
                    emit = sink,
                )
            }
        }

        @Test
        fun `cannot continue from assistant message`() = runTest {
            val (_, sink) = eventCollector()

            assertFailsWith<IllegalArgumentException> {
                runAgentLoopContinue(
                    systemPrompt = "system",
                    messages = listOf(AgentMessage.Assistant()),
                    chatId = "chat",
                    config = AgentLoopConfig(llmBridge = staticLlmBridge("x")),
                    emit = sink,
                )
            }
        }
    }

    @Nested
    inner class SteeringMessages {
        @Test
        fun `steering messages queued before loop are injected before first LLM call`() = runTest {
            val (events, sink) = eventCollector()
            var capturedMessages: List<AgentMessage>? = null

            val steering = mutableListOf(AgentMessage.User("steering hint"))

            val bridge = LlmBridge { _, msgs, _ ->
                capturedMessages = msgs
                AgentMessage.Assistant(content = listOf(ContentPart.Text("ok")))
            }

            val config = AgentLoopConfig(
                llmBridge = bridge,
                getSteeringMessages = {
                    if (steering.isNotEmpty()) {
                        steering.toList().also { steering.clear() }
                    } else {
                        emptyList()
                    }
                },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("hi")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            // Steering message is injected before the first LLM call
            // So LLM sees: user("hi") + user("steering hint")
            assertEquals(2, capturedMessages?.count { it is AgentMessage.User })

            // New messages: user, steering user, assistant
            assertEquals(3, newMessages.size)
        }

        @Test
        fun `steering messages injected between tool iterations`() = runTest {
            val (_, sink) = eventCollector()
            var llmCallCount = 0
            val steeringAvailable = mutableListOf<AgentMessage>()

            val bridge = LlmBridge { _, msgs, _ ->
                llmCallCount++
                when (llmCallCount) {
                    1 -> {
                        // After this call, queue a steering message for the next turn
                        steeringAvailable.add(AgentMessage.User("mid-run steering"))
                        AgentMessage.Assistant(
                            toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                        )
                    }
                    else -> AgentMessage.Assistant(content = listOf(ContentPart.Text("final")))
                }
            }

            val config = AgentLoopConfig(
                llmBridge = bridge,
                toolExecutor = staticToolExecutor("result"),
                getSteeringMessages = {
                    if (steeringAvailable.isNotEmpty()) {
                        steeringAvailable.toList().also { steeringAvailable.clear() }
                    } else {
                        emptyList()
                    }
                },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            // user, assistant(tool), tool result, steering user, assistant(final)
            assertEquals(5, newMessages.size)
            assertIs<AgentMessage.User>(newMessages[3])
            assertIs<AgentMessage.Assistant>(newMessages[4])
            assertEquals(2, llmCallCount)
        }
    }

    @Nested
    inner class FollowUpMessages {
        @Test
        fun `follow-up messages trigger additional turns`() = runTest {
            val (_, sink) = eventCollector()
            var callCount = 0

            val followUps = mutableListOf(AgentMessage.User("follow up"))

            val bridge = LlmBridge { _, msgs, _ ->
                callCount++
                AgentMessage.Assistant(content = listOf(ContentPart.Text("response $callCount")))
            }

            val config = AgentLoopConfig(
                llmBridge = bridge,
                getFollowUpMessages = {
                    if (followUps.isNotEmpty()) {
                        followUps.toList().also { followUps.clear() }
                    } else {
                        emptyList()
                    }
                },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("initial")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            // initial user, response 1, follow-up user, response 2
            assertEquals(4, newMessages.size)
            assertEquals(2, callCount)
        }
    }

    @Nested
    inner class AfterToolCallHook {
        @Test
        fun `afterToolCall can override result`() = runTest {
            val (_, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("done"))),
            )

            val config = AgentLoopConfig(
                llmBridge = sequenceLlmBridge(*responses),
                toolExecutor = staticToolExecutor("original result"),
                afterToolCall = { ctx ->
                    AfterToolCallResult(content = "modified: ${ctx.result}")
                },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            val toolResult = newMessages.filterIsInstance<AgentMessage.ToolResult>().first()
            assertEquals("modified: original result", toolResult.textContent)
        }

        @Test
        fun `afterToolCall can override error flag`() = runTest {
            val (_, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("done"))),
            )

            val config = AgentLoopConfig(
                llmBridge = sequenceLlmBridge(*responses),
                toolExecutor = staticToolExecutor("result", isError = true),
                afterToolCall = { AfterToolCallResult(isError = false) },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            val toolResult = newMessages.filterIsInstance<AgentMessage.ToolResult>().first()
            assertEquals(false, toolResult.isError)
        }

        @Test
        fun `afterToolCall returning null leaves result unchanged`() = runTest {
            val (_, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("done"))),
            )

            val config = AgentLoopConfig(
                llmBridge = sequenceLlmBridge(*responses),
                toolExecutor = staticToolExecutor("original"),
                afterToolCall = { null },
            )

            val newMessages = runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = config,
                emit = sink,
            )

            val toolResult = newMessages.filterIsInstance<AgentMessage.ToolResult>().first()
            assertEquals("original", toolResult.textContent)
        }
    }

    @Nested
    inner class EventSequence {
        @Test
        fun `tool call produces correct event sequence`() = runTest {
            val (events, sink) = eventCollector()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "my_tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("final"))),
            )

            runAgentLoop(
                prompts = listOf(AgentMessage.User("go")),
                systemPrompt = "system",
                messages = emptyList(),
                chatId = "chat",
                config = AgentLoopConfig(
                    llmBridge = sequenceLlmBridge(*responses),
                    toolExecutor = staticToolExecutor("result"),
                ),
                emit = sink,
            )

            val eventTypes = events.map { it::class.simpleName }

            // Expected sequence:
            // AgentStart, TurnStart,
            // MessageRecord(user),
            // MessageStart(assistant+tool), MessageEnd(assistant+tool),
            // ToolExecutionStart, ToolExecutionEnd,
            // MessageRecord(tool result),
            // TurnEnd,
            // TurnStart,
            // MessageStart(final assistant), MessageEnd(final assistant),
            // TurnEnd,
            // AgentEnd

            assertTrue(eventTypes.contains("AgentStart"))
            assertTrue(eventTypes.contains("AgentEnd"))
            assertTrue(eventTypes.contains("ToolExecutionStart"))
            assertTrue(eventTypes.contains("ToolExecutionEnd"))
            assertTrue(eventTypes.contains("MessageRecord"))
            assertTrue(eventTypes.count { it == "TurnStart" } >= 2)
            assertTrue(eventTypes.count { it == "TurnEnd" } >= 2)
        }
    }
}
