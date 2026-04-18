package fraggle.agent.loop

import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.StopReason
import fraggle.agent.message.ToolCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

typealias EventSink = suspend (AgentEvent) -> Unit

/**
 * Main agent loop. Owns the turn lifecycle:
 * LLM call -> tool execution -> repeat while tools/steering -> check follow-ups.
 *
 * @param prompts Initial messages to process
 * @param systemPrompt System prompt for LLM calls
 * @param messages Existing conversation history
 * @param chatId Chat ID for tool supervision context
 * @param config Loop configuration
 * @param emit Event sink for lifecycle events
 * @return All new messages produced during this run
 */
suspend fun runAgentLoop(
    prompts: List<AgentMessage>,
    systemPrompt: String,
    messages: List<AgentMessage>,
    chatId: String,
    config: AgentLoopConfig,
    emit: EventSink,
): List<AgentMessage> {
    val allMessages = messages.toMutableList()
    allMessages.addAll(prompts)
    val newMessages = prompts.toMutableList()

    emit(AgentEvent.AgentStart(systemPrompt))
    emit(AgentEvent.TurnStart)
    for (prompt in prompts) {
        emit(AgentEvent.MessageRecord(prompt))
    }

    try {
        runLoop(allMessages, newMessages, systemPrompt, chatId, config, emit)
    } finally {
        withContext(NonCancellable) {
            emit(AgentEvent.AgentEnd(newMessages.toList()))
        }
    }
    return newMessages
}

/**
 * Continue from existing context (retry). Last message must be user or tool-result.
 */
suspend fun runAgentLoopContinue(
    systemPrompt: String,
    messages: List<AgentMessage>,
    chatId: String,
    config: AgentLoopConfig,
    emit: EventSink,
): List<AgentMessage> {
    require(messages.isNotEmpty()) { "Cannot continue: no messages in context" }
    require(messages.last() !is AgentMessage.Assistant) {
        "Cannot continue from assistant message"
    }

    val allMessages = messages.toMutableList()
    val newMessages = mutableListOf<AgentMessage>()

    emit(AgentEvent.AgentStart(systemPrompt))
    emit(AgentEvent.TurnStart)

    try {
        runLoop(allMessages, newMessages, systemPrompt, chatId, config, emit)
    } finally {
        withContext(NonCancellable) {
            emit(AgentEvent.AgentEnd(newMessages.toList()))
        }
    }
    return newMessages
}

private suspend fun runLoop(
    messages: MutableList<AgentMessage>,
    newMessages: MutableList<AgentMessage>,
    systemPrompt: String,
    chatId: String,
    config: AgentLoopConfig,
    emit: EventSink,
) {
    var firstTurn = true
    var pendingMessages = config.getSteeringMessages()
    var iterationCount = 0

    while (true) {
        coroutineContext.ensureActive()

        var hasMoreToolCalls = true

        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            coroutineContext.ensureActive()

            if (iterationCount >= config.maxIterations) {
                val errorMsg = AgentMessage.Assistant(
                    content = listOf(fraggle.agent.message.ContentPart.Text("Maximum iterations ($iterationCount) reached.")),
                    stopReason = StopReason.ERROR,
                    errorMessage = "Maximum iterations reached",
                )
                messages.add(errorMsg)
                newMessages.add(errorMsg)
                emit(AgentEvent.MessageRecord(errorMsg))
                emit(AgentEvent.TurnEnd(errorMsg, emptyList()))
                return
            }

            if (!firstTurn) emit(AgentEvent.TurnStart) else firstTurn = false

            // Inject pending steering messages
            for (msg in pendingMessages) {
                emit(AgentEvent.MessageRecord(msg))
                messages.add(msg)
                newMessages.add(msg)
            }
            pendingMessages = emptyList()

            // Get tool definitions
            val tools = config.toolExecutor?.getToolDefinitions() ?: emptyList()

            // Apply context transform and call LLM (streaming if available)
            val llmMessages = config.contextTransform?.transform(messages.toList()) ?: messages.toList()
            val assistantMsg = callLlm(config.llmBridge, systemPrompt, llmMessages, tools, emit)
            messages.add(assistantMsg)
            newMessages.add(assistantMsg)

            if (assistantMsg.stopReason == StopReason.ERROR ||
                assistantMsg.stopReason == StopReason.ABORTED
            ) {
                emit(AgentEvent.TurnEnd(assistantMsg, emptyList()))
                return
            }

            // Execute tool calls
            val toolCalls = assistantMsg.toolCalls
            hasMoreToolCalls = toolCalls.isNotEmpty()

            val toolResults = if (hasMoreToolCalls && config.toolExecutor != null) {
                iterationCount++
                val results = executeToolCalls(
                    toolCalls, assistantMsg, chatId, config, emit,
                )
                for (result in results) {
                    messages.add(result)
                    newMessages.add(result)
                }
                results
            } else {
                if (hasMoreToolCalls) {
                    // Tool calls requested but no executor — treat as no-op
                    hasMoreToolCalls = false
                }
                emptyList()
            }

            emit(AgentEvent.TurnEnd(assistantMsg, toolResults))
            pendingMessages = config.getSteeringMessages()
        }

        // Check for follow-up messages
        val followUps = config.getFollowUpMessages()
        if (followUps.isNotEmpty()) {
            pendingMessages = followUps
            firstTurn = false
            continue
        }

        break
    }
}

