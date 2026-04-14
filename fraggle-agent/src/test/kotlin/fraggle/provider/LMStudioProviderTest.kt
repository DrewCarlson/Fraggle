package fraggle.provider

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [LMStudioProvider] against a live LM Studio instance.
 *
 * All tests are disabled unless the `LMS_API_URL` environment variable is set
 * to a base URL.
 *
 * No model load/unload/download operations are tested.
 */
@EnabledIfEnvironmentVariable(named = "LMS_API_URL", matches = ".+")
class LMStudioProviderTest {

    companion object {
        private val apiUrl: String = System.getenv("LMS_API_URL") ?: ""
        private val apiKey: String? = System.getenv("LMS_API_KEY")

        /** Resolved once from the live server. */
        private var resolvedModel: String = ""

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }

        val httpClient: HttpClient by lazy {
            HttpClient(CIO) {
                install(ContentNegotiation) { json(json) }
                install(HttpTimeout) {
                    requestTimeoutMillis = 5.minutes.inWholeMilliseconds
                    connectTimeoutMillis = 10.seconds.inWholeMilliseconds
                }
                defaultRequest {
                    contentType(ContentType.Application.Json)
                }
                Logging {
                    level = LogLevel.ALL
                    logger = Logger.SIMPLE
                }
            }
        }

        val provider: LMStudioProvider by lazy {
            LMStudioProvider(
                baseUrl = apiUrl,
                httpClient = httpClient,
                apiKey = apiKey,
            )
        }

