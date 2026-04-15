package fraggle.testing

import fraggle.provider.ChatRequest
import fraggle.provider.FunctionCall
import fraggle.provider.LMStudioProvider
import fraggle.provider.LLMProviderException
import fraggle.provider.Message
import fraggle.provider.ToolCall
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Shared test environment for live LM Studio integration tests.
 *
 * Environment variables:
 * - `LMS_API_URL` — Base URL of the LM Studio-compatible server (required to enable tests)
 * - `LMS_API_KEY` — Optional bearer token for authentication
 * - `LMS_TEST_MODEL` — Optional specific model ID to use for chat tests. If unset,
 *    falls back to auto-discovery via the server's model list.
 * - `LMS_TEST_EMBEDDING_MODEL` — Optional specific embedding model ID. If unset,
 *    falls back to auto-discovery.
 *
 * Pinning `LMS_TEST_MODEL` is strongly recommended to avoid tests triggering
 * large/slow model loads on the server.
 */
object TestLlmEnvironment {

    val apiUrl: String = System.getenv("LMS_API_URL") ?: ""
    val apiKey: String? = System.getenv("LMS_API_KEY")

    /** Explicit chat/completion model from env. Null means auto-discover. */
    val overrideModel: String? = System.getenv("LMS_TEST_MODEL")?.takeIf { it.isNotBlank() }

    /** Explicit embedding model from env. Null means auto-discover. */
    val overrideEmbeddingModel: String? = System.getenv("LMS_TEST_EMBEDDING_MODEL")?.takeIf { it.isNotBlank() }

    val isAvailable: Boolean get() = apiUrl.isNotBlank()

