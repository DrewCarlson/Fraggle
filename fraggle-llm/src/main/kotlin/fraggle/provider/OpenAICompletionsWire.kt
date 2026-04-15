package fraggle.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ═════════════════════════════════════════════════════════════════════════
// OpenAI-compatible chat completions wire models
//
// Shared between LMStudioProvider and OpenAICompletionsProvider. All types
// are `internal` to `fraggle.provider` — consumers outside this package
// should use the neutral [ChatRequest] / [ChatResponse] / [Message] types
// defined in LLMProvider.kt.
// ═════════════════════════════════════════════════════════════════════════

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
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
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
    val store: Boolean? = null,
    val stream: Boolean = false,
    @SerialName("stream_options")
    val streamOptions: OpenAIStreamOptions? = null,
    @SerialName("response_format")
    val responseFormat: JsonElement? = null,
)

@Serializable
internal data class OpenAIStreamOptions(
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

// ── Streaming chunks ────────────────────────────────────────────────────

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
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
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

// ── Models list ─────────────────────────────────────────────────────────

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

// ═════════════════════════════════════════════════════════════════════════
// Neutral ⇄ OpenAI wire conversions
// ═════════════════════════════════════════════════════════════════════════

/**
 * Build an OpenAI chat completions request from a neutral [ChatRequest].
 *
 * [defaultModel] is used when [ChatRequest.model] is blank.
 * [systemRole] lets reasoning-model providers substitute `"developer"` for
 * `"system"` when the target model requires it.
 * [maxTokensField] selects between `max_tokens` (legacy / non-standard) and
 * `max_completion_tokens` (OpenAI o-series and newer).
 * [reasoningEffort] is the already-mapped effort string (or null to omit).
 * [supportsStore] adds `store: false` when true (OpenAI proper).
 * [suppressTemperature] drops the temperature field regardless of the
 * incoming value — o-series models reject anything other than the default.
 */
internal fun ChatRequest.toOpenAIRequest(
    defaultModel: String?,
    systemRole: String = "system",
    maxTokensField: OpenAIMaxTokensField = OpenAIMaxTokensField.MAX_TOKENS,
    reasoningEffort: String? = null,
    supportsStore: Boolean = false,
    suppressTemperature: Boolean = false,
): OpenAIChatRequest {
    val maxTokens = if (maxTokensField == OpenAIMaxTokensField.MAX_TOKENS) this.maxTokens else null
    val maxCompletionTokens = if (maxTokensField == OpenAIMaxTokensField.MAX_COMPLETION_TOKENS) this.maxTokens else null

    return OpenAIChatRequest(
        model = model.ifEmpty { defaultModel ?: error("No model specified") },
        messages = messages.map { it.toOpenAIMessage(systemRole) },
        tools = tools?.takeIf { it.isNotEmpty() },
        toolChoice = toolChoice?.toJsonElement(),
        temperature = temperature.takeUnless { suppressTemperature },
        maxTokens = maxTokens,
        maxCompletionTokens = maxCompletionTokens,
        reasoningEffort = reasoningEffort,
        store = if (supportsStore) false else null,
    )
}

internal enum class OpenAIMaxTokensField {
    MAX_TOKENS,
    MAX_COMPLETION_TOKENS,
}

internal fun Message.toOpenAIMessage(systemRole: String = "system"): OpenAIMessage {
    // Text blocks concatenate into the message content string; tool call
    // blocks become the tool_calls array on assistant messages.
    val textContent = blocks.asSequence()
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("") { it.text }
        .takeIf { it.isNotEmpty() }

    val toolCallBlocks = blocks.filterIsInstance<ContentBlock.ToolCallBlock>()
        .takeIf { it.isNotEmpty() }
        ?.map { tc ->
            OpenAIToolCall(
                id = tc.id,
                function = OpenAIFunctionCall(name = tc.name, arguments = tc.arguments),
            )
        }

    return OpenAIMessage(
        role = when (role) {
            Role.SYSTEM -> systemRole
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        },
        content = textContent,
        toolCalls = toolCallBlocks,
        toolCallId = toolCallId,
        name = name,
    )
}

internal fun ToolChoice.toJsonElement(): JsonElement = when (this) {
    is ToolChoice.Auto -> JsonPrimitive("auto")
    is ToolChoice.None -> JsonPrimitive("none")
    is ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Specific -> JsonObject(
        mapOf(
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf("name" to JsonPrimitive(function.name))),
        ),
    )
}

internal fun OpenAIChatResponse.toChatResponse(): ChatResponse = ChatResponse(
    id = id,
    model = model,
    choices = choices.map { choice ->
        Choice(
            index = choice.index,
            message = choice.message.toNeutralMessage(),
            finishReason = choice.finishReason,
        )
    },
    usage = usage?.toNeutralUsage(),
)

internal fun OpenAIUsage.toNeutralUsage(): Usage = Usage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
)

internal fun OpenAIMessage.toNeutralMessage(): Message {
    val textBlocks = content?.takeIf { it.isNotEmpty() }?.let {
        listOf(ContentBlock.Text(it))
    } ?: emptyList()
    val toolCallBlocks = toolCalls.orEmpty().map { tc ->
        ContentBlock.ToolCallBlock(
            id = tc.id,
            name = tc.function.name,
            arguments = tc.function.arguments,
        )
    }
    return Message(
        role = when (role) {
            "system" -> Role.SYSTEM
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            "tool" -> Role.TOOL
            "developer" -> Role.SYSTEM
            else -> Role.USER
        },
        blocks = textBlocks + toolCallBlocks,
        toolCallId = toolCallId,
        name = name,
    )
}
