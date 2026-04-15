package fraggle.provider

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Comprehensive LM Studio provider supporting both OpenAI-compatible endpoints
 * and the native LM Studio v1 REST API.
 *
 * OpenAI-compatible endpoints (at [baseUrl]):
 * - POST /chat/completions — chat with streaming and tool calling
 * - POST /completions — legacy text completion
 * - POST /embeddings — text embeddings
 * - GET /models — list models
 *
 * Native v1 endpoints (at [nativeBaseUrl]):
 * - POST /api/v1/chat — native chat with streaming, MCP integrations, stateful chats
 * - GET /api/v1/models — detailed model listing with capabilities and loaded instances
 */
class LMStudioProvider(
    /** Base URL for OpenAI-compatible endpoints, e.g. "http://localhost:1234/v1" */
    private val baseUrl: String = "http://localhost:1234/v1",
    private val defaultModel: String? = null,
    private val httpClient: HttpClient,
    private val apiKey: String? = null,
) : LLMProvider {

    private val logger = LoggerFactory.getLogger(LMStudioProvider::class.java)

    /**
     * Base URL for native LM Studio v1 endpoints.
     * Derived from [baseUrl] by stripping the trailing "/v1" path.
     */
    private val nativeBaseUrl: String = baseUrl.removeSuffix("/v1").removeSuffix("/")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }

    override val name: String = "LM Studio"

    override fun supportsTools(): Boolean = true

    // ── OpenAI-Compatible: Chat Completions ─────────────────────────────

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val apiRequest = request.toOpenAIRequest(defaultModel)
        val response = httpPost("$baseUrl/chat/completions", apiRequest)
        val apiResponse: OpenAIChatResponse = response.body()
        return apiResponse.toChatResponse()
    }

    /**
     * Streaming chat completion via OpenAI-compatible endpoint.
     * Returns a [Flow] of [ChatStreamEvent]s as they arrive via SSE.
     */
    fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val apiRequest = request.toOpenAIRequest(defaultModel).copy(
            stream = true,
            streamOptions = StreamOptions(includeUsage = true),
        )

        httpClient.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(apiRequest)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw LLMProviderException(
                    "LM Studio streaming error: ${response.status.value} - $errorBody",
                    statusCode = response.status.value,
                )
            }

            val channel = response.bodyAsChannel()
            parseSSEStream(channel) { eventData ->
                if (eventData == "[DONE]") {
                    emit(ChatStreamEvent.Done)
                    return@parseSSEStream
                }

                val chunk = json.decodeFromString<OpenAIStreamChunk>(eventData)
                val delta = chunk.choices?.firstOrNull()?.delta
                val finishReason = chunk.choices?.firstOrNull()?.finishReason

                if (delta != null) {
                    // Text content delta
                    delta.content?.let { text ->
                        emit(ChatStreamEvent.TextDelta(text))
                    }

                    // Reasoning content delta
                    delta.reasoning?.let { reasoning ->
                        emit(ChatStreamEvent.ReasoningDelta(reasoning))
                    }

                    // Tool call deltas
                    delta.toolCalls?.forEach { toolCallDelta ->
                        emit(ChatStreamEvent.ToolCallDelta(
                            index = toolCallDelta.index,
                            id = toolCallDelta.id,
                            functionName = toolCallDelta.function?.name,
                            argumentsDelta = toolCallDelta.function?.arguments,
                        ))
                    }
                }

                // Usage info (arrives in final chunk when stream_options.include_usage is set)
                chunk.usage?.let { usage ->
                    emit(ChatStreamEvent.UsageInfo(
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens,
                        totalTokens = usage.totalTokens,
                    ))
                }

                if (finishReason != null) {
                    emit(ChatStreamEvent.FinishReason(finishReason))
                }
            }
        }
    }

    // ── OpenAI-Compatible: Text Completions (Legacy) ────────────────────

    /**
     * Legacy text completion via OpenAI-compatible endpoint.
     * Best used with base (non-chat-tuned) models.
     */
    suspend fun complete(request: CompletionRequest): CompletionResponse {
        val apiRequest = OpenAICompletionRequest(
            model = request.model.ifEmpty { defaultModel ?: error("No model specified") },
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = request.stop,
            echo = request.echo,
            seed = request.seed,
        )
        val response = httpPost("$baseUrl/completions", apiRequest)
        val apiResponse: OpenAICompletionResponse = response.body()
        return CompletionResponse(
            id = apiResponse.id,
            model = apiResponse.model,
            choices = apiResponse.choices.map { choice ->
                CompletionChoice(
                    index = choice.index,
                    text = choice.text,
                    finishReason = choice.finishReason,
                )
            },
            usage = apiResponse.usage?.toUsage(),
        )
    }

    /**
     * Streaming text completion via OpenAI-compatible endpoint.
     */
    fun completeStream(request: CompletionRequest): Flow<CompletionStreamEvent> = flow {
        val apiRequest = OpenAICompletionRequest(
            model = request.model.ifEmpty { defaultModel ?: error("No model specified") },
            prompt = request.prompt,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = request.stop,
            echo = request.echo,
            seed = request.seed,
            stream = true,
        )

        httpClient.preparePost("$baseUrl/completions") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(apiRequest)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw LLMProviderException(
                    "LM Studio completions streaming error: ${response.status.value} - $errorBody",
                    statusCode = response.status.value,
                )
            }

            val channel = response.bodyAsChannel()
            parseSSEStream(channel) { eventData ->
                if (eventData == "[DONE]") {
                    emit(CompletionStreamEvent.Done)
                    return@parseSSEStream
                }

                val chunk = json.decodeFromString<OpenAICompletionStreamChunk>(eventData)
                val choice = chunk.choices.firstOrNull()
                if (choice != null) {
                    choice.text?.let { text ->
                        emit(CompletionStreamEvent.TextDelta(text))
                    }
                    choice.finishReason?.let { reason ->
                        emit(CompletionStreamEvent.FinishReason(reason))
                    }
                }
            }
        }
    }

    // ── OpenAI-Compatible: Embeddings ───────────────────────────────────

    /**
     * Generate embeddings via OpenAI-compatible endpoint.
     */
    suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        val apiRequest = OpenAIEmbeddingRequest(
            model = request.model.ifEmpty { defaultModel ?: error("No model specified") },
            input = request.input,
        )
        val response = httpPost("$baseUrl/embeddings", apiRequest)
        val apiResponse: OpenAIEmbeddingResponse = response.body()
        return EmbeddingResponse(
            model = apiResponse.model,
            data = apiResponse.data.map { item ->
                EmbeddingData(
                    index = item.index,
                    embedding = item.embedding,
                )
            },
            usage = apiResponse.usage?.let {
                EmbeddingUsage(
                    promptTokens = it.promptTokens,
                    totalTokens = it.totalTokens,
                )
            },
        )
    }

    // ── OpenAI-Compatible: List Models ──────────────────────────────────

    override suspend fun listModels(): List<ModelInfo> {
        val response = httpGet("$baseUrl/models")
        val modelsResponse: OpenAIModelsResponse = response.body()
        return modelsResponse.data.map { model ->
            ModelInfo(
                id = model.id,
                name = model.id,
                owned_by = model.ownedBy,
            )
        }
    }

    // ── Native v1: Chat ─────────────────────────────────────────────────

    /**
     * Chat via the native LM Studio v1 endpoint.
     * Supports MCP integrations, stateful chats, and reasoning modes.
     */
    suspend fun nativeChat(request: NativeChatRequest): NativeChatResponse {
        val response = httpPost("$nativeBaseUrl/api/v1/chat", request)
        return response.body()
    }

    /**
     * Streaming chat via the native LM Studio v1 endpoint.
     * Returns a [Flow] of [NativeChatStreamEvent]s.
     */
    fun nativeChatStream(request: NativeChatRequest): Flow<NativeChatStreamEvent> = flow {
        val streamRequest = request.copy(stream = true)

        httpClient.preparePost("$nativeBaseUrl/api/v1/chat") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(streamRequest)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw LLMProviderException(
                    "LM Studio native chat streaming error: ${response.status.value} - $errorBody",
                    statusCode = response.status.value,
                )
            }

            val channel = response.bodyAsChannel()
            parseNativeSSEStream(channel) { eventType, eventData ->
                val event = when (eventType) {
                    "chat.start" -> {
                        val data = json.decodeFromString<NativeSSEChatStart>(eventData)
                        NativeChatStreamEvent.ChatStart(modelInstanceId = data.modelInstanceId)
                    }
                    "reasoning.start" -> NativeChatStreamEvent.ReasoningStart
                    "reasoning.delta" -> {
                        val data = json.decodeFromString<NativeSSEContentDelta>(eventData)
                        NativeChatStreamEvent.ReasoningDelta(content = data.content)
                    }
                    "reasoning.end" -> NativeChatStreamEvent.ReasoningEnd
                    "message.start" -> NativeChatStreamEvent.MessageStart
                    "message.delta" -> {
                        val data = json.decodeFromString<NativeSSEContentDelta>(eventData)
                        NativeChatStreamEvent.MessageDelta(content = data.content)
                    }
                    "message.end" -> NativeChatStreamEvent.MessageEnd
                    "tool_call.start" -> {
                        val data = json.decodeFromString<NativeSSEToolCallStart>(eventData)
                        NativeChatStreamEvent.ToolCallStart(
                            tool = data.tool,
                            providerInfo = data.providerInfo,
                        )
                    }
                    "tool_call.arguments" -> {
                        val data = json.decodeFromString<NativeSSEToolCallArguments>(eventData)
                        NativeChatStreamEvent.ToolCallArguments(arguments = data.arguments)
                    }
                    "tool_call.success" -> {
                        val data = json.decodeFromString<NativeSSEToolCallSuccess>(eventData)
                        NativeChatStreamEvent.ToolCallSuccess(output = data.output)
                    }
                    "tool_call.failure" -> {
                        val data = json.decodeFromString<NativeSSEToolCallFailure>(eventData)
                        NativeChatStreamEvent.ToolCallFailure(
                            reason = data.reason,
                            metadata = data.metadata,
                        )
                    }
                    "error" -> {
                        val data = json.decodeFromString<NativeSSEError>(eventData)
                        NativeChatStreamEvent.Error(
                            type = data.error.type,
                            message = data.error.message,
                        )
                    }
                    "chat.end" -> {
                        val data = json.decodeFromString<NativeSSEChatEnd>(eventData)
                        NativeChatStreamEvent.ChatEnd(result = data.result)
                    }
                    // Model load events — emit but don't require special handling
                    "model_load.start" -> NativeChatStreamEvent.ModelLoadStart
                    "model_load.progress" -> {
                        val data = json.decodeFromString<NativeSSEModelLoadProgress>(eventData)
                        NativeChatStreamEvent.ModelLoadProgress(progress = data.progress)
                    }
                    "model_load.end" -> NativeChatStreamEvent.ModelLoadEnd
                    "prompt_processing.start" -> NativeChatStreamEvent.PromptProcessingStart
                    "prompt_processing.progress" -> {
                        val data = json.decodeFromString<NativeSSEPromptProcessingProgress>(eventData)
                        NativeChatStreamEvent.PromptProcessingProgress(progress = data.progress)
                    }
                    "prompt_processing.end" -> NativeChatStreamEvent.PromptProcessingEnd
                    else -> {
                        logger.debug("Unknown native SSE event type: {}", eventType)
                        null
                    }
                }
                if (event != null) emit(event)
            }
        }
    }

    // ── Native v1: List Models ──────────────────────────────────────────

    /**
     * List models via native LM Studio v1 endpoint.
     * Returns detailed model info including capabilities, loaded instances, and architecture.
     */
    suspend fun nativeListModels(): NativeModelsResponse {
        val response = httpGet("$nativeBaseUrl/api/v1/models")
        return response.body()
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
            throw LLMProviderException("Failed to connect to LM Studio at $url: ${e.message}", cause = e)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw LLMProviderException(
                "LM Studio API error: ${response.status.value} - $errorBody",
                statusCode = response.status.value,
            )
        }

        return response
    }

    private suspend fun httpGet(url: String): HttpResponse {
        val response = try {
            httpClient.get(url) {
                applyAuth()
            }
        } catch (e: Exception) {
            throw LLMProviderException("Failed to connect to LM Studio at $url: ${e.message}", cause = e)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw LLMProviderException(
                "LM Studio API error: ${response.status.value} - $errorBody",
                statusCode = response.status.value,
            )
        }

        return response
    }

    private fun HttpRequestBuilder.applyAuth() {
        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    // ── SSE parsing ─────────────────────────────────────────────────────

    /**
     * Parse OpenAI-format SSE stream (no event: field, just data: lines).
     */
    private suspend fun parseSSEStream(
        channel: ByteReadChannel,
        onData: suspend (String) -> Unit,
    ) {
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break

            when {
                line.startsWith("data: ") -> {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isNotEmpty()) {
                        onData(data)
                    }
                }
                line.isBlank() -> {
                    // SSE event boundary
                }
                line.startsWith(":") -> {
                    // SSE comment (keepalive), ignore
                }
            }
        }
    }

    /**
     * Parse native LM Studio SSE stream (has both event: and data: fields).
     */
    private suspend fun parseNativeSSEStream(
        channel: ByteReadChannel,
        onEvent: suspend (type: String, data: String) -> Unit,
    ) {
        var currentEventType: String? = null
        var currentData: String? = null

        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break

            when {
                line.startsWith("event: ") -> {
                    currentEventType = line.removePrefix("event: ").trim()
                }
                line.startsWith("data: ") -> {
                    currentData = line.removePrefix("data: ").trim()
                }
                line.isBlank() -> {
                    // End of SSE event — dispatch if we have both type and data
                    if (currentEventType != null && currentData != null) {
                        onEvent(currentEventType, currentData)
                    }
                    currentEventType = null
                    currentData = null
                }
                line.startsWith(":") -> {
                    // SSE comment, ignore
                }
            }
        }

        // Handle final event if stream closes without trailing newline
        if (currentEventType != null && currentData != null) {
            onEvent(currentEventType, currentData)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Public domain models
// ═════════════════════════════════════════════════════════════════════════

// ── Chat Stream Events ──────────────────────────────────────────────────

/**
 * Events emitted during OpenAI-compatible streaming chat completion.
 */
sealed class ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent()
    data class ReasoningDelta(val text: String) : ChatStreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val functionName: String?,
        val argumentsDelta: String?,
    ) : ChatStreamEvent()
    data class FinishReason(val reason: String) : ChatStreamEvent()
    data class UsageInfo(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
    ) : ChatStreamEvent()
    data object Done : ChatStreamEvent()
}