/**
 * Call the LLM, using streaming if the bridge supports it.
 * Emits MessageStart/MessageUpdate/MessageEnd events appropriately.
 */
private suspend fun callLlm(
    bridge: LlmBridge,
    systemPrompt: String,
    messages: List<AgentMessage>,
    tools: List<ToolDefinition>,
    emit: EventSink,
): AgentMessage.Assistant {
    return if (bridge is StreamingLlmBridge) {
        // Streaming path: emit updates as deltas arrive
        val emptyAssistant = AgentMessage.Assistant()
        emit(AgentEvent.MessageStart(emptyAssistant))

        val finalMsg = bridge.callStreaming(systemPrompt, messages, tools) { delta, partial ->
            emit(AgentEvent.MessageUpdate(partial, delta))
        }

        emit(AgentEvent.MessageEnd(finalMsg))
        finalMsg
    } else {
        // Non-streaming path: emit start/end around the full response
        val assistantMsg = bridge.call(systemPrompt, messages, tools)
        emit(AgentEvent.MessageStart(assistantMsg))
        emit(AgentEvent.MessageEnd(assistantMsg))
        assistantMsg
    }
}

private suspend fun executeToolCalls(
    toolCalls: List<ToolCall>,
    assistantMessage: AgentMessage.Assistant,
    chatId: String,
    config: AgentLoopConfig,
    emit: EventSink,
): List<AgentMessage.ToolResult> {
    val executor = config.toolExecutor ?: return emptyList()

    return when (config.toolExecution) {
        ToolExecutionMode.SEQUENTIAL -> {
            toolCalls.map { toolCall ->
                executeSingleToolCall(toolCall, assistantMessage, chatId, executor, config, emit)
            }
        }
        ToolExecutionMode.PARALLEL -> {
            coroutineScope {
                toolCalls.map { toolCall ->
                    async {
                        executeSingleToolCall(toolCall, assistantMessage, chatId, executor, config, emit)
                    }
                }.awaitAll()
            }
        }
    }
}

private suspend fun executeSingleToolCall(
    toolCall: ToolCall,
    assistantMessage: AgentMessage.Assistant,
    chatId: String,
    executor: ToolCallExecutor,
    config: AgentLoopConfig,
    emit: EventSink,
): AgentMessage.ToolResult {
    emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))

    val result = try {
        executor.execute(toolCall, chatId)
    } catch (e: Exception) {
        ToolCallResult(content = "Error: ${e.message}", isError = true)
    }

    var content = result.content
    var isError = result.isError

    // Apply afterToolCall hook
    config.afterToolCall?.let { hook ->
        val override = hook(AfterToolCallContext(
            assistantMessage = assistantMessage,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            result = content,
            isError = isError,
        ))
        if (override != null) {
            content = override.content ?: content
            isError = override.isError ?: isError
        }
    }

    emit(AgentEvent.ToolExecutionEnd(toolCall.id, toolCall.name, content, isError))

    val toolResult = AgentMessage.ToolResult(
        toolCallId = toolCall.id,
        toolName = toolCall.name,
        text = content,
        isError = isError,
    )
    emit(AgentEvent.MessageRecord(toolResult))

    return toolResult
}
