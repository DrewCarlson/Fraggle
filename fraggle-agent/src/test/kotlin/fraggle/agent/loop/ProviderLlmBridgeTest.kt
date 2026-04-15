package fraggle.agent.loop

import fraggle.agent.event.StreamDelta
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.provider.LMStudioProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for [ProviderLlmBridge] against a live LM Studio instance.
 *
 * All tests are disabled unless the `LMS_API_URL` environment variable is set.
 */
@EnabledIfEnvironmentVariable(named = "LMS_API_URL", matches = ".+")
class ProviderLlmBridgeTest {

    companion object {
        private val env get() = fraggle.testing.TestLlmEnvironment
        val provider: LMStudioProvider get() = env.provider

        /** Build a bridge with the resolved chat model. */
        suspend fun bridge(): ProviderLlmBridge = ProviderLlmBridge(
            provider = provider,
            model = env.getChatModel(),
            temperature = 0.0,
        )
    }

    @Nested
    inner class NonStreamingCalls {
        @Test
        fun `basic text response`() = runTest(timeout = 2.minutes) {
            val result = bridge().call(
                systemPrompt = "You are a helpful assistant. Be very brief.",
                messages = listOf(AgentMessage.User("What is 2+2? Reply with just the number.")),
                tools = emptyList(),
            )

            assertEquals(StopReason.STOP, result.stopReason)
            assertTrue(result.textContent.contains("4"), "Should contain '4', got: ${result.textContent}")
        }

        @Test
        fun `multi-turn conversation context`() = runTest(timeout = 2.minutes) {
            val messages = listOf(
                AgentMessage.User("My name is BridgeTestUser."),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("Hello BridgeTestUser!"))),
                AgentMessage.User("What is my name? Reply with just the name."),
            )

            val result = bridge().call(
                systemPrompt = "Be very brief.",
                messages = messages,
                tools = emptyList(),
            )

            assertTrue(
                result.textContent.contains("BridgeTestUser", ignoreCase = true),
                "Should remember name, got: ${result.textContent}",
            )
        }

        @Test
        fun `returns usage statistics`() = runTest(timeout = 2.minutes) {
            val result = bridge().call(
                systemPrompt = "Be brief.",
                messages = listOf(AgentMessage.User("Say hi")),
                tools = emptyList(),
            )

            val usage = result.usage
            assertNotNull(usage, "Should have usage stats")
            assertTrue(usage.promptTokens > 0)
            assertTrue(usage.totalTokens > 0)
        }
    }

    @Nested
    inner class StreamingCalls {
        @Test
        fun `streams text response with deltas`() = runTest(timeout = 2.minutes) {
            val deltas = mutableListOf<StreamDelta>()
            val partials = mutableListOf<AgentMessage.Assistant>()

            val result = bridge().callStreaming(
                systemPrompt = "Be very brief.",
                messages = listOf(AgentMessage.User("Count from 1 to 5, separated by commas.")),
                tools = emptyList(),
            ) { delta, partial ->
                deltas.add(delta)
                partials.add(partial)
            }

            // Should have received multiple text deltas
            val textDeltas = deltas.filterIsInstance<StreamDelta.TextDelta>()
            assertTrue(textDeltas.isNotEmpty(), "Should receive text deltas")

            // Partial messages should grow progressively
            if (partials.size >= 2) {
                assertTrue(
                    partials.last().textContent.length >= partials.first().textContent.length,
                    "Partial messages should grow",
                )
            }

            // Final result should have the complete text
            assertTrue(result.textContent.isNotBlank(), "Final text should not be blank")
            assertEquals(StopReason.STOP, result.stopReason)
        }

        @Test
        fun `streaming result matches non-streaming`() = runTest(timeout = 2.minutes) {
            val systemPrompt = "Always respond with exactly: PONG"
            val messages = listOf(AgentMessage.User("PING"))

            val nonStreamResult = bridge().call(systemPrompt, messages, emptyList())

            val streamResult = bridge().callStreaming(systemPrompt, messages, emptyList()) { _, _ -> }

            // Both should produce similar content
            assertTrue(
                nonStreamResult.textContent.trim() == streamResult.textContent.trim(),
                "Non-streaming ('${nonStreamResult.textContent.trim()}') should match " +
                    "streaming ('${streamResult.textContent.trim()}')",
            )
        }
    }

    @Nested
    inner class ToolCalling {
        @Test
        fun `non-streaming tool call`() = runTest(timeout = 2.minutes) {
            val tools = listOf(
                ToolDefinition(
                    name = "add_numbers",
                    description = "Add two numbers together",
                    parametersSchema = """{
                        "type": "object",
                        "properties": {
                            "a": {"type": "number"},
                            "b": {"type": "number"}
                        },
                        "required": ["a", "b"]
                    }""",
                ),
            )

            val result = bridge().call(
                systemPrompt = "You must use the add_numbers tool when asked to add.",
                messages = listOf(AgentMessage.User("Add 3 and 7")),
                tools = tools,
            )

            // Model should either use the tool or respond with text
            if (result.toolCalls.isNotEmpty()) {
                val toolCall = result.toolCalls.first()
                assertEquals("add_numbers", toolCall.name)
                assertTrue(toolCall.id.isNotBlank())
                assertTrue(toolCall.arguments.isNotBlank())
            }
        }

        @Test
        fun `tool result roundtrip`() = runTest(timeout = 2.minutes) {
            val tools = listOf(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get weather for a location",
                    parametersSchema = """{
                        "type": "object",
                        "properties": {
                            "location": {"type": "string"}
                        },
                        "required": ["location"]
                    }""",
                ),
            )

            // First call — get tool call
            val firstResult = bridge().call(
                systemPrompt = "You must use the get_weather tool to answer weather questions.",
                messages = listOf(AgentMessage.User("What's the weather in Paris?")),
                tools = tools,
            )

            if (firstResult.toolCalls.isEmpty()) return@runTest // Model didn't use tools

            val toolCall = firstResult.toolCalls.first()

            // Second call — provide tool result
            val secondResult = bridge().call(
                systemPrompt = "You must use the get_weather tool to answer weather questions.",
                messages = listOf(
                    AgentMessage.User("What's the weather in Paris?"),
                    firstResult,
                    AgentMessage.ToolResult(
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        text = """{"temperature": 22, "condition": "sunny"}""",
                    ),
                ),
                tools = emptyList(),
            )

            assertTrue(secondResult.textContent.isNotBlank(), "Should have final response")
        }
    }

    @Nested
    inner class MessageConversion {
        @Test
        fun `platform messages are skipped`() = runTest(timeout = 2.minutes) {
            val messages = listOf(
                AgentMessage.Platform("signal", mapOf("ignored" to true)),
                AgentMessage.User("Say PONG"),
            )

            val result = bridge().call(
                systemPrompt = "Always respond with exactly: PONG",
                messages = messages,
                tools = emptyList(),
            )

            assertTrue(result.textContent.contains("PONG", ignoreCase = true))
        }
    }
}