// ── Text Completion ─────────────────────────────────────────────────────

data class CompletionRequest(
    val model: String = "",
    val prompt: String,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
    val echo: Boolean? = null,
    val seed: Int? = null,
)

data class CompletionResponse(
    val id: String,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: Usage? = null,
)

data class CompletionChoice(
    val index: Int,
    val text: String,
    val finishReason: String? = null,
)

sealed class CompletionStreamEvent {
    data class TextDelta(val text: String) : CompletionStreamEvent()
    data class FinishReason(val reason: String) : CompletionStreamEvent()
    data object Done : CompletionStreamEvent()
}

// ── Embeddings ──────────────────────────────────────────────────────────

data class EmbeddingRequest(
    val model: String = "",
    val input: List<String>,
)

data class EmbeddingResponse(
    val model: String,
    val data: List<EmbeddingData>,
    val usage: EmbeddingUsage? = null,
)

data class EmbeddingData(
    val index: Int,
    val embedding: List<Double>,
)

data class EmbeddingUsage(
    val promptTokens: Int,
    val totalTokens: Int,
)

// ── Native v1 Chat ──────────────────────────────────────────────────────

@Serializable
data class NativeChatRequest(
    val model: String,
    val input: NativeChatInput,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    val stream: Boolean? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    @SerialName("min_p")
    val minP: Double? = null,
    @SerialName("repeat_penalty")
    val repeatPenalty: Double? = null,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
    val reasoning: String? = null,
    @SerialName("context_length")
    val contextLength: Int? = null,
    val store: Boolean? = null,
    @SerialName("previous_response_id")
    val previousResponseId: String? = null,
    val integrations: List<NativeMcpIntegration>? = null,
)

