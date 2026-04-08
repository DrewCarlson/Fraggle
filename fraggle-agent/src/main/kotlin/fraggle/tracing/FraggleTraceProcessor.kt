package fraggle.tracing

import ai.koog.agents.core.feature.message.FeatureMessage
import ai.koog.agents.core.feature.message.FeatureMessageProcessor
import ai.koog.agents.core.feature.model.FeatureStringMessage
import ai.koog.agents.core.feature.model.events.*
import ai.koog.prompt.message.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import fraggle.agent.ToolExecutionContext
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import fraggle.models.TraceEventRecord
import fraggle.models.TracingLevel
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

class FraggleTraceProcessor(
    private val level: TracingLevel,
    private val traceStore: TraceStore,
    private val eventBus: EventBus,
) : FeatureMessageProcessor() {
    private val logger = LoggerFactory.getLogger(FraggleTraceProcessor::class.java)

    private val _isOpen = MutableStateFlow(true)
    override val isOpen: StateFlow<Boolean> = _isOpen

    override suspend fun processMessage(message: FeatureMessage) {
        when (message) {
            is DefinedFeatureEvent -> processEvent(message)
            is FeatureStringMessage -> {
                // String messages don't carry structured data, skip
            }
            else -> {
                logger.debug("Unhandled trace message type: {}", message::class.simpleName)
            }
        }
    }

    private suspend fun processEvent(event: DefinedFeatureEvent) {
        val sessionId = extractRunId(event) ?: event.executionInfo.partName
        val now = Clock.System.now()

        // Create session on agent start, complete on agent finish/error
        when (event) {
            is AgentStartingEvent -> {
                val chatId = ToolExecutionContext.current()?.chatId ?: "unknown"
                traceStore.startSession(sessionId, chatId)
                eventBus.emit(
                    FraggleEvent.TraceSessionStarted(
                        timestamp = now,
                        sessionId = sessionId,
                        chatId = chatId,
                    )
                )
            }
            is AgentCompletedEvent -> {
                traceStore.completeSession(sessionId, "completed")
            }
            is AgentExecutionFailedEvent -> {
                traceStore.completeSession(sessionId, "error")
            }
            else -> {}
        }

        val (eventType, phase, data, durationMs, detail) = extractEventInfo(event)

        val record = TraceEventRecord(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestamp = Instant.fromEpochMilliseconds(event.timestamp),
            eventType = eventType,
            phase = phase,
            data = data,
            durationMs = durationMs,
            detail = detail,
        )

        traceStore.addEvent(sessionId, record)

        eventBus.emit(
            FraggleEvent.TraceEvent(
                timestamp = now,
                sessionId = sessionId,
                event = record,
            )
        )
    }

    override suspend fun close() {
        _isOpen.value = false
    }

    private fun extractRunId(event: DefinedFeatureEvent): String? {
        return when (event) {
            is AgentStartingEvent -> event.runId
            is AgentCompletedEvent -> event.runId
            is AgentExecutionFailedEvent -> event.runId
            is LLMCallStartingEvent -> event.runId
            is LLMCallCompletedEvent -> event.runId
            is ToolCallStartingEvent -> event.runId
            is ToolCallCompletedEvent -> event.runId
            is ToolCallFailedEvent -> event.runId
            is ToolValidationFailedEvent -> event.runId
            is StrategyCompletedEvent -> event.runId
            is LLMStreamingStartingEvent -> event.runId
            is LLMStreamingCompletedEvent -> event.runId
            is LLMStreamingFailedEvent -> event.runId
            is LLMStreamingFrameReceivedEvent -> event.runId
            else -> null
        }
    }

    private fun extractEventInfo(event: DefinedFeatureEvent): EventInfo {
        return when (event) {
            // Agent lifecycle
            is AgentStartingEvent -> EventInfo(
                eventType = "agent",
                phase = "starting",
                data = buildData("agentId" to event.agentId, "runId" to event.runId),
            )
            is AgentCompletedEvent -> EventInfo(
                eventType = "agent",
                phase = "finished",
                data = buildData(
                    "agentId" to event.agentId,
                    "runId" to event.runId,
                    "result" to event.result?.take(200),
                ),
            )
            is AgentExecutionFailedEvent -> EventInfo(
                eventType = "agent",
                phase = "error",
                data = buildData(
                    "agentId" to event.agentId,
                    "runId" to event.runId,
                    "error" to event.error?.message,
                ),
            )
            is AgentClosingEvent -> EventInfo(
                eventType = "agent",
                phase = "closing",
                data = buildData("agentId" to event.agentId),
            )

            // LLM calls
            is LLMCallStartingEvent -> EventInfo(
                eventType = "llm_call",
                phase = "starting",
                data = buildData(
                    "model" to event.model.toString(),
                    "tools" to event.tools.takeIf { it.isNotEmpty() }?.joinToString(", "),
                ),
                detail = if (level == TracingLevel.FULL) buildLlmRequestDetail(event) else null,
            )
            is LLMCallCompletedEvent -> EventInfo(
                eventType = "llm_call",
                phase = "finished",
                data = buildData(
                    "model" to event.model.toString(),
                    "responseCount" to event.responses.size.toString(),
                ),
                detail = if (level == TracingLevel.FULL) buildLlmResponseDetail(event) else null,
            )

            // Tool calls
            is ToolCallStartingEvent -> EventInfo(
                eventType = "tool_call",
                phase = "starting",
                data = buildData(
                    "toolName" to event.toolName,
                    "toolCallId" to event.toolCallId,
                    "args" to event.toolArgs.toString().take(500),
                ),
            )
            is ToolCallCompletedEvent -> EventInfo(
                eventType = "tool_call",
                phase = "finished",
                data = buildData(
                    "toolName" to event.toolName,
                    "toolCallId" to event.toolCallId,
                    "result" to event.result?.toString()?.take(500),
                ),
            )
            is ToolCallFailedEvent -> EventInfo(
                eventType = "tool_call",
                phase = "error",
                data = buildData(
                    "toolName" to event.toolName,
                    "toolCallId" to event.toolCallId,
                    "error" to event.error?.message,
                ),
            )
            is ToolValidationFailedEvent -> EventInfo(
                eventType = "tool_call",
                phase = "validation_error",
                data = buildData(
                    "toolName" to event.toolName,
                    "toolCallId" to event.toolCallId,
                    "message" to event.message,
                ),
            )

            // Node execution
            is NodeExecutionStartingEvent -> EventInfo(
                eventType = "node",
                phase = "starting",
                data = emptyMap(),
            )
            is NodeExecutionCompletedEvent -> EventInfo(
                eventType = "node",
                phase = "finished",
                data = emptyMap(),
            )
            is NodeExecutionFailedEvent -> EventInfo(
                eventType = "node",
                phase = "error",
                data = emptyMap(),
            )

            // Strategy
            is StrategyCompletedEvent -> EventInfo(
                eventType = "strategy",
                phase = "finished",
                data = mapOf("strategyName" to event.strategyName),
            )

            // Subgraph
            is SubgraphExecutionStartingEvent -> EventInfo(
                eventType = "subgraph",
                phase = "starting",
                data = emptyMap(),
            )
            is SubgraphExecutionCompletedEvent -> EventInfo(
                eventType = "subgraph",
                phase = "finished",
                data = emptyMap(),
            )
            is SubgraphExecutionFailedEvent -> EventInfo(
                eventType = "subgraph",
                phase = "error",
                data = emptyMap(),
            )

            // LLM Streaming
            is LLMStreamingStartingEvent -> EventInfo(
                eventType = "llm_streaming",
                phase = "starting",
                data = emptyMap(),
            )
            is LLMStreamingCompletedEvent -> EventInfo(
                eventType = "llm_streaming",
                phase = "finished",
                data = emptyMap(),
            )
            is LLMStreamingFailedEvent -> EventInfo(
                eventType = "llm_streaming",
                phase = "error",
                data = buildData("error" to event.error.message),
            )
            is LLMStreamingFrameReceivedEvent -> EventInfo(
                eventType = "llm_streaming",
                phase = "frame",
                data = emptyMap(),
            )

            // Catch-all for any new events
            else -> EventInfo(
                eventType = "unknown",
                phase = "unknown",
                data = buildData("class" to event::class.simpleName),
            )
        }
    }

    private fun buildLlmRequestDetail(event: LLMCallStartingEvent): String {
        return buildJsonObject {
            putJsonArray("messages") {
                for (msg in event.prompt.messages) {
                    addJsonObject {
                        put("role", msg.role.name.lowercase())
                        put("content", msg.content)
                        if (msg is Message.Tool.Result) {
                            msg.tool.let { put("tool", it) }
                        }
                    }
                }
            }
            putJsonObject("params") {
                event.prompt.params.temperature?.let { put("temperature", it) }
                event.prompt.params.maxTokens?.let { put("maxTokens", it) }
                event.prompt.params.toolChoice?.let { put("toolChoice", it.toString()) }
                event.prompt.params.numberOfChoices?.let { put("numberOfChoices", it) }
            }
            putJsonObject("model") {
                put("provider", event.model.provider)
                put("model", event.model.model)
                event.model.contextLength?.let { put("contextLength", it) }
                event.model.maxOutputTokens?.let { put("maxOutputTokens", it) }
            }
            putJsonArray("tools") {
                for (tool in event.tools) {
                    add(tool)
                }
            }
        }.toString()
    }

    private fun buildLlmResponseDetail(event: LLMCallCompletedEvent): String {
        return buildJsonObject {
            putJsonArray("responses") {
                for (msg in event.responses) {
                    addJsonObject {
                        put("role", msg.role.name.lowercase())
                        put("content", msg.content)
                        if (msg is Message.Assistant) {
                            msg.finishReason?.let { put("finishReason", it) }
                        }
                        if (msg is Message.Tool.Call) {
                            putJsonObject("toolCall") {
                                put("name", msg.tool)
                                msg.id?.let { put("id", it) }
                                put("arguments", msg.content)
                            }
                        }
                        val meta = msg.metaInfo
                        meta.totalTokensCount?.let { put("totalTokens", it) }
                        meta.inputTokensCount?.let { put("inputTokens", it) }
                        meta.outputTokensCount?.let { put("outputTokens", it) }
                    }
                }
            }
            putJsonObject("model") {
                put("provider", event.model.provider)
                put("model", event.model.model)
                event.model.contextLength?.let { put("contextLength", it) }
                event.model.maxOutputTokens?.let { put("maxOutputTokens", it) }
            }
            // Aggregate token usage from the last response with meta info
            val lastMeta = event.responses.lastOrNull()?.metaInfo
            if (lastMeta is ai.koog.prompt.message.ResponseMetaInfo) {
                putJsonObject("tokenUsage") {
                    lastMeta.totalTokensCount?.let { put("total", it) }
                    lastMeta.inputTokensCount?.let { put("input", it) }
                    lastMeta.outputTokensCount?.let { put("output", it) }
                }
            }
        }.toString()
    }

    private fun buildData(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }
}

private data class EventInfo(
    val eventType: String,
    val phase: String,
    val data: Map<String, String>,
    val durationMs: Long? = null,
    val detail: String? = null,
)
