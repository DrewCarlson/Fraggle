package fraggle.agent.loop

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for [KoogLlmBridge] against a live LM Studio instance.
 *
 * All tests are disabled unless the `LMS_API_URL` environment variable is set.
 */
@EnabledIfEnvironmentVariable(named = "LMS_API_URL", matches = ".+")
class KoogLlmBridgeTest {

    companion object {
        private val env get() = fraggle.testing.TestLlmEnvironment

        private val promptExecutor: MultiLLMPromptExecutor by lazy {
            val client = OpenAILLMClient(
                apiKey = env.apiKey ?: "lm-studio",
                settings = OpenAIClientSettings(baseUrl = env.apiUrl),
            )
            MultiLLMPromptExecutor(client)
        }

        /** Build a bridge with the resolved chat model. */
        suspend fun bridge(): KoogLlmBridge = KoogLlmBridge(
            promptExecutor = promptExecutor,
            model = LLModel(
                provider = LLMProvider.OpenAI,
                id = env.getChatModel(),
                capabilities = listOf(
                    LLMCapability.Temperature,
                    LLMCapability.Completion,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
            ),
        )
    }

    @Nested
    inner class BasicCalls {
        @Test
        fun `simple text response`() = runTest(timeout = 2.minutes) {
            val result = bridge().call(
                systemPrompt = "You are a helpful assistant. Be very brief.",
                messages = listOf(AgentMessage.User("What is 2+2? Reply with just the number.")),
                tools = emptyList(),
            )

            assertEquals(
                StopReason.STOP,
                result.stopReason,
                "Expected STOP but got ${result.stopReason}; errorMessage=${result.errorMessage}; content=${result.textContent}",
            )
            assertTrue(result.textContent.contains("4"), "Should contain '4', got: ${result.textContent}")
        }

        @Test
        fun `preserves conversation context`() = runTest(timeout = 2.minutes) {
            val messages = listOf(
                AgentMessage.User("My name is IntegrationTestUser."),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("Nice to meet you, IntegrationTestUser!"))),
                AgentMessage.User("What is my name? Reply with just the name."),
            )

            val result = bridge().call(
                systemPrompt = "Be very brief.",
                messages = messages,
                tools = emptyList(),
            )

            assertTrue(
                result.textContent.contains("IntegrationTestUser", ignoreCase = true),
                "Should remember name, got: ${result.textContent}",
            )
        }
    }

    @Nested
    inner class ConversionTests {
        @Test
        fun `handles empty messages list`() = runTest(timeout = 2.minutes) {
            val result = bridge().call(
                systemPrompt = "Say hello.",
                messages = emptyList(),
                tools = emptyList(),
            )

            assertEquals(StopReason.STOP, result.stopReason)
            assertTrue(result.textContent.isNotBlank())
        }

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