/**
 * Input for native chat — either a plain string or an array of input items.
 */
@Serializable(with = NativeChatInputSerializer::class)
sealed class NativeChatInput {
    data class Text(val text: String) : NativeChatInput()
    data class Messages(val items: List<NativeChatInputItem>) : NativeChatInput()
}

/**
 * Input item for native chat. Types: "text" (with [content]) or "image" (with [dataUrl]).
 */
@Serializable
data class NativeChatInputItem(
    /** "text" or "image" */
    val type: String,
    val content: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
)

@Serializable
data class NativeMcpIntegration(
    val type: String,
    @SerialName("server_label")
    val serverLabel: String? = null,
    @SerialName("server_url")
    val serverUrl: String? = null,
    val id: String? = null,
    @SerialName("allowed_tools")
    val allowedTools: List<String>? = null,
    val headers: JsonObject? = null,
)

@Serializable
data class NativeChatResponse(
    @SerialName("model_instance_id")
    val modelInstanceId: String,
    val output: List<NativeChatOutputItem>,
    val stats: NativeChatStats,
    @SerialName("response_id")
    val responseId: String? = null,
)

@Serializable
data class NativeChatOutputItem(
    val type: String,
    val content: String? = null,
    val tool: String? = null,
    val arguments: JsonElement? = null,
    val output: String? = null,
    @SerialName("provider_info")
    val providerInfo: JsonElement? = null,
    val reason: String? = null,
    val metadata: JsonElement? = null,
)