    val json = Json {
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

    @Volatile
    private var cachedChatModel: String? = null

    @Volatile
    private var cachedEmbeddingModel: String? = null

    /**
     * Resolve a chat model to use for tests.
     *
     * Resolution order:
     * 1. `LMS_TEST_MODEL` env var (if set)
     * 2. An LLM model already loaded on the server (via native API)
     * 3. Any LLM model available on the server (via native API)
     * 4. First model listed by the OpenAI-compatible /models endpoint
     *
     * Caches the resolved model for the JVM lifetime.
     */
    suspend fun getChatModel(): String {
        cachedChatModel?.let { return it }

        overrideModel?.let {
            cachedChatModel = it
            return it
        }

        // Try native endpoint first for richer info
        try {
            val nativeModels = provider.nativeListModels()
            val loadedLlm = nativeModels.models.firstOrNull { model ->
                model.type == "llm" && model.loadedInstances.isNotEmpty()
            }
            if (loadedLlm != null) {
                cachedChatModel = loadedLlm.key
                return loadedLlm.key
            }
            val anyLlm = nativeModels.models.firstOrNull { it.type == "llm" }
            if (anyLlm != null) {
                cachedChatModel = anyLlm.key
                return anyLlm.key
            }
        } catch (_: Exception) {
            // Native endpoint may not be available
        }

        val models = provider.listModels()
        assertTrue(models.isNotEmpty(), "No models available on the LM Studio server")
        val id = models.first().id
        cachedChatModel = id
        return id
    }

    /**
     * Resolve an embedding model to use for tests. Returns null if none available
     * (in which case the caller should skip the test).
     *
     * Resolution order:
     * 1. `LMS_TEST_EMBEDDING_MODEL` env var (if set)
     * 2. First embedding model via native API
     */
    suspend fun findEmbeddingModel(): String? {
        cachedEmbeddingModel?.let { return it }

        overrideEmbeddingModel?.let {
            cachedEmbeddingModel = it
            return it
        }

        return try {
            val nativeModels = provider.nativeListModels()
            val embeddingModel = nativeModels.models.firstOrNull { it.type == "embedding" }
            if (embeddingModel == null) {
                println("No embedding model available — skipping embedding test")
            }
            embeddingModel?.key.also { cachedEmbeddingModel = it }
        } catch (_: Exception) {
            println("Could not query native models for embedding model — skipping")
            null
        }
    }

    /**
     * Known LM Studio / model infrastructure issue where a jinja prompt
     * template can't render messages containing `role: tool`. Manifests as
     * an HTTP 400 with "Unknown test: sequence".
     *
     * Example: Gemma-4 default LM Studio template.
     */
    fun isJinjaToolTemplateError(message: String?): Boolean =
        message != null && message.contains("Unknown test: sequence")

    @Volatile
    private var cachedUsesReasoning: Boolean? = null

    /**
     * Probe the resolved model to detect whether it silently emits
     * reasoning tokens that consume the `max_tokens` budget (producing an
     * empty `content` when the cap is small). Fine-tuned variants (e.g.
     * gemma-4-26b-heretic-guff) can emit reasoning even when the upstream
     * model metadata says otherwise.
     *
     * Detection strategy: ask the model a trivial question with a small
     * token budget. A regular model answers with non-empty content; a
     * reasoning model burns the budget on hidden reasoning and returns
     * empty content with `finish_reason: length`.
     *
     * Result is cached for the JVM lifetime.
     */
    suspend fun usesReasoning(): Boolean {
        cachedUsesReasoning?.let { return it }
        return try {
            val model = getChatModel()
            val response = provider.chat(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.user("Say hi.")),
                    temperature = 0.0,
                    maxTokens = 16,
                ),
            )
            val choice = response.choices.firstOrNull()
            val contentEmpty = choice?.message?.content.isNullOrBlank()
            val hitLength = choice?.finishReason == "length"
            val reasoning = contentEmpty && hitLength
            cachedUsesReasoning = reasoning
            reasoning
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Return a safe `max_tokens` budget for a test. On reasoning models, the
     * budget is scaled up to leave headroom for reasoning_content (which
     * competes with `content` under the same cap). On regular models, the
     * caller-requested budget is returned unchanged.
     */
    suspend fun maxTokens(nominal: Int): Int =
        if (usesReasoning()) maxOf(nominal, 512) else nominal

    /**
     * Cache of tool roundtrip support per model, keyed by model id.
     * `true` means the server can successfully accept a message sequence
     * that includes a `role: tool` message, `false` means it hits the
     * known jinja template bug.
     */
    private val toolRoundtripSupport = mutableMapOf<String, Boolean>()

    /**
     * Probe the server to see if the given model supports the tool-roundtrip
     * message shape (assistant tool_calls + tool result). Aborts the test via
     * [Assumptions.abort] if the server rejects it with the known jinja template
     * error. Result is cached for subsequent tests.
     */
    suspend fun assumeToolRoundtripSupported(provider: LMStudioProvider, model: String) {
        val supported = toolRoundtripSupport.getOrPut(model) {
            val probeTool = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "probe_tool")
                    put("description", "probe")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("location") {
                                put("type", "string")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("location")) }
                    }
                }
            }
            val probe = ChatRequest(
                model = model,
                messages = listOf(
                    Message.system("You use tools."),
                    Message.user("probe"),
                    // Use non-trivial arguments to reliably trigger the known
                    // jinja template bug with Gemma-style prompt templates.
                    Message.assistant(
                        listOf(
                            ToolCall(
                                id = "call_probe",
                                function = FunctionCall(
                                    name = "probe_tool",
                                    arguments = """{"location":"Paris"}""",
                                ),
                            ),
                        ),
                    ),
                    // Use a multi-field object to reliably trigger the known
                    // jinja "Unknown test: sequence" bug on Gemma-style templates.
                    Message.tool("call_probe", """{"temperature": 22, "condition": "sunny"}"""),
                ),
                tools = listOf(probeTool),
                temperature = 0.0,
                maxTokens = 1,
            )
            try {
                provider.chat(probe)
                true
            } catch (e: LLMProviderException) {
                if (isJinjaToolTemplateError(e.message)) false else true
            } catch (_: Exception) {
                true
            }
        }
        if (!supported) {
            Assumptions.abort<Unit>(
                "Model '$model' tool roundtrip not supported by server (jinja template bug). Skipping.",
            )
        }
    }
}
