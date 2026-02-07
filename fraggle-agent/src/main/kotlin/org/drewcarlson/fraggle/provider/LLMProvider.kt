package org.drewcarlson.fraggle.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Abstract interface for LLM providers.
 * Implementations can wrap different APIs (OpenAI, Anthropic, LM Studio, Ollama, etc.)
 */
interface LLMProvider {
    /**
     * Send a chat completion request.
     */
    suspend fun chat(request: ChatRequest): ChatResponse

    /**
     * Check if this provider supports tool/function calling.
     */
    fun supportsTools(): Boolean

    /**
     * Get information about available models.
     */
    suspend fun listModels(): List<ModelInfo>

    /**
     * Get the provider name for logging/identification.
     */
    val name: String
}

/**
 * Chat completion request.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<JsonElement>? = null,
    val toolChoice: ToolChoice? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = false,
)

/**
 * Chat completion response.
 */
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
 * Message in a conversation.
 */
@Serializable
data class Message(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(content: String) = Message(role = Role.SYSTEM, content = content)
        fun user(content: String) = Message(role = Role.USER, content = content)
        fun assistant(content: String) = Message(role = Role.ASSISTANT, content = content)
        fun assistant(toolCalls: List<ToolCall>) = Message(role = Role.ASSISTANT, toolCalls = toolCalls)
        fun tool(toolCallId: String, content: String) = Message(
            role = Role.TOOL,
            content = content,
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
 * Tool call made by the assistant.
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

/**
 * Tool choice specification.
 */
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
data class ToolChoiceFunction(
    val name: String,
)

/**
 * Information about an available model.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String? = null,
    val contextLength: Int? = null,
    val owned_by: String? = null,
)

/**
 * Exception thrown when LLM provider encounters an error.
 */
class LLMProviderException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
