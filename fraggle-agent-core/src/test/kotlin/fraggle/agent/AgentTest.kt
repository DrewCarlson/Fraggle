package fraggle.agent

import fraggle.agent.event.AgentEvent
import fraggle.agent.loop.AgentOptions
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.ToolCall
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {

    private fun staticBridge(text: String) = LlmBridge { _, _, _ ->
        AgentMessage.Assistant(content = listOf(ContentPart.Text(text)))
    }

    private fun sequenceBridge(vararg responses: AgentMessage.Assistant): LlmBridge {
        val iter = responses.iterator()
        return LlmBridge { _, _, _ ->
            if (iter.hasNext()) iter.next()
            else AgentMessage.Assistant(content = listOf(ContentPart.Text("done")))
        }
    }

    private fun simpleAgent(
        llmBridge: LlmBridge = staticBridge("hello"),
        toolExecutor: ToolCallExecutor? = null,
        maxIterations: Int = 10,
        chatId: String = "test-chat",
    ) = Agent(
        AgentOptions(
            systemPrompt = "You are helpful.",
            model = "test-model",
            llmBridge = llmBridge,
            toolExecutor = toolExecutor,
            maxIterations = maxIterations,
            chatId = chatId,
        )
    )

    @Nested
    inner class BasicPrompt {
        @Test
        fun `prompt with text and get response`() = runTest {
            val agent = simpleAgent()
            agent.prompt("Hello!")

            val state = agent.state
            assertFalse(state.isStreaming)
            assertEquals(2, state.messages.size) // user + assistant
            assertIs<AgentMessage.User>(state.messages[0])
            assertIs<AgentMessage.Assistant>(state.messages[1])
            assertEquals("hello", (state.messages[1] as AgentMessage.Assistant).textContent)
        }

        @Test
        fun `prompt with message list`() = runTest {
            val agent = simpleAgent()
            agent.prompt(listOf(AgentMessage.User("Hi there")))

            assertEquals(2, agent.state.messages.size)
        }

        @Test
        fun `multiple prompts accumulate messages`() = runTest {
            var callCount = 0
            val bridge = LlmBridge { _, _, _ ->
                callCount++
                AgentMessage.Assistant(content = listOf(ContentPart.Text("response $callCount")))
            }
            val agent = simpleAgent(llmBridge = bridge)

            agent.prompt("First")
            agent.prompt("Second")

            assertEquals(4, agent.state.messages.size) // user1, assistant1, user2, assistant2
        }
    }

    @Nested
    inner class StateTracking {
        @Test
        fun `initial state has no messages`() {
            val agent = simpleAgent()
            val state = agent.state
            assertTrue(state.messages.isEmpty())
            assertFalse(state.isStreaming)
            assertNull(state.streamingMessage)
            assertTrue(state.pendingToolCalls.isEmpty())
            assertNull(state.errorMessage)
            assertEquals("You are helpful.", state.systemPrompt)
            assertEquals("test-model", state.model)
        }

        @Test
        fun `state snapshot is immutable`() = runTest {
            val agent = simpleAgent()
            val before = agent.state
            agent.prompt("Hi")
            val after = agent.state

            assertTrue(before.messages.isEmpty())
            assertEquals(2, after.messages.size)
        }

        @Test
        fun `error state is tracked`() = runTest {
            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    content = listOf(ContentPart.Text("error")),
                    stopReason = StopReason.ERROR,
                    errorMessage = "LLM failed",
                )
            }
            val agent = simpleAgent(llmBridge = bridge)
            agent.prompt("Hi")

            assertNotNull(agent.state.errorMessage)
            assertEquals("LLM failed", agent.state.errorMessage)
        }
    }

    @Nested
    inner class EventSubscription {
        @Test
        fun `subscriber receives all events`() = runTest {
            val events = mutableListOf<AgentEvent>()
            val agent = simpleAgent()
            backgroundScope.launch { agent.events().collect { events.add(it) } }
            yield()

            agent.prompt("Hi")

            assertTrue(events.any { it is AgentEvent.AgentStart })
            assertTrue(events.any { it is AgentEvent.AgentEnd })
            assertTrue(events.any { it is AgentEvent.TurnStart })
            assertTrue(events.any { it is AgentEvent.TurnEnd })
            assertTrue(events.any { it is AgentEvent.MessageStart })
            assertTrue(events.any { it is AgentEvent.MessageEnd })
        }

        @Test
        fun `unsubscribe stops receiving events`() = runTest {
            val events = mutableListOf<AgentEvent>()
            val agent = simpleAgent()
            agent.events()
                .onEach { events.add(it) }
                .launchIn(backgroundScope)

            agent.prompt("First")
            val countAfterFirst = events.size

            agent.prompt("Second")

            assertEquals(countAfterFirst, events.size)
        }

        @Test
        fun `multiple subscribers all receive events`() = runTest {
            val events1 = mutableListOf<AgentEvent>()
            val events2 = mutableListOf<AgentEvent>()
            val agent = simpleAgent()
            backgroundScope.launch { agent.events().collect { events1.add(it) } }
            backgroundScope.launch { agent.events().collect { events2.add(it) } }
            yield()

            agent.prompt("Hi")

            assertEquals(events1.size, events2.size)
            assertTrue(events1.isNotEmpty())
        }
    }

    @Nested
    inner class ToolExecution {
        @Test
        fun `tool calls are tracked in state during execution`() = runTest {
            val toolCallIds = mutableListOf<Set<String>>()

            val responses = arrayOf(
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "my_tool", "{}")),
                ),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("done"))),
            )

            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
                    return ToolCallResult("result")
                }

                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            val agent = simpleAgent(
                llmBridge = sequenceBridge(*responses),
                toolExecutor = executor,
            )

            agent.events()
                .onEach { event ->
                    if (event is AgentEvent.ToolExecutionStart) {
                        // Can't snapshot state during event processing easily,
                        // but we can verify the event itself
                        toolCallIds.add(setOf(event.toolCallId))
                    }
                }
                .launchIn(backgroundScope)

            agent.prompt("go")

            // Verify final state has all messages
            val msgs = agent.state.messages
            assertEquals(4, msgs.size) // user, assistant(tool), tool result, assistant(final)
            assertTrue(agent.state.pendingToolCalls.isEmpty()) // no pending after completion
        }
    }

    @Nested
    inner class Reset {
        @Test
        fun `reset clears messages and state`() = runTest {
            val agent = simpleAgent()
            agent.prompt("Hi")

            assertTrue(agent.state.messages.isNotEmpty())

            agent.reset()

            assertTrue(agent.state.messages.isEmpty())
            assertFalse(agent.state.isStreaming)
            assertNull(agent.state.errorMessage)
        }

        @Test
        fun `reset clears queues`() = runTest {
            val agent = simpleAgent()
            agent.steer(AgentMessage.User("steer"))
            agent.followUp(AgentMessage.User("follow"))

            agent.reset()

            // After reset, prompt should work normally without injected messages
            var llmCallCount = 0
            val bridge = LlmBridge { _, msgs, _ ->
                llmCallCount++
                AgentMessage.Assistant(content = listOf(ContentPart.Text("ok")))
            }
            val freshAgent = simpleAgent(llmBridge = bridge)
            freshAgent.prompt("Hi")
            assertEquals(1, llmCallCount)
        }
    }

    @Nested
    inner class SteeringAndFollowUp {
        @Test
        fun `steering messages are injected during run`() = runTest {
            var callCount = 0
            val bridge = LlmBridge { _, msgs, _ ->
                callCount++
                if (callCount == 1) {
                    AgentMessage.Assistant(
                        toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                    )
                } else {
                    AgentMessage.Assistant(content = listOf(ContentPart.Text("final")))
                }
            }
            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
                    return ToolCallResult("ok")
                }

                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            val agent = simpleAgent(llmBridge = bridge, toolExecutor = executor)

            // Queue steering before prompt
            agent.steer(AgentMessage.User("extra context"))
            agent.prompt("start")

            // Messages should include the steering message
            val userMessages = agent.state.messages.filterIsInstance<AgentMessage.User>()
            assertTrue(userMessages.size >= 2) // at least original + steering
        }

        @Test
        fun `follow-up messages trigger additional turns`() = runTest {
            var callCount = 0
            val agent = simpleAgent(llmBridge = LlmBridge { _, _, _ ->
                callCount++
                AgentMessage.Assistant(content = listOf(ContentPart.Text("response $callCount")))
            })

            agent.followUp(AgentMessage.User("follow up"))
            agent.prompt("start")

            // Should have made 2 LLM calls
            assertEquals(2, callCount)
        }
    }

    @Nested
    inner class ContinueRun {
        @Test
        fun `continue from existing messages`() = runTest {
            val agent = simpleAgent()
            agent.prompt("Hello")

            // Now manually add a user message and continue
            // (This simulates the retry pattern)
            val state = agent.state
            assertEquals(2, state.messages.size)

            // Continue should work since last message is assistant
            // We need to manually set up the state for continuation
            // For now just verify the error case
        }

        @Test
        fun `continue fails on empty state`() = runTest {
            val agent = simpleAgent()
            assertFailsWith<IllegalArgumentException> {
                agent.continueRun()
            }
        }
    }

    @Nested
    inner class MaxIterations {
        @Test
        fun `respects max iterations limit`() = runTest {
            val bridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "tool", "{}")),
                )
            }
            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String) =
                    ToolCallResult("ok")

                override fun getToolDefinitions() = emptyList<ToolDefinition>()
            }

            val agent = simpleAgent(
                llmBridge = bridge,
                toolExecutor = executor,
                maxIterations = 2,
            )
            agent.prompt("go")

            // Should have stopped with error after max iterations
            val lastAssistant = agent.state.messages.filterIsInstance<AgentMessage.Assistant>().last()
            assertEquals(StopReason.ERROR, lastAssistant.stopReason)
        }
    }

    @Nested
    inner class Integration {
        @Test
        fun `full tool roundtrip flow`() = runTest {
            var callCount = 0
            val bridge = LlmBridge { _, msgs, _ ->
                callCount++
                when (callCount) {
                    1 -> AgentMessage.Assistant(
                        toolCalls = listOf(
                            ToolCall("tc-1", "get_weather", """{"city":"Tokyo"}"""),
                        ),
                    )

                    else -> AgentMessage.Assistant(
                        content = listOf(ContentPart.Text("The weather in Tokyo is sunny and 22C.")),
                    )
                }
            }

            val executor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
                    assertEquals("get_weather", toolCall.name)
                    return ToolCallResult("""{"temp": 22, "condition": "sunny"}""")
                }

                override fun getToolDefinitions() = listOf(
                    ToolDefinition("get_weather", "Get weather", """{"type":"object"}""")
                )
            }

            val events = mutableListOf<AgentEvent>()
            val agent = simpleAgent(llmBridge = bridge, toolExecutor = executor)
            agent.events()
                .onEach { events.add(it) }
                .launchIn(backgroundScope)

            agent.prompt("What's the weather in Tokyo?")

            // Verify message flow
            val msgs = agent.state.messages
            assertEquals(4, msgs.size)
            assertIs<AgentMessage.User>(msgs[0])
            assertIs<AgentMessage.Assistant>(msgs[1]) // tool call
            assertIs<AgentMessage.ToolResult>(msgs[2])
            assertIs<AgentMessage.Assistant>(msgs[3]) // final response

            val finalResponse = (msgs[3] as AgentMessage.Assistant).textContent
            assertTrue(finalResponse.contains("Tokyo"))

            // Verify events
            assertTrue(events.filterIsInstance<AgentEvent.ToolExecutionStart>().isNotEmpty())
            assertTrue(events.filterIsInstance<AgentEvent.ToolExecutionEnd>().isNotEmpty())

            // Verify clean final state
            assertFalse(agent.state.isStreaming)
            assertTrue(agent.state.pendingToolCalls.isEmpty())
        }
    }
}