@Serializable
data class NativeChatStats(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("total_output_tokens")
    val totalOutputTokens: Int,
    @SerialName("reasoning_output_tokens")
    val reasoningOutputTokens: Int? = null,
    @SerialName("tokens_per_second")
    val tokensPerSecond: Double? = null,
    @SerialName("time_to_first_token_seconds")
    val timeToFirstTokenSeconds: Double? = null,
    @SerialName("model_load_time_seconds")
    val modelLoadTimeSeconds: Double? = null,
)

// ── Native v1 Chat Stream Events ────────────────────────────────────────

sealed class NativeChatStreamEvent {
    data class ChatStart(val modelInstanceId: String) : NativeChatStreamEvent()
    data object ReasoningStart : NativeChatStreamEvent()
    data class ReasoningDelta(val content: String) : NativeChatStreamEvent()
    data object ReasoningEnd : NativeChatStreamEvent()
    data object MessageStart : NativeChatStreamEvent()
    data class MessageDelta(val content: String) : NativeChatStreamEvent()
    data object MessageEnd : NativeChatStreamEvent()
    data class ToolCallStart(val tool: String, val providerInfo: JsonElement?) : NativeChatStreamEvent()
    data class ToolCallArguments(val arguments: JsonElement?) : NativeChatStreamEvent()
    data class ToolCallSuccess(val output: String?) : NativeChatStreamEvent()
    data class ToolCallFailure(val reason: String?, val metadata: JsonElement?) : NativeChatStreamEvent()
    data class Error(val type: String?, val message: String?) : NativeChatStreamEvent()
    data class ChatEnd(val result: NativeChatResponse) : NativeChatStreamEvent()
    data object ModelLoadStart : NativeChatStreamEvent()
    data class ModelLoadProgress(val progress: Double) : NativeChatStreamEvent()
    data object ModelLoadEnd : NativeChatStreamEvent()
    data object PromptProcessingStart : NativeChatStreamEvent()
    data class PromptProcessingProgress(val progress: Double) : NativeChatStreamEvent()
    data object PromptProcessingEnd : NativeChatStreamEvent()
}

