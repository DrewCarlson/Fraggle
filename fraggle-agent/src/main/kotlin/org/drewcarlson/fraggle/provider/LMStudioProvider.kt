package org.drewcarlson.fraggle.provider

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LLM Provider implementation for LM Studio.
 * Uses the OpenAI-compatible API that LM Studio provides.
 */
class LMStudioProvider(
    private val baseUrl: String = "http://localhost:1234/v1",
    private val defaultModel: String? = null,
    private val httpClient: HttpClient,
) : LLMProvider {

    override val name: String = "LM Studio"

    override fun supportsTools(): Boolean = true

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val apiRequest = request.toApiRequest(defaultModel)

        val response = try {
            httpClient.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(apiRequest)
            }
        } catch (e: Exception) {
            throw LLMProviderException("Failed to connect to LM Studio: ${e.message}", cause = e)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw LLMProviderException(
                "LM Studio API error: ${response.status.value} - $errorBody",
                statusCode = response.status.value,
            )
        }

        val apiResponse: OpenAIChatResponse = response.body()
        return apiResponse.toChatResponse()
    }

    override suspend fun listModels(): List<ModelInfo> {
        val response = try {
            httpClient.get("$baseUrl/models")
        } catch (e: Exception) {
            throw LLMProviderException("Failed to list models: ${e.message}", cause = e)
        }

        if (!response.status.isSuccess()) {
            throw LLMProviderException(
                "Failed to list models: ${response.status.value}",
                statusCode = response.status.value,
            )
        }

        val modelsResponse: OpenAIModelsResponse = response.body()
        return modelsResponse.data.map { model ->
            ModelInfo(
                id = model.id,
                name = model.id,
                owned_by = model.ownedBy,
            )
        }
    }

}

// OpenAI API request/response models

@Serializable
private data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val tools: List<JsonElement>? = null,
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
private data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
private data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIFunctionCall,
)

@Serializable
private data class OpenAIFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
private data class OpenAIChatResponse(
    val id: String,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null,
)

@Serializable
private data class OpenAIChoice(
    val index: Int,
    val message: OpenAIMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class OpenAIUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

@Serializable
private data class OpenAIModelsResponse(
    val data: List<OpenAIModel>,
)

@Serializable
private data class OpenAIModel(
    val id: String,
    @SerialName("owned_by")
    val ownedBy: String? = null,
)

// Extension functions for conversion

private fun ChatRequest.toApiRequest(defaultModel: String?): OpenAIChatRequest {
    return OpenAIChatRequest(
        model = model.ifEmpty { defaultModel ?: error("No model specified") },
        messages = messages.map { it.toOpenAIMessage() },
        tools = tools?.takeIf { it.isNotEmpty() },
        toolChoice = toolChoice?.toJsonElement(),
        temperature = temperature,
        maxTokens = maxTokens,
        stream = stream,
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
        usage = usage?.let { u ->
            Usage(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                totalTokens = u.totalTokens,
            )
        },
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
