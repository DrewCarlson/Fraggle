package fraggle.agent.loop

import fraggle.agent.ReasoningContentFilter
import fraggle.agent.event.StreamDelta
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.TokenUsage
import fraggle.agent.message.ToolCall
import fraggle.provider.ChatRequest
import fraggle.provider.ChatStreamEvent
import fraggle.provider.LMStudioProvider
import fraggle.provider.Message
import fraggle.provider.FunctionCall
import kotlinx.serialization.json.JsonElement

/**
 * LLM bridge backed by [LMStudioProvider].
 * Supports both blocking and streaming calls using the OpenAI-compatible API.
 */
class ProviderLlmBridge(
    private val provider: LMStudioProvider,
    private val model: String = "",
    private val temperature: Double? = null,
    private val maxTokens: Int? = null,
) : StreamingLlmBridge {

    override suspend fun call(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
    ): AgentMessage.Assistant {
        val request = buildChatRequest(systemPrompt, messages, tools)

        return try {
            val response = provider.chat(request)
            val choice = response.choices.firstOrNull()
            val message = choice?.message

            val content = message?.content
                ?.let { ReasoningContentFilter.strip(it) }
                ?: ""

            val toolCalls = message?.toolCalls?.map { tc ->
                ToolCall(
                    id = tc.id,
                    name = tc.function.name,
                    arguments = tc.function.arguments,
                )
            } ?: emptyList()

            val stopReason = when (choice?.finishReason) {
                "tool_calls" -> StopReason.STOP
                "stop" -> StopReason.STOP
                "length" -> StopReason.STOP
                else -> StopReason.STOP
            }

            AgentMessage.Assistant(
                content = if (content.isNotBlank()) listOf(ContentPart.Text(content)) else emptyList(),
                toolCalls = toolCalls,
                stopReason = stopReason,
                usage = response.usage?.let {
                    TokenUsage(it.promptTokens, it.completionTokens, it.totalTokens)
                },
            )
        } catch (e: Exception) {
            AgentMessage.Assistant(
                content = listOf(ContentPart.Text("LLM error: ${e.message}")),
                stopReason = StopReason.ERROR,
                errorMessage = e.message,
            )
        }
    }

    override suspend fun callStreaming(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onDelta: suspend (StreamDelta, AgentMessage.Assistant) -> Unit,
    ): AgentMessage.Assistant {
        val request = buildChatRequest(systemPrompt, messages, tools)

        return try {
            val textBuilder = StringBuilder()
            var toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
            var usage: TokenUsage? = null
            var finishReason: String? = null

            provider.chatStream(request).collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        textBuilder.append(event.text)
                        val partial = buildPartialAssistant(textBuilder, toolCallBuilders)
                        onDelta(StreamDelta.TextDelta(event.text), partial)
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        // Reasoning is filtered out of final content
                        val partial = buildPartialAssistant(textBuilder, toolCallBuilders)
                        onDelta(StreamDelta.ThinkingDelta(event.text), partial)
                    }
                    is ChatStreamEvent.ToolCallDelta -> {
                        val builder = toolCallBuilders.getOrPut(event.index) {
                            ToolCallBuilder()
                        }
                        event.id?.let { builder.id = it }
                        event.functionName?.let { builder.name = it }
                        event.argumentsDelta?.let { builder.arguments.append(it) }

                        val partial = buildPartialAssistant(textBuilder, toolCallBuilders)
                        val delta = StreamDelta.ToolCallDelta(
                            toolCallId = builder.id ?: "pending-${event.index}",
                            argumentsDelta = event.argumentsDelta ?: "",
                        )
                        onDelta(delta, partial)
                    }
                    is ChatStreamEvent.UsageInfo -> {
                        usage = TokenUsage(event.promptTokens, event.completionTokens, event.totalTokens)
                    }
                    is ChatStreamEvent.FinishReason -> {
                        finishReason = event.reason
                    }
                    is ChatStreamEvent.Done -> { /* handled below */ }
                }
            }

            val content = ReasoningContentFilter.strip(textBuilder.toString())
            val toolCalls = toolCallBuilders.values
                .filter { it.id != null && it.name != null }
                .map { ToolCall(it.id!!, it.name!!, it.arguments.toString()) }

            AgentMessage.Assistant(
                content = if (content.isNotBlank()) listOf(ContentPart.Text(content)) else emptyList(),
                toolCalls = toolCalls,
                stopReason = StopReason.STOP,
                usage = usage,
            )
        } catch (e: Exception) {
            AgentMessage.Assistant(
                content = listOf(ContentPart.Text("LLM error: ${e.message}")),
                stopReason = StopReason.ERROR,
                errorMessage = e.message,
            )
        }
    }

    private fun buildChatRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
    ): ChatRequest {
        val providerMessages = buildList {
            add(Message.system(systemPrompt))
            for (msg in messages) {
                when (msg) {
                    is AgentMessage.User -> {
                        val text = msg.content.filterIsInstance<ContentPart.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotBlank()) add(Message.user(text))
                    }
                    is AgentMessage.Assistant -> {
                        if (msg.toolCalls.isNotEmpty()) {
                            add(Message.assistant(msg.toolCalls.map { tc ->
                                fraggle.provider.ToolCall(
                                    id = tc.id,
                                    function = FunctionCall(tc.name, tc.arguments),
                                )
                            }))
                        } else {
                            val text = msg.textContent
                            if (text.isNotBlank()) add(Message.assistant(text))
                        }
                    }
                    is AgentMessage.ToolResult -> {
                        add(Message.tool(msg.toolCallId, msg.textContent))
                    }
                    is AgentMessage.Platform -> { /* skip */ }
                }
            }
        }

        return ChatRequest(
            model = model,
            messages = providerMessages,
            tools = if (tools.isNotEmpty()) tools.map { buildToolJson(it) } else null,
            temperature = temperature,
            maxTokens = maxTokens,
        )
    }

    private fun buildPartialAssistant(
        textBuilder: StringBuilder,
        toolCallBuilders: Map<Int, ToolCallBuilder>,
    ): AgentMessage.Assistant {
        val text = textBuilder.toString()
        val toolCalls = toolCallBuilders.values
            .filter { it.id != null }
            .map { ToolCall(it.id!!, it.name ?: "", it.arguments.toString()) }

        return AgentMessage.Assistant(
            content = if (text.isNotBlank()) listOf(ContentPart.Text(text)) else emptyList(),
            toolCalls = toolCalls,
        )
    }

    private fun buildToolJson(tool: ToolDefinition): JsonElement {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val params = json.parseToJsonElement(tool.parametersSchema)
        return kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("function"))
            putJsonObject("function") {
                put("name", kotlinx.serialization.json.JsonPrimitive(tool.name))
                put("description", kotlinx.serialization.json.JsonPrimitive(tool.description))
                put("parameters", params)
            }
        }
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, kotlinx.serialization.json.buildJsonObject(block))
}