// ── Native v1 Models ────────────────────────────────────────────────────

@Serializable
data class NativeModelsResponse(
    val models: List<NativeModelInfo>,
)

@Serializable
data class NativeModelInfo(
    val type: String,
    val publisher: String? = null,
    val key: String,
    @SerialName("display_name")
    val displayName: String? = null,
    val architecture: String? = null,
    val quantization: NativeQuantization? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
    @SerialName("params_string")
    val paramsString: String? = null,
    @SerialName("loaded_instances")
    val loadedInstances: List<NativeLoadedInstance> = emptyList(),
    @SerialName("max_context_length")
    val maxContextLength: Int? = null,
    val format: String? = null,
    val capabilities: NativeModelCapabilities? = null,
    val description: String? = null,
)

@Serializable
data class NativeQuantization(
    val name: String? = null,
    @SerialName("bits_per_weight")
    val bitsPerWeight: Double? = null,
)

@Serializable
data class NativeLoadedInstance(
    val id: String,
    val config: JsonElement? = null,
)

@Serializable
data class NativeModelCapabilities(
    val vision: Boolean = false,
    @SerialName("trained_for_tool_use")
    val trainedForToolUse: Boolean = false,
    val reasoning: NativeReasoningCapability? = null,
)

@Serializable
data class NativeReasoningCapability(
    @SerialName("allowed_options")
    val allowedOptions: List<String> = emptyList(),
    val default: String? = null,
)