        /**
         * Get a model identifier to use for tests. Queries the server once
         * and caches the result. Prefers a loaded LLM model.
         */
        suspend fun getModel(): String {
            if (resolvedModel.isNotEmpty()) return resolvedModel

            // Try native endpoint first for richer info
            try {
                val nativeModels = provider.nativeListModels()
                val loadedLlm = nativeModels.models.firstOrNull { model ->
                    model.type == "llm" && model.loadedInstances.isNotEmpty()
                }
                if (loadedLlm != null) {
                    resolvedModel = loadedLlm.key
                    return resolvedModel
                }
                // Fall back to any LLM
                val anyLlm = nativeModels.models.firstOrNull { it.type == "llm" }
                if (anyLlm != null) {
                    resolvedModel = anyLlm.key
                    return resolvedModel
                }
            } catch (_: Exception) {
                // Native endpoint may not be available, fall through
            }

            // Fall back to OpenAI-compat models list
            val models = provider.listModels()
            assertTrue(models.isNotEmpty(), "No models available on the LM Studio server")
            resolvedModel = models.first().id
            return resolvedModel
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible: List Models
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ListModels {
        @Test
        fun `lists available models`() = runTest {
            val models = provider.listModels()
            assertTrue(models.isNotEmpty(), "Expected at least one model")
            models.forEach { model ->
                assertTrue(model.id.isNotBlank(), "Model id should not be blank")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible: Chat Completions
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ChatCompletions {
        @Test
        fun `basic chat completion`() = runTest {
            val model = getModel()
            val response = provider.chat(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You are a helpful assistant. Be very brief."),
                    Message.user("What is 2+2? Reply with just the number."),
                ),
                temperature = 0.0,
                maxTokens = 32,
            ))

            assertEquals(1, response.choices.size)
            val content = response.choices.first().message.content
            assertNotNull(content, "Response content should not be null")
            assertTrue(content.contains("4"), "Response should contain '4', got: $content")
            assertTrue(response.id.isNotBlank(), "Response ID should not be blank")
        }

        @Test
        fun `chat with multiple messages`() = runTest {
            val model = getModel()
            val response = provider.chat(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You are a helpful assistant. Be very brief."),
                    Message.user("My name is TestUser."),
                    Message.assistant("Nice to meet you, TestUser!"),
                    Message.user("What is my name? Reply with just the name."),
                ),
                temperature = 0.0,
                maxTokens = 32,
            ))

            val content = response.choices.first().message.content
            assertNotNull(content)
            assertTrue(
                content.contains("TestUser", ignoreCase = true),
                "Response should contain 'TestUser', got: $content",
            )
        }

        @Test
        fun `chat returns usage statistics`() = runTest {
            val model = getModel()
            val response = provider.chat(ChatRequest(
                model = model,
                messages = listOf(Message.user("Say hi")),
                maxTokens = 16,
            ))

            val usage = response.usage
            assertNotNull(usage, "Usage should be present")
            assertTrue(usage.promptTokens > 0, "Prompt tokens should be > 0")
            assertTrue(usage.completionTokens > 0, "Completion tokens should be > 0")
            assertTrue(usage.totalTokens > 0, "Total tokens should be > 0")
            assertEquals(
                usage.promptTokens + usage.completionTokens,
                usage.totalTokens,
                "Total should equal prompt + completion",
            )
        }

        @Test
        fun `chat with tool calling`() = runTest {
            val model = getModel()

            val weatherTool = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "get_weather")
                    put("description", "Get the current weather for a location")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("location") {
                                put("type", "string")
                                put("description", "City name")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("location")) }
                        put("additionalProperties", false)
                    }
                }
            }

            val response = provider.chat(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You are a helpful assistant. Use the get_weather tool when asked about weather."),
                    Message.user("What's the weather in Tokyo?"),
                ),
                tools = listOf(weatherTool),
                toolChoice = ToolChoice.Auto,
                temperature = 0.0,
                maxTokens = 256,
            ))

            val message = response.choices.first().message
            // Model should either call the tool or respond with text
            if (message.toolCalls != null && message.toolCalls.isNotEmpty()) {
                val toolCall = message.toolCalls.first()
                assertEquals("get_weather", toolCall.function.name)
                assertTrue(toolCall.id.isNotBlank(), "Tool call ID should not be blank")
                assertTrue(
                    toolCall.function.arguments.contains("Tokyo", ignoreCase = true) ||
                        toolCall.function.arguments.contains("tokyo", ignoreCase = true),
                    "Tool call arguments should mention Tokyo, got: ${toolCall.function.arguments}",
                )
                assertEquals(
                    "tool_calls",
                    response.choices.first().finishReason,
                    "Finish reason should be 'tool_calls'",
                )
            }
            // If the model responds with text instead of a tool call, that's also valid behavior
        }

        @Test
        fun `chat with tool result roundtrip`() = runTest {
            val model = getModel()

            val weatherTool = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "get_weather")
                    put("description", "Get the current weather for a location")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("location") {
                                put("type", "string")
                                put("description", "City name")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("location")) }
                    }
                }
            }

            // First call — expect tool call
            val firstResponse = provider.chat(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You must use the get_weather tool to answer weather questions."),
                    Message.user("What's the weather in Paris?"),
                ),
                tools = listOf(weatherTool),
                toolChoice = ToolChoice.Required,
                temperature = 0.0,
                maxTokens = 256,
            ))

            val toolCalls = firstResponse.choices.first().message.toolCalls
            assertNotNull(toolCalls, "Expected tool calls with tool_choice=required")
            assertTrue(toolCalls.isNotEmpty(), "Expected at least one tool call")

            val toolCall = toolCalls.first()

            // Second call — provide tool result and get final answer
            val secondResponse = provider.chat(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You must use the get_weather tool to answer weather questions."),
                    Message.user("What's the weather in Paris?"),
                    Message.assistant(toolCalls),
                    Message.tool(toolCall.id, """{"temperature": 22, "condition": "sunny"}"""),
                ),
                temperature = 0.0,
                maxTokens = 128,
            ))

            val finalContent = secondResponse.choices.first().message.content
            assertNotNull(finalContent, "Final response should have content")
            assertTrue(finalContent.isNotBlank(), "Final response should not be blank")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible: Streaming Chat
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class StreamingChat {
        @Test
        fun `streams text response`() = runTest {
            val model = getModel()
            val events = provider.chatStream(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("Be very brief."),
                    Message.user("Count from 1 to 5, separated by commas."),
                ),
                temperature = 0.0,
                maxTokens = 64,
            )).toList()

            assertTrue(events.isNotEmpty(), "Should receive streaming events")

            val textDeltas = events.filterIsInstance<ChatStreamEvent.TextDelta>()
            assertTrue(textDeltas.isNotEmpty(), "Should receive text deltas")

            val fullText = textDeltas.joinToString("") { it.text }
            assertTrue(fullText.isNotBlank(), "Accumulated text should not be blank")

            // Should end with Done
            assertTrue(
                events.any { it is ChatStreamEvent.Done },
                "Stream should end with Done event",
            )
        }

        @Test
        fun `streams finish reason`() = runTest {
            val model = getModel()
            val events = provider.chatStream(ChatRequest(
                model = model,
                messages = listOf(Message.user("Say hello")),
                maxTokens = 16,
            )).toList()

            val finishReasons = events.filterIsInstance<ChatStreamEvent.FinishReason>()
            assertTrue(finishReasons.isNotEmpty(), "Should receive a finish reason event")
            assertTrue(
                finishReasons.first().reason in listOf("stop", "length"),
                "Finish reason should be 'stop' or 'length', got: ${finishReasons.first().reason}",
            )
        }

        @Test
        fun `streams usage when requested`() = runTest {
            val model = getModel()
            val events = provider.chatStream(ChatRequest(
                model = model,
                messages = listOf(Message.user("Say hi")),
                maxTokens = 16,
            )).toList()

            val usageEvents = events.filterIsInstance<ChatStreamEvent.UsageInfo>()
            assertTrue(usageEvents.isNotEmpty(), "Should receive usage info in stream")
            val usage = usageEvents.first()
            assertTrue(usage.promptTokens > 0, "Prompt tokens should be > 0")
            assertTrue(usage.totalTokens > 0, "Total tokens should be > 0")
        }

        @Test
        fun `streaming tool calls`() = runTest {
            val model = getModel()

            val tool = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "add_numbers")
                    put("description", "Add two numbers together")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("a") { put("type", "number") }
                            putJsonObject("b") { put("type", "number") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("a"))
                            add(JsonPrimitive("b"))
                        }
                    }
                }
            }

            val events = provider.chatStream(ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You must use the add_numbers tool."),
                    Message.user("Add 3 and 7"),
                ),
                tools = listOf(tool),
                toolChoice = ToolChoice.Required,
                temperature = 0.0,
                maxTokens = 128,
            )).toList()

            val toolCallDeltas = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
            // Tool calls should be streamed when model supports it
            if (toolCallDeltas.isNotEmpty()) {
                // At least one delta should have the function name
                val nameDeltas = toolCallDeltas.filter { it.functionName != null }
                assertTrue(nameDeltas.isNotEmpty(), "Should have a tool call delta with function name")
                assertEquals("add_numbers", nameDeltas.first().functionName)
            }
            // Either way, finish reason should be tool_calls
            val finishReasons = events.filterIsInstance<ChatStreamEvent.FinishReason>()
            if (finishReasons.isNotEmpty()) {
                assertEquals("tool_calls", finishReasons.first().reason)
            }
        }

        @Test
        fun `streaming text matches non-streaming result`() = runTest {
            val model = getModel()
            val request = ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("Always respond with exactly: PONG"),
                    Message.user("PING"),
                ),
                temperature = 0.0,
                maxTokens = 16,
            )

            val nonStreamResponse = provider.chat(request)
            val nonStreamText = nonStreamResponse.choices.first().message.content

            val streamEvents = provider.chatStream(request).toList()
            val streamText = streamEvents
                .filterIsInstance<ChatStreamEvent.TextDelta>()
                .joinToString("") { it.text }

            // Both should contain the same core content (may differ in whitespace)
            assertNotNull(nonStreamText)
            assertTrue(
                nonStreamText.trim() == streamText.trim(),
                "Non-streaming ('${nonStreamText.trim()}') should match streaming ('${streamText.trim()}')",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible: Text Completions (Legacy)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class TextCompletions {
        @Test
        fun `basic text completion`() = runTest {
            val model = getModel()
            val response = provider.complete(CompletionRequest(
                model = model,
                prompt = "The capital of France is",
                maxTokens = 16,
                temperature = 0.0,
            ))

            assertTrue(response.choices.isNotEmpty(), "Should have at least one choice")
            val text = response.choices.first().text
            assertTrue(text.isNotBlank(), "Completion text should not be blank")
            assertTrue(response.id.isNotBlank(), "Response ID should not be blank")
        }

        @Test
        fun `text completion returns usage`() = runTest {
            val model = getModel()
            val response = provider.complete(CompletionRequest(
                model = model,
                prompt = "Hello world",
                maxTokens = 8,
            ))

            val usage = response.usage
            assertNotNull(usage, "Usage should be present")
            assertTrue(usage.promptTokens > 0)
            assertTrue(usage.totalTokens > 0)
        }

        @Test
        fun `streaming text completion`() = runTest {
            val model = getModel()
            val events = provider.completeStream(CompletionRequest(
                model = model,
                prompt = "Once upon a time",
                maxTokens = 32,
                temperature = 0.0,
            )).toList()

            val textDeltas = events.filterIsInstance<CompletionStreamEvent.TextDelta>()
            assertTrue(textDeltas.isNotEmpty(), "Should receive text deltas")

            val fullText = textDeltas.joinToString("") { it.text }
            assertTrue(fullText.isNotBlank(), "Accumulated text should not be blank")

            assertTrue(
                events.any { it is CompletionStreamEvent.Done },
                "Stream should end with Done",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible: Embeddings
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class Embeddings {
        @Test
        fun `generates single embedding`() = runTest {
            val embeddingModel = findEmbeddingModel() ?: return@runTest

            val response = provider.embed(EmbeddingRequest(
                model = embeddingModel,
                input = listOf("Hello world"),
            ))

            assertEquals(1, response.data.size, "Should have one embedding")
            val embedding = response.data.first()
            assertEquals(0, embedding.index)
            assertTrue(embedding.embedding.isNotEmpty(), "Embedding vector should not be empty")
            assertTrue(embedding.embedding.size > 10, "Embedding should have reasonable dimensionality")
        }

        @Test
        fun `generates multiple embeddings`() = runTest {
            val embeddingModel = findEmbeddingModel() ?: return@runTest

            val response = provider.embed(EmbeddingRequest(
                model = embeddingModel,
                input = listOf("Hello", "World", "Test"),
            ))

            assertEquals(3, response.data.size, "Should have three embeddings")
            response.data.forEachIndexed { idx, data ->
                assertEquals(idx, data.index)
                assertTrue(data.embedding.isNotEmpty())
            }

            // All embeddings should have the same dimensionality
            val dims = response.data.map { it.embedding.size }.toSet()
            assertEquals(1, dims.size, "All embeddings should have same dimensionality")
        }

        @Test
        fun `similar texts have similar embeddings`() = runTest {
            val embeddingModel = findEmbeddingModel() ?: return@runTest

            val response = provider.embed(EmbeddingRequest(
                model = embeddingModel,
                input = listOf(
                    "The cat sat on the mat",
                    "A cat was sitting on a mat",
                    "Quantum physics describes subatomic particles",
                ),
            ))

            val catMat1 = response.data[0].embedding
            val catMat2 = response.data[1].embedding
            val quantum = response.data[2].embedding

            val similarScore = cosineSimilarity(catMat1, catMat2)
            val dissimilarScore = cosineSimilarity(catMat1, quantum)

            assertTrue(
                similarScore > dissimilarScore,
                "Similar texts should have higher cosine similarity " +
                    "($similarScore) than dissimilar texts ($dissimilarScore)",
            )
        }

        @Test
        fun `embeddings return usage`() = runTest {
            val embeddingModel = findEmbeddingModel() ?: return@runTest

            val response = provider.embed(EmbeddingRequest(
                model = embeddingModel,
                input = listOf("Test input"),
            ))

            val usage = response.usage
            assertNotNull(usage, "Usage should be present")
            // Note: some embedding models report 0 prompt tokens but still provide usage
            assertTrue(usage.totalTokens >= 0, "Total tokens should be >= 0")
        }

        private suspend fun findEmbeddingModel(): String? {
            return try {
                val nativeModels = provider.nativeListModels()
                val embeddingModel = nativeModels.models.firstOrNull { it.type == "embedding" }
                if (embeddingModel == null) {
                    println("No embedding model available — skipping embedding test")
                }
                embeddingModel?.key
            } catch (_: Exception) {
                println("Could not query native models for embedding model — skipping")
                null
            }
        }

        private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
            require(a.size == b.size)
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            return dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Native v1: List Models
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class NativeListModels {
        @Test
        fun `lists models with detailed info`() = runTest {
            val response = provider.nativeListModels()
            assertTrue(response.models.isNotEmpty(), "Expected at least one model")

            response.models.forEach { model ->
                assertTrue(model.key.isNotBlank(), "Model key should not be blank")
                assertTrue(
                    model.type in listOf("llm", "embedding"),
                    "Model type should be 'llm' or 'embedding', got: ${model.type}",
                )
            }
        }

        @Test
        fun `llm models have expected fields`() = runTest {
            val response = provider.nativeListModels()
            val llmModel = response.models.firstOrNull { it.type == "llm" } ?: return@runTest

            assertTrue(llmModel.key.isNotBlank())
            val contextLength = llmModel.maxContextLength
            assertNotNull(contextLength, "LLM should have max_context_length")
            assertTrue(contextLength > 0, "Context length should be positive")
        }

        @Test
        fun `loaded models have instance info`() = runTest {
            val response = provider.nativeListModels()
            val loadedModel = response.models.firstOrNull { it.loadedInstances.isNotEmpty() }
                ?: return@runTest

            val instance = loadedModel.loadedInstances.first()
            assertTrue(instance.id.isNotBlank(), "Instance ID should not be blank")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Native v1: Chat
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class NativeChat {
        @Test
        fun `basic native chat with text input`() = runTest {
            val model = getModel()
            val response = provider.nativeChat(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("What is 2+2? Reply with just the number."),
                systemPrompt = "Be very brief.",
                temperature = 0.0,
                maxOutputTokens = 32,
            ))

            assertTrue(response.output.isNotEmpty(), "Should have output items")
            val messageOutput = response.output.firstOrNull { it.type == "message" }
            assertNotNull(messageOutput, "Should have a message output item")
            val content = messageOutput.content
            assertNotNull(content, "Message should have content")
            assertTrue(
                content.contains("4"),
                "Response should contain '4', got: $content",
            )

            assertTrue(response.stats.inputTokens > 0, "Input tokens should be > 0")
            assertTrue(response.stats.totalOutputTokens > 0, "Output tokens should be > 0")
            assertTrue(response.modelInstanceId.isNotBlank(), "Model instance ID should not be blank")
        }

        @Test
        fun `native chat with structured input`() = runTest {
            val model = getModel()
            val response = provider.nativeChat(NativeChatRequest(
                model = model,
                input = NativeChatInput.Messages(listOf(
                    NativeChatInputItem(type = "text", content = "Say hello"),
                )),
                temperature = 0.0,
                maxOutputTokens = 32,
            ))

            assertTrue(response.output.isNotEmpty())
            val messageOutput = response.output.firstOrNull { it.type == "message" }
            assertNotNull(messageOutput)
            val content = messageOutput.content
            assertNotNull(content)
            assertTrue(content.isNotBlank())
        }

        @Test
        fun `native chat returns stats`() = runTest {
            val model = getModel()
            val response = provider.nativeChat(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("Hi"),
                maxOutputTokens = 16,
            ))

            assertTrue(response.stats.inputTokens > 0)
            assertTrue(response.stats.totalOutputTokens > 0)
            response.stats.tokensPerSecond?.let { tps ->
                assertTrue(tps > 0, "Tokens per second should be positive")
            }
        }

        @Test
        fun `native chat with stateful continuation`() = runTest {
            val model = getModel()

            // First message
            val first = provider.nativeChat(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("My name is FraggleTestUser."),
                systemPrompt = "Remember facts about the user. Be brief.",
                store = true,
                maxOutputTokens = 64,
            ))

            val responseId = first.responseId
            if (responseId == null) {
                println("Server did not return response_id — skipping stateful continuation test")
                return@runTest
            }

            // Continue the conversation
            val second = provider.nativeChat(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("What is my name? Reply with just the name."),
                previousResponseId = responseId,
                maxOutputTokens = 32,
                temperature = 0.0,
            ))

            val content = second.output.firstOrNull { it.type == "message" }?.content
            assertNotNull(content)
            assertTrue(
                content.contains("FraggleTestUser", ignoreCase = true),
                "Stateful response should remember the name, got: $content",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Native v1: Streaming Chat
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class NativeStreamingChat {
        @Test
        fun `streams native chat response`() = runTest {
            val model = getModel()
            val events = provider.nativeChatStream(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("Count from 1 to 3, separated by commas."),
                systemPrompt = "Be very brief.",
                temperature = 0.0,
                maxOutputTokens = 32,
            )).toList()

            assertTrue(events.isNotEmpty(), "Should receive streaming events")

            // Should have message deltas
            val messageDeltas = events.filterIsInstance<NativeChatStreamEvent.MessageDelta>()
            assertTrue(messageDeltas.isNotEmpty(), "Should receive message deltas")

            val fullText = messageDeltas.joinToString("") { it.content }
            assertTrue(fullText.isNotBlank(), "Accumulated message text should not be blank")

            // Should end with ChatEnd containing full response
            val chatEnd = events.filterIsInstance<NativeChatStreamEvent.ChatEnd>()
            assertTrue(chatEnd.isNotEmpty(), "Should receive ChatEnd event")

            val result = chatEnd.first().result
            assertTrue(result.output.isNotEmpty(), "ChatEnd result should have output")
            assertTrue(result.stats.totalOutputTokens > 0, "ChatEnd should have stats")
        }

        @Test
        fun `native stream includes lifecycle events`() = runTest {
            val model = getModel()
            val events = provider.nativeChatStream(NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("Hi"),
                maxOutputTokens = 8,
            )).toList()

            // Should have MessageStart and MessageEnd
            assertTrue(
                events.any { it is NativeChatStreamEvent.MessageStart },
                "Should have MessageStart",
            )
            assertTrue(
                events.any { it is NativeChatStreamEvent.MessageEnd },
                "Should have MessageEnd",
            )
        }

        @Test
        fun `native streaming result matches non-streaming`() = runTest {
            val model = getModel()
            val request = NativeChatRequest(
                model = model,
                input = NativeChatInput.Text("Reply with exactly: PONG"),
                systemPrompt = "Always respond with exactly: PONG",
                temperature = 0.0,
                maxOutputTokens = 16,
            )

            val nonStreamResponse = provider.nativeChat(request)
            val nonStreamContent = nonStreamResponse.output
                .firstOrNull { it.type == "message" }?.content ?: ""

            val streamEvents = provider.nativeChatStream(request).toList()
            val streamContent = streamEvents
                .filterIsInstance<NativeChatStreamEvent.MessageDelta>()
                .joinToString("") { it.content }

            assertTrue(
                nonStreamContent.trim() == streamContent.trim(),
                "Non-streaming ('${nonStreamContent.trim()}') should match streaming ('${streamContent.trim()}')",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Error Handling
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    inner class ErrorHandling {
        @Test
        fun `chat with invalid model throws`() = runTest {
            assertFailsWith<LLMProviderException> {
                provider.chat(ChatRequest(
                    model = "nonexistent-model-that-does-not-exist-12345",
                    messages = listOf(Message.user("Hi")),
                ))
            }
        }

        @Test
        fun `connection to invalid host throws`() = runTest {
            val badProvider = LMStudioProvider(
                baseUrl = "http://localhost:99999/v1",
                httpClient = httpClient,
            )

            assertFailsWith<LLMProviderException> {
                badProvider.listModels()
            }
        }
    }
}
