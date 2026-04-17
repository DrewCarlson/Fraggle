package fraggle.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Abstract interface for LLM providers.
 * Implementations wrap different APIs (OpenAI, Anthropic, Gemini, LM Studio, ...).
 */
interface LLMProvider {

    /** Provider name for logging/identification. */
    val name: String

    /** Whether this provider supports tool/function calling. */
    fun supportsTools(): Boolean

    /** List available models. */
    suspend fun listModels(): List<ModelInfo>

    /**
     * Streaming chat completion. Returns a cold [Flow] of [ChatEvent]s.
     *
     * Contract:
     * - The flow emits [ChatEvent.Start] first.
     * - Errors and cancellation *after* [ChatEvent.Start] must be encoded as
     *   [ChatEvent.Error], never thrown from the flow.
     * - Errors *before* the first emission (e.g. invalid request construction)
     *   may be thrown as [LLMProviderException].
     */
    fun stream(request: ChatRequest): Flow<ChatEvent>

    /**
     * Non-streaming chat completion. The default implementation reduces
     * [stream] into a single [ChatResponse]. Providers may override this
     * to call a native non-streaming endpoint directly.
     */
    suspend fun chat(request: ChatRequest): ChatResponse {
        val events = stream(request).toList()
        return reduceEventsToResponse(request.model, events)
    }
}

/** Chat completion request. */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<JsonElement>? = null,
    val toolChoice: ToolChoice? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Reasoning/thinking level. Providers that don't support it ignore this. */
    val thinking: ThinkingLevel? = null,
    /**
     * Advisory: whether the caller *prefers* streaming. Providers are free
     * to stream regardless via [LLMProvider.stream]; this flag is forwarded
     * to wire requests only when a provider exposes both modes.
     */
    val stream: Boolean = false,
)

/**
 * Requested reasoning/thinking intensity. Maps to provider-specific knobs:
 * - OpenAI: `reasoning_effort` (minimal/low/medium/high)
 * - LM Studio: `reasoning` (off/low/medium/high/on) — see [asLmStudioReasoning]
 * - Anthropic: thinking budget tokens
 * - Gemini: thinking config
 *
 * `OFF` and `ON` are LM-Studio-specific levels exposed for the per-session
 * `/think` slash command. On providers that don't support them they degrade
 * to the nearest sensible value (OFF → null-send, ON → HIGH).
 */
@Serializable
enum class ThinkingLevel {
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    ON,
}

/**
 * Map a [ThinkingLevel] to the string LM Studio's `reasoning` field expects.
 * Returns null when the level doesn't correspond to a meaningful LM Studio
 * value (currently only [ThinkingLevel.MINIMAL], which is OpenAI-specific).
 */
fun ThinkingLevel.asLmStudioReasoning(): String? = when (this) {
    ThinkingLevel.OFF -> "off"
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.ON -> "on"
    ThinkingLevel.MINIMAL -> "low" // Closest LM Studio equivalent.
}

/**
 * Parse a user-facing string ("off", "low", "medium", "high", "on") into a
 * [ThinkingLevel]. Case-insensitive. Returns null on empty input or the
 * literal "default" / "auto" — the caller should interpret null as
 * "clear the override and use the model's default".
 */
fun thinkingLevelFromUserInput(raw: String): ThinkingLevel? =
    when (raw.trim().lowercase()) {
        "", "default", "auto" -> null
        "off" -> ThinkingLevel.OFF
        "minimal" -> ThinkingLevel.MINIMAL
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "on" -> ThinkingLevel.ON
        else -> error("unknown thinking level: $raw (expected off/low/medium/high/on or default)")
    }

/** Chat completion response. */
@Serializable
data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    val finishReason: String? = null,
)

@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)

/**
 * A message in a conversation.
 *
 * Content is stored as an ordered list of [ContentBlock]s. The legacy
 * [content] and [toolCalls] convenience accessors remain as derived views
 * so existing call sites continue to work during the provider refactor.
 */