// ═════════════════════════════════════════════════════════════════════════
// Internal OpenAI-compatible wire models
// ═════════════════════════════════════════════════════════════════════════

// ── Chat Completions ────────────────────────────────────────────────────

@Serializable
internal data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val tools: List<JsonElement>? = null,
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    @SerialName("min_p")
    val minP: Double? = null,
    @SerialName("repeat_penalty")
    val repeatPenalty: Double? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val stream: Boolean = false,
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null,
    @SerialName("response_format")
    val responseFormat: JsonElement? = null,
)

@Serializable
internal data class StreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean = true,
)

@Serializable
internal data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
internal data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIFunctionCall,
)

@Serializable
internal data class OpenAIFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAIChatResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null,
)

@Serializable
internal data class OpenAIChoice(
    val index: Int,
    val message: OpenAIMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAIUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

// ── Streaming Chunks ────────────────────────────────────────────────────

@Serializable
internal data class OpenAIStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAIStreamChoice>? = null,
    val usage: OpenAIUsage? = null,
)

@Serializable
internal data class OpenAIStreamChoice(
    val index: Int = 0,
    val delta: OpenAIStreamDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAIStreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIStreamToolCall>? = null,
)

@Serializable
internal data class OpenAIStreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAIStreamFunctionCall? = null,
)

@Serializable
internal data class OpenAIStreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)

// ── Models ──────────────────────────────────────────────────────────────

@Serializable
internal data class OpenAIModelsResponse(
    val data: List<OpenAIModel>,
)

@Serializable
internal data class OpenAIModel(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null,
)

// ── Text Completions ────────────────────────────────────────────────────

@Serializable
internal data class OpenAICompletionRequest(
    val model: String,
    val prompt: String,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stop: List<String>? = null,
    val echo: Boolean? = null,
    val seed: Int? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class OpenAICompletionResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAICompletionChoice>,
    val usage: OpenAIUsage? = null,
)

@Serializable
internal data class OpenAICompletionChoice(
    val index: Int,
    val text: String,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAICompletionStreamChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAICompletionStreamChoice> = emptyList(),
)

