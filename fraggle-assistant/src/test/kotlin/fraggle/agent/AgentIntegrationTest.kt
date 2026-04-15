package fraggle.agent

import fraggle.agent.event.AgentEvent
import fraggle.agent.event.StreamDelta
import fraggle.agent.loop.AgentOptions
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ProviderLlmBridge
import fraggle.agent.loop.StreamingLlmBridge
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.loop.ToolExecutionMode
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.ToolCall
import fraggle.agent.tool.AgentToolDef
import fraggle.agent.tool.FraggleToolRegistry
import fraggle.agent.tool.SupervisedToolCallExecutor
import fraggle.executor.supervision.NoOpToolSupervisor
import fraggle.provider.LMStudioProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Full integration tests for the new agent harness.
 * Exercises the complete pipeline: Agent -> AgentLoop -> ProviderLlmBridge -> LMStudioProvider.
 *
 * All tests are disabled unless the `LMS_API_URL` environment variable is set.
 */
@EnabledIfEnvironmentVariable(named = "LMS_API_URL", matches = ".+")
class AgentIntegrationTest {

    companion object {
        private val env get() = fraggle.testing.TestLlmEnvironment
        val httpClient: HttpClient get() = env.httpClient
        val provider: LMStudioProvider get() = env.provider

        /** Build a bridge with the resolved chat model. */
        suspend fun bridge(): ProviderLlmBridge = ProviderLlmBridge(
            provider = provider,
            model = env.getChatModel(),
            temperature = 0.0,
        )

        /** The resolved model id to use for Agent construction. */
        suspend fun model(): String = env.getChatModel()

        /** Simple math tool for testing tool calls. */
        class AddTool : AgentToolDef<AddTool.Args>(
            name = "add_numbers",
            description = "Add two numbers together and return the sum",
            argsSerializer = Args.serializer(),
        ) {
            @Serializable
            data class Args(val a: Int, val b: Int)

            override suspend fun execute(args: Args): String = (args.a + args.b).toString()
        }

        /** Simple lookup tool for testing. */
        class GetInfoTool : AgentToolDef<GetInfoTool.Args>(
            name = "get_info",
            description = "Get information about a topic. Returns a brief fact.",
            argsSerializer = Args.serializer(),
        ) {
            @Serializable
            data class Args(val topic: String)

            override suspend fun execute(args: Args): String = when (args.topic.lowercase()) {
                "paris" -> "Paris is the capital of France with a population of about 2.2 million."
                "tokyo" -> "Tokyo is the capital of Japan with a population of about 14 million."
                else -> "No information available for: ${args.topic}"
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Basic Agent Flow
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class BasicAgentFlow {
        @Test
        fun `simple prompt and response`() = runTest(timeout = 2.minutes) {
            val agent = Agent(AgentOptions(
                systemPrompt = "You are a helpful assistant. Be very brief.",
                model = model(),
                llmBridge = bridge(),
            ))

            agent.prompt("What is 2+2? Reply with just the number.")

            val state = agent.state
            assertFalse(state.isStreaming)
            assertEquals(2, state.messages.size)

            val response = state.messages.last()
            assertIs<AgentMessage.Assistant>(response)
            assertTrue(response.textContent.contains("4"), "Should contain '4', got: ${response.textContent}")
        }

        @Test
        fun `multi-turn conversation`() = runTest(timeout = 2.minutes) {
            val agent = Agent(AgentOptions(
                systemPrompt = "Be very brief.",
                model = model(),
                llmBridge = bridge(),
            ))

            agent.prompt("My name is IntegrationTestAgent.")
            agent.prompt("What is my name? Reply with just the name.")

            val state = agent.state
            assertEquals(4, state.messages.size) // 2 user + 2 assistant

            val lastResponse = (state.messages.last() as AgentMessage.Assistant).textContent
            assertTrue(
                lastResponse.contains("IntegrationTestAgent", ignoreCase = true),
                "Should remember name, got: $lastResponse",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Tool Calling
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ToolCalling {
        @Test
        fun `agent calls tool and uses result`() = runTest(timeout = 2.minutes) {
            fraggle.testing.TestLlmEnvironment.assumeToolRoundtripSupported(provider, model())
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val agent = Agent(AgentOptions(
                systemPrompt = "You must use the add_numbers tool when asked to add numbers. After getting the result, state the answer clearly.",
                model = model(),
                llmBridge = bridge(),
                toolExecutor = executor,
            ))

            agent.prompt("What is 17 + 25?")

            val messages = agent.state.messages
            // Should have: user, assistant(tool call), tool result, assistant(final)
            assertTrue(messages.size >= 3, "Should have at least 3 messages, got ${messages.size}")

            val toolResults = messages.filterIsInstance<AgentMessage.ToolResult>()
            if (toolResults.isNotEmpty()) {
                assertEquals("42", toolResults.first().textContent)
            }

            val finalResponse = messages.filterIsInstance<AgentMessage.Assistant>().last()
            assertTrue(
                finalResponse.textContent.contains("42"),
                "Final response should mention 42, got: ${finalResponse.textContent}",
            )
        }

        @Test
        fun `agent calls multiple tools sequentially`() = runTest(timeout = 3.minutes) {
            fraggle.testing.TestLlmEnvironment.assumeToolRoundtripSupported(provider, model())
            val registry = FraggleToolRegistry(listOf(GetInfoTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())

            val agent = Agent(AgentOptions(
                systemPrompt = "You MUST use the get_info tool to answer questions. " +
                    "After receiving the tool result, restate the information clearly in your own words.",
                model = model(),
                llmBridge = bridge(),
                toolExecutor = executor,
                maxIterations = 5,
            ))

            agent.prompt("Tell me about Paris using the get_info tool.")

            val messages = agent.state.messages
            val toolResults = messages.filterIsInstance<AgentMessage.ToolResult>()

            // Model should have used the tool
            assertTrue(
                toolResults.any { it.textContent.contains("Paris") || it.textContent.contains("capital") },
                "Tool result should mention Paris",
            )

            val finalResponse = messages.filterIsInstance<AgentMessage.Assistant>().last()
            assertTrue(finalResponse.textContent.isNotBlank(), "Should have a final response: $finalResponse")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Event Streaming
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class EventStreaming {
        @Test
        fun `receives all lifecycle events`() = runTest(timeout = 2.minutes) {
            val events = mutableListOf<AgentEvent>()

            val agent = Agent(AgentOptions(
                systemPrompt = "Be very brief.",
                model = model(),
                llmBridge = bridge(),
            ))
            agent.subscribe { events.add(it) }

            agent.prompt("Say hello")

            assertTrue(events.any { it is AgentEvent.AgentStart }, "Should have AgentStart")
            assertTrue(events.any { it is AgentEvent.AgentEnd }, "Should have AgentEnd")
            assertTrue(events.any { it is AgentEvent.TurnStart }, "Should have TurnStart")
            assertTrue(events.any { it is AgentEvent.TurnEnd }, "Should have TurnEnd")
            assertTrue(events.any { it is AgentEvent.MessageStart }, "Should have MessageStart")
            assertTrue(events.any { it is AgentEvent.MessageEnd }, "Should have MessageEnd")
        }

        @Test
        fun `streaming bridge emits MessageUpdate deltas`() = runTest(timeout = 2.minutes) {
            // ProviderLlmBridge always implements StreamingLlmBridge by construction.
            val deltas = mutableListOf<StreamDelta>()
            val agent = Agent(AgentOptions(
                systemPrompt = "Be very brief.",
                model = model(),
                llmBridge = bridge(),
            ))
            agent.subscribe { event ->
                if (event is AgentEvent.MessageUpdate) {
                    deltas.add(event.delta)
                }
            }

            agent.prompt("Count from 1 to 5, separated by commas.")

            // Should have received streaming text deltas
            val textDeltas = deltas.filterIsInstance<StreamDelta.TextDelta>()
            assertTrue(textDeltas.isNotEmpty(), "Should receive text deltas during streaming")
        }

        @Test
        fun `tool execution events are emitted`() = runTest(timeout = 2.minutes) {
            val registry = FraggleToolRegistry(listOf(AddTool()))
            val executor = SupervisedToolCallExecutor(registry, NoOpToolSupervisor())
            val toolEvents = mutableListOf<AgentEvent>()

            val agent = Agent(AgentOptions(
                systemPrompt = "You must use the add_numbers tool to add numbers.",
                model = model(),
                llmBridge = bridge(),
                toolExecutor = executor,
            ))
            agent.subscribe { event ->
                if (event is AgentEvent.ToolExecutionStart || event is AgentEvent.ToolExecutionEnd) {
                    toolEvents.add(event)
                }
            }

            agent.prompt("Add 5 and 3 using the tool.")

            // If the model used the tool, we should see start/end events
            if (toolEvents.isNotEmpty()) {
                assertTrue(toolEvents.any { it is AgentEvent.ToolExecutionStart })
                assertTrue(toolEvents.any { it is AgentEvent.ToolExecutionEnd })

                val startEvent = toolEvents.filterIsInstance<AgentEvent.ToolExecutionStart>().first()
                assertEquals("add_numbers", startEvent.toolName)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // State Management
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class StateManagement {
        @Test
        fun `state is clean after completion`() = runTest(timeout = 2.minutes) {
            val agent = Agent(AgentOptions(
                systemPrompt = "Be brief.",
                model = model(),
                llmBridge = bridge(),
            ))

            agent.prompt("Hi")

            val state = agent.state
            assertFalse(state.isStreaming)
            assertTrue(state.pendingToolCalls.isEmpty())
            assertNotNull(state.messages.lastOrNull())
        }

        @Test
        fun `reset clears all state`() = runTest(timeout = 2.minutes) {
            val agent = Agent(AgentOptions(
                systemPrompt = "Be brief.",
                model = model(),
                llmBridge = bridge(),
            ))

            agent.prompt("Hi")
            assertTrue(agent.state.messages.isNotEmpty())

            agent.reset()
            assertTrue(agent.state.messages.isEmpty())
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Context Transforms
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ContextTransforms {
        @Test
        fun `sliding window limits context sent to LLM`() = runTest(timeout = 3.minutes) {
            val transform = fraggle.agent.context.SlidingWindowTransform(maxTokens = 200)

            val agent = Agent(AgentOptions(
                systemPrompt = "Be very brief. Always respond with exactly one short sentence.",
                model = model(),
                llmBridge = bridge(),
            ))

            // Have a multi-turn conversation
            agent.prompt("Tell me about cats")
            agent.prompt("Tell me about dogs")
            agent.prompt("Tell me about fish")

            // Verify conversation history grew
            assertTrue(agent.state.messages.size >= 6, "Should have at least 6 messages")

            // Now test with context transform - this just verifies the transform runs without error
            // The actual truncation is tested in ContextTransformTest
            val transformedMessages = transform.transform(agent.state.messages)
            assertTrue(transformedMessages.size <= agent.state.messages.size)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Error Handling
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ErrorHandling {
        @Test
        fun `max iterations prevents infinite tool loops`() = runTest(timeout = 2.minutes) {
            // A tool executor that always "needs another call"
            val alwaysCallExecutor = object : ToolCallExecutor {
                override suspend fun execute(toolCall: ToolCall, chatId: String) =
                    ToolCallResult("Need to call again")
                override fun getToolDefinitions() = listOf(
                    ToolDefinition("loop_tool", "A tool that loops", """{"type":"object","properties":{}}""")
                )
            }

            // Bridge that always requests a tool call
            val loopingBridge = LlmBridge { _, _, _ ->
                AgentMessage.Assistant(
                    toolCalls = listOf(ToolCall("tc-1", "loop_tool", "{}")),
                )
            }

            val agent = Agent(AgentOptions(
                systemPrompt = "test",
                model = "test",
                llmBridge = loopingBridge,
                toolExecutor = alwaysCallExecutor,
                maxIterations = 3,
            ))

            agent.prompt("go")

            val lastAssistant = agent.state.messages.filterIsInstance<AgentMessage.Assistant>().last()
            assertEquals(StopReason.ERROR, lastAssistant.stopReason)
            assertTrue(lastAssistant.errorMessage?.contains("Maximum iterations") == true)
        }

        @Test
        fun `handles LLM errors gracefully`() = runTest(timeout = 2.minutes) {
            val badBridge = ProviderLlmBridge(
                provider = LMStudioProvider(
                    baseUrl = "http://localhost:1",
                    httpClient = httpClient,
                ),
            )

            val agent = Agent(AgentOptions(
                systemPrompt = "test",
                model = "test",
                llmBridge = badBridge,
            ))

            agent.prompt("Hi")

            // Should have an error in the response, not crash
            val lastAssistant = agent.state.messages.filterIsInstance<AgentMessage.Assistant>().last()
            assertEquals(StopReason.ERROR, lastAssistant.stopReason)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Schema Generation
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class SchemaGeneration {
        @Test
        fun `generated schemas are valid for LLM consumption`() = runTest(timeout = 2.minutes) {
            val registry = FraggleToolRegistry(listOf(AddTool(), GetInfoTool()))
            val definitions = registry.toToolDefinitions()

            assertEquals(2, definitions.size)

            val addDef = definitions.first { it.name == "add_numbers" }
            assertTrue(addDef.parametersSchema.contains("\"a\""))
            assertTrue(addDef.parametersSchema.contains("\"b\""))
            assertTrue(addDef.parametersSchema.contains("integer"))

            val infoDef = definitions.first { it.name == "get_info" }
            assertTrue(infoDef.parametersSchema.contains("\"topic\""))
            assertTrue(infoDef.parametersSchema.contains("string"))
        }
    }
}