@Serializable
data class Message(
    val role: Role,
    val blocks: List<ContentBlock> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
) {
    /** Concatenation of all [ContentBlock.Text] blocks, or null if none. */
    val content: String?
        get() = blocks.asSequence()
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("") { it.text }
            .takeIf { it.isNotEmpty() || blocks.any { b -> b is ContentBlock.Text } }

    /** Derived view of assistant tool calls, or null if none. */
    val toolCalls: List<ToolCall>?
        get() = blocks.asSequence()
            .filterIsInstance<ContentBlock.ToolCallBlock>()
            .map { tc ->
                ToolCall(id = tc.id, function = FunctionCall(tc.name, tc.arguments))
            }
            .toList()
            .takeIf { it.isNotEmpty() }

    companion object {
        fun system(content: String) = Message(
            role = Role.SYSTEM,
            blocks = listOf(ContentBlock.Text(content)),
        )

        fun user(content: String) = Message(
            role = Role.USER,
            blocks = listOf(ContentBlock.Text(content)),
        )

        fun assistant(content: String) = Message(
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Text(content)),
        )

        fun assistant(toolCalls: List<ToolCall>) = Message(
            role = Role.ASSISTANT,
            blocks = toolCalls.map { tc ->
                ContentBlock.ToolCallBlock(
                    id = tc.id,
                    name = tc.function.name,
                    arguments = tc.function.arguments,
                )
            },
        )

        fun tool(toolCallId: String, content: String) = Message(
            role = Role.TOOL,
            blocks = listOf(ContentBlock.Text(content)),
            toolCallId = toolCallId,
        )
    }
}

@Serializable
enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * Wire-shaped tool call. Kept as a distinct type because OpenAI-compatible
 * backends exchange it directly; internally [Message] stores tool calls as
 * [ContentBlock.ToolCallBlock] entries.
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
sealed class ToolChoice {
    @Serializable
    data object Auto : ToolChoice()

    @Serializable
    data object None : ToolChoice()

    @Serializable
    data object Required : ToolChoice()

    @Serializable
    data class Specific(val function: ToolChoiceFunction) : ToolChoice()
}

@Serializable
data class ToolChoiceFunction(val name: String)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String? = null,
    val contextLength: Int? = null,
    val owned_by: String? = null,
)

/** Exception thrown when an LLM provider encounters an unrecoverable error. */
class LLMProviderException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

// ─────────────────────────────────────────────────────────────────────────
// Event-stream reducer
// ─────────────────────────────────────────────────────────────────────────

/**
 * Collapse a completed list of [ChatEvent]s into a [ChatResponse]. Used by
 * the default [LLMProvider.chat] implementation. Throws [LLMProviderException]
 * if the stream terminated with [ChatEvent.Error].
 */
internal fun reduceEventsToResponse(
    requestedModel: String,
    events: List<ChatEvent>,
): ChatResponse {
    var terminal: ChatEvent? = null
    for (e in events) {
        if (e is ChatEvent.Done || e is ChatEvent.Error) {
            terminal = e
            break
        }
    }

    when (val t = terminal) {
        is ChatEvent.Done -> {
            val finishReason = when (t.reason) {
                StopReason.STOP -> "stop"
                StopReason.LENGTH -> "length"
                StopReason.TOOL_USE -> "tool_calls"
                StopReason.ERROR -> "error"
                StopReason.ABORTED -> "aborted"
            }
            return ChatResponse(
                id = "evt-${t.message.hashCode()}",
                model = requestedModel,
                choices = listOf(
                    Choice(index = 0, message = t.message, finishReason = finishReason),
                ),
                usage = t.usage,
            )
        }
        is ChatEvent.Error -> {
            throw LLMProviderException(
                message = t.errorMessage,
                cause = null,
            )
        }
        else -> {
            throw LLMProviderException(
                message = "Stream ended without a terminal Done/Error event",
            )
        }
    }
}