@Serializable
internal data class OpenAICompletionStreamChoice(
    val index: Int = 0,
    val text: String? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

// ── Embeddings ──────────────────────────────────────────────────────────

@Serializable
internal data class OpenAIEmbeddingRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
internal data class OpenAIEmbeddingResponse(
    val model: String,
    val data: List<OpenAIEmbeddingData>,
    val usage: OpenAIEmbeddingUsage? = null,
)

@Serializable
internal data class OpenAIEmbeddingData(
    val index: Int,
    val embedding: List<Double>,
)

@Serializable
internal data class OpenAIEmbeddingUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

// ── Native v1 SSE helper models ─────────────────────────────────────────

@Serializable
internal data class NativeSSEChatStart(
    @SerialName("model_instance_id")
    val modelInstanceId: String,
)

@Serializable
internal data class NativeSSEContentDelta(
    val content: String,
)

@Serializable
internal data class NativeSSEToolCallStart(
    val tool: String,
    @SerialName("provider_info")
    val providerInfo: JsonElement? = null,
)

@Serializable
internal data class NativeSSEToolCallArguments(
    val arguments: JsonElement? = null,
)

@Serializable
internal data class NativeSSEToolCallSuccess(
    val output: String? = null,
)

@Serializable
internal data class NativeSSEToolCallFailure(
    val reason: String? = null,
    val metadata: JsonElement? = null,
)

@Serializable
internal data class NativeSSEError(
    val error: NativeSSEErrorDetail,
)

@Serializable
internal data class NativeSSEErrorDetail(
    val type: String? = null,
    val message: String? = null,
    val code: String? = null,
    val param: String? = null,
)

@Serializable
internal data class NativeSSEChatEnd(
    val result: NativeChatResponse,
)

@Serializable
internal data class NativeSSEModelLoadProgress(
    val progress: Double,
)

@Serializable
internal data class NativeSSEPromptProcessingProgress(
    val progress: Double,
)

// ═════════════════════════════════════════════════════════════════════════
// Conversion extensions
// ═════════════════════════════════════════════════════════════════════════

private fun ChatRequest.toOpenAIRequest(defaultModel: String?): OpenAIChatRequest {
    return OpenAIChatRequest(
        model = model.ifEmpty { defaultModel ?: error("No model specified") },
        messages = messages.map { it.toOpenAIMessage() },
        tools = tools?.takeIf { it.isNotEmpty() },
        toolChoice = toolChoice?.toJsonElement(),
        temperature = temperature,
        maxTokens = maxTokens,
    )
}

private fun Message.toOpenAIMessage(): OpenAIMessage {
    return OpenAIMessage(
        role = when (role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        },
        content = content,
        toolCalls = toolCalls?.map { tc ->
            OpenAIToolCall(
                id = tc.id,
                type = tc.type,
                function = OpenAIFunctionCall(
                    name = tc.function.name,
                    arguments = tc.function.arguments,
                ),
            )
        },
        toolCallId = toolCallId,
        name = name,
    )
}

private fun ToolChoice.toJsonElement(): JsonElement {
    return when (this) {
        is ToolChoice.Auto -> JsonPrimitive("auto")
        is ToolChoice.None -> JsonPrimitive("none")
        is ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(mapOf("name" to JsonPrimitive(function.name))),
            )
        )
    }
}

private fun OpenAIChatResponse.toChatResponse(): ChatResponse {
    return ChatResponse(
        id = id,
        model = model,
        choices = choices.map { choice ->
            Choice(
                index = choice.index,
                message = choice.message.toMessage(),
                finishReason = choice.finishReason,
            )
        },
        usage = usage?.toUsage(),
    )
}

private fun OpenAIUsage.toUsage(): Usage {
    return Usage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
    )
}

private fun OpenAIMessage.toMessage(): Message {
    return Message(
        role = when (role) {
            "system" -> Role.SYSTEM
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            "tool" -> Role.TOOL
            else -> Role.USER
        },
        content = content,
        toolCalls = toolCalls?.map { tc ->
            ToolCall(
                id = tc.id,
                type = tc.type,
                function = FunctionCall(
                    name = tc.function.name,
                    arguments = tc.function.arguments,
                ),
            )
        },
        toolCallId = toolCallId,
        name = name,
    )
}
