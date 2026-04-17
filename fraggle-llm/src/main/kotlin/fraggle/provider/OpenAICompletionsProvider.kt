package fraggle.provider

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * [LLMProvider] implementation for OpenAI's `/v1/chat/completions` endpoint
 * (and OpenAI-compatible APIs that follow the same wire format but don't
 * need the full LM Studio feature surface).
 *
 * For non-OpenAI compat backends running OpenAI-shaped APIs (LM Studio,
 * Ollama, vLLM), prefer [LMStudioProvider] which additionally exposes the
 * native v1 endpoints.
 *
 * Reasoning-model handling is controlled by [isReasoningModel]. When the
 * caller's model matches, this provider:
 * - sends `max_completion_tokens` instead of `max_tokens`
 * - substitutes `developer` for the `system` role
 * - drops `temperature` (o-series reject non-default values)
 * - forwards [ChatRequest.thinking] as `reasoning_effort`
 */
class OpenAICompletionsProvider(
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: HttpClient,
    /**
     * Returns true if the given model id should be treated as a reasoning
     * model. Default matches OpenAI's naming conventions: `o1`, `o3`, `o4`,
     * and any `-mini`/`-preview` variant.
     */
    private val isReasoningModel: (String) -> Boolean = ::isOpenAIReasoningModel,
    /**
     * Whether the target API supports the `store` request field. OpenAI
     * proper does; most OpenAI-compatible backends don't.
     */
    private val supportsStore: Boolean = true,
    private val defaultModel: String? = null,
) : LLMProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    override val name: String = "OpenAI"

    override fun supportsTools(): Boolean = true

    override suspend fun listModels(): List<ModelInfo> {
        val response = httpGet("$baseUrl/models")
        val modelsResponse: OpenAIModelsResponse = response.body()
        return modelsResponse.data.map { model ->
            ModelInfo(id = model.id, name = model.id, owned_by = model.ownedBy)
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val apiRequest = buildWireRequest(request, streaming = false)
        val response = httpPost("$baseUrl/chat/completions", apiRequest)
        val apiResponse: OpenAIChatResponse = response.body()
        return apiResponse.toChatResponse()
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val reducer = ChatEventReducer()
        emit(ChatEvent.Start(reducer.partial()))

        try {
            val apiRequest = buildWireRequest(request, streaming = true)

            httpClient.preparePost("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                applyAuth()
                setBody(apiRequest)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    throw LLMProviderException(
                        "OpenAI streaming error: ${response.status.value} - $errorBody",
                        statusCode = response.status.value,
                    )
                }

                val channel = response.bodyAsChannel()
                parseOpenAISse(channel) { eventData ->
                    if (eventData == "[DONE]") return@parseOpenAISse

                    val chunk = json.decodeFromString<OpenAIStreamChunk>(eventData)
                    val choice = chunk.choices?.firstOrNull()
                    val delta = choice?.delta

                    if (delta != null) {
                        // Reasoning content arrives in either `reasoning` or
                        // `reasoning_content` depending on the backend.
                        val thinkingChunk = delta.reasoning ?: delta.reasoningContent
                        if (!thinkingChunk.isNullOrEmpty()) {
                            for (e in reducer.onThinkingDelta(thinkingChunk)) emit(e)
                        }
                        if (!delta.content.isNullOrEmpty()) {
                            for (e in reducer.onTextDelta(delta.content)) emit(e)
                        }
                        delta.toolCalls?.forEach { tc ->
                            for (e in reducer.onToolCallDelta(
                                index = tc.index,
                                id = tc.id,
                                name = tc.function?.name,
                                argumentsDelta = tc.function?.arguments,
                            )) emit(e)
                        }
                    }

                    chunk.usage?.let { u ->
                        reducer.usage = Usage(
                            promptTokens = u.promptTokens,
                            completionTokens = u.completionTokens,
                            totalTokens = u.totalTokens,
                        )
                    }
                    choice?.finishReason?.let { reducer.finishReason = it }
                }
            }

            for (e in reducer.finish()) emit(e)
        } catch (e: Throwable) {
            emit(
                ChatEvent.Error(
                    reason = StopReason.ERROR,
                    partial = reducer.partial(),
                    errorMessage = e.message ?: e::class.simpleName ?: "unknown error",
                ),
            )
        }
    }

    // ── Request building ────────────────────────────────────────────────

    private fun buildWireRequest(request: ChatRequest, streaming: Boolean): OpenAIChatRequest {
        val resolvedModel = request.model.ifEmpty { defaultModel ?: error("No model specified") }
        val isReasoning = isReasoningModel(resolvedModel)

        val wire = request.copy(model = resolvedModel).toOpenAIRequest(
            defaultModel = defaultModel,
            systemRole = if (isReasoning) "developer" else "system",
            maxTokensField = if (isReasoning) {
                OpenAIMaxTokensField.MAX_COMPLETION_TOKENS
            } else {
                OpenAIMaxTokensField.MAX_TOKENS
            },
            reasoningEffort = if (isReasoning) {
                mapReasoningEffort(request.thinking)
            } else {
                null
            },
            supportsStore = supportsStore,
            suppressTemperature = isReasoning,
        )

        return if (streaming) {
            wire.copy(
                stream = true,
                streamOptions = OpenAIStreamOptions(includeUsage = true),
            )
        } else {
            wire
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────────────

    private suspend fun httpPost(url: String, body: Any): HttpResponse {
        val response = try {
            httpClient.post(url) {
                contentType(ContentType.Application.Json)
                applyAuth()
                setBody(body)
            }
        } catch (e: Exception) {
            throw LLMProviderException("Failed to call OpenAI at $url: ${e.message}", cause = e)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw LLMProviderException(
                "OpenAI API error: ${response.status.value} - $errorBody",
                statusCode = response.status.value,
            )
        }
        return response
    }

    private suspend fun httpGet(url: String): HttpResponse {
        val response = try {
            httpClient.get(url) { applyAuth() }
        } catch (e: Exception) {
            throw LLMProviderException("Failed to call OpenAI at $url: ${e.message}", cause = e)
        }
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw LLMProviderException(
                "OpenAI API error: ${response.status.value} - $errorBody",
                statusCode = response.status.value,
            )
        }
        return response
    }

    private fun HttpRequestBuilder.applyAuth() {
        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}

/**
 * Default reasoning-model matcher. True for OpenAI's o-series (`o1`, `o3`,
 * `o4`) and their `-mini`/`-preview` variants. Callers with custom naming
 * can pass their own predicate to [OpenAICompletionsProvider].
 */
fun isOpenAIReasoningModel(modelId: String): Boolean {
    val normalized = modelId.lowercase().substringAfterLast('/')
    return normalized.startsWith("o1") ||
        normalized.startsWith("o3") ||
        normalized.startsWith("o4") ||
        normalized.startsWith("gpt-5")
}

/**
 * Map the neutral [ThinkingLevel] to OpenAI's `reasoning_effort` string.
 * OpenAI recognises: "minimal", "low", "medium", "high".
 *
 * The LM-Studio-specific levels [ThinkingLevel.OFF] and [ThinkingLevel.ON]
 * degrade to the nearest OpenAI equivalent: OFF is expressed by omitting
 * the field entirely (there's no "disable reasoning" knob on OpenAI's API),
 * and ON pins to "high".
 */
internal fun mapReasoningEffort(level: ThinkingLevel?): String? = when (level) {
    null, ThinkingLevel.OFF -> null
    ThinkingLevel.MINIMAL -> "minimal"
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH, ThinkingLevel.ON -> "high"
}
