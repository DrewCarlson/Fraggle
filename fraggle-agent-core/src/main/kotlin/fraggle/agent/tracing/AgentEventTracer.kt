package fraggle.agent.tracing

import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import fraggle.models.TraceEventRecord
import fraggle.tracing.TraceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Bridges [AgentEvent]s to the existing [TraceStore] and [EventBus].
 *
 * Usage:
 * ```
 * val tracer = AgentEventTracer(traceStore, eventBus, chatId)
 * scope.launch { agent.events().collect(tracer::processEvent) }
 * ```
 */
class AgentEventTracer(
    private val traceStore: TraceStore,
    private val eventBus: EventBus?,
    private val chatId: String,
) {
    private var sessionId: String? = null
    private var turnCount = 0

    /**
     * Start timestamps for in-flight spans, keyed by (eventType, correlationKey).
     * Populated on `start` phase, drained on `end` phase to compute [TraceEventRecord.durationMs].
     * Assistant messages and turns have no explicit id so they use a monotonic counter to match the
     * most recent unpaired start within the same type.
     */
    private val pendingSpanStarts = mutableMapOf<String, Instant>()
    private var assistantStartCount = 0
    private var assistantEndCount = 0

    private fun spanKey(eventType: String, correlationKey: String): String =
        "$eventType:$correlationKey"

    suspend fun processEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentStart -> {
                sessionId = UUID.randomUUID().toString()
                traceStore.startSession(sessionId!!, chatId)
                eventBus?.emit(FraggleEvent.TraceSessionStarted(
                    timestamp = Clock.System.now(),
                    sessionId = sessionId!!,
                    chatId = chatId,
                ))
                val now = Clock.System.now()
                pendingSpanStarts[spanKey("agent", "0")] = now
                addRecord(
                    eventType = "agent",
                    phase = "start",
                    data = buildMap {
                        event.systemPrompt?.let {
                            put("system_prompt_length", it.length.toString())
                        }
                    },
                    detail = event.systemPrompt?.let { prompt ->
                        buildJsonObject {
                            put("system_prompt", prompt)
                        }.toJsonString()
                    },
                    timestamp = now,
                )
            }

            is AgentEvent.AgentEnd -> {
                val end = Clock.System.now()
                val duration = pendingSpanStarts.remove(spanKey("agent", "0"))
                    ?.let { end - it }
                addRecord(
                    eventType = "agent",
                    phase = "end",
                    data = mapOf("message_count" to event.messages.size.toString()),
                    duration = duration,
                    timestamp = end,
                )
                sessionId?.let { traceStore.completeSession(it) }
            }

            is AgentEvent.TurnStart -> {
                turnCount++
                val now = Clock.System.now()
                pendingSpanStarts[spanKey("turn", turnCount.toString())] = now
                addRecord("turn", "start", data = mapOf("turn" to turnCount.toString()), timestamp = now)
            }

            is AgentEvent.TurnEnd -> {
                val toolCount = event.toolResults.size
                val messageType = messageType(event.message)
                val end = Clock.System.now()
                val duration = pendingSpanStarts.remove(spanKey("turn", turnCount.toString()))
                    ?.let { end - it }
                addRecord(
                    eventType = "turn",
                    phase = "end",
                    data = buildMap {
                        put("turn", turnCount.toString())
                        put("type", messageType)
                        if (toolCount > 0) put("tool_results", toolCount.toString())
                    },
                    detail = buildJsonObject {
                        put("turn", turnCount)
                        put("message_type", messageType)
                        put("tool_results_count", toolCount)
                        putJsonObject("message") { encodeMessage(this, event.message) }
                    }.toJsonString(),
                    duration = duration,
                    timestamp = end,
                )
            }

            is AgentEvent.MessageStart -> {
                val type = messageType(event.message)
                assistantStartCount++
                val now = Clock.System.now()
                pendingSpanStarts[spanKey("message", assistantStartCount.toString())] = now
                addRecord(
                    eventType = "message",
                    phase = "start",
                    data = mapOf("type" to type),
                    detail = buildJsonObject { encodeMessage(this, event.message) }.toJsonString(),
                    timestamp = now,
                )
            }

            is AgentEvent.MessageUpdate -> {
                // Don't emit individual streaming deltas to trace store (too noisy)
            }

            is AgentEvent.MessageEnd -> {
                val type = messageType(event.message)
                val data = buildMap<String, String> {
                    put("type", type)
                    val msg = event.message
                    if (msg is AgentMessage.Assistant) {
                        val toolCallCount = msg.toolCalls.size
                        if (toolCallCount > 0) put("tool_calls", toolCallCount.toString())
                        msg.usage?.let { usage ->
                            put("prompt_tokens", usage.promptTokens.toString())
                            put("completion_tokens", usage.completionTokens.toString())
                            put("total_tokens", usage.totalTokens.toString())
                        }
                    }
                }
                assistantEndCount++
                val end = Clock.System.now()
                val duration = pendingSpanStarts.remove(spanKey("message", assistantEndCount.toString()))
                    ?.let { end - it }
                addRecord(
                    eventType = "message",
                    phase = "end",
                    data = data,
                    detail = buildJsonObject { encodeMessage(this, event.message) }.toJsonString(),
                    duration = duration,
                    timestamp = end,
                )
            }

            is AgentEvent.MessageRecord -> {
                val type = messageType(event.message)
                val msg = event.message
                val data = buildMap<String, String> {
                    put("type", type)
                    if (msg is AgentMessage.ToolResult) {
                        put("tool_call_id", msg.toolCallId)
                        put("tool_name", msg.toolName)
                        put("is_error", msg.isError.toString())
                    }
                }
                // For tool_result, the full payload already lives in the tool:end event —
                // only include a reference here. For other types, include the content.
                val detail = if (msg is AgentMessage.ToolResult) {
                    buildJsonObject {
                        put("type", type)
                        put("tool_call_id", msg.toolCallId)
                        put("tool_name", msg.toolName)
                        put("is_error", msg.isError)
                    }.toJsonString()
                } else {
                    buildJsonObject { encodeMessage(this, msg) }.toJsonString()
                }
                addRecord(
                    eventType = "message",
                    phase = "instant",
                    data = data,
                    detail = detail,
                )
            }

            is AgentEvent.ToolExecutionStart -> {
                val now = Clock.System.now()
                pendingSpanStarts[spanKey("tool", event.toolCallId)] = now
                addRecord(
                    eventType = "tool",
                    phase = "start",
                    data = mapOf(
                        "tool_call_id" to event.toolCallId,
                        "tool_name" to event.toolName,
                    ),
                    detail = buildJsonObject {
                        put("tool_call_id", event.toolCallId)
                        put("tool_name", event.toolName)
                        put("arguments", parseMaybeJson(event.args))
                    }.toJsonString(),
                    timestamp = now,
                )
            }

            is AgentEvent.ToolExecutionEnd -> {
                val end = Clock.System.now()
                val duration = pendingSpanStarts.remove(spanKey("tool", event.toolCallId))
                    ?.let { end - it }
                addRecord(
                    eventType = "tool",
                    phase = "end",
                    data = buildMap {
                        put("tool_call_id", event.toolCallId)
                        put("tool_name", event.toolName)
                        put("is_error", event.isError.toString())
                    },
                    detail = buildJsonObject {
                        put("tool_call_id", event.toolCallId)
                        put("tool_name", event.toolName)
                        put("is_error", event.isError)
                        put("result", parseMaybeJson(event.result))
                    }.toJsonString(),
                    duration = duration,
                    timestamp = end,
                )
            }
        }
    }

    private suspend fun addRecord(
        eventType: String,
        phase: String,
        data: Map<String, String> = emptyMap(),
        detail: String? = null,
        duration: Duration? = null,
        timestamp: Instant = Clock.System.now(),
    ) {
        val sid = sessionId ?: return
        val record = TraceEventRecord(
            id = UUID.randomUUID().toString(),
            sessionId = sid,
            timestamp = timestamp,
            eventType = eventType,
            phase = phase,
            data = data,
            duration = duration,
            detail = detail,
        )
        traceStore.addEvent(sid, record)
        eventBus?.emit(FraggleEvent.TraceEvent(
            timestamp = Clock.System.now(),
            sessionId = sid,
            event = record,
        ))
    }

    private fun messageType(message: AgentMessage): String = when (message) {
        is AgentMessage.User -> "user"
        is AgentMessage.Assistant -> "assistant"
        is AgentMessage.ToolResult -> "tool_result"
        is AgentMessage.Platform -> "platform"
    }

    private fun encodeMessage(builder: kotlinx.serialization.json.JsonObjectBuilder, message: AgentMessage) {
        with(builder) {
            put("type", messageType(message))
            when (message) {
                is AgentMessage.User -> {
                    put("content", extractText(message.content))
                }
                is AgentMessage.Assistant -> {
                    put("content", message.textContent)
                    if (message.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            for (call in message.toolCalls) {
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("arguments", parseMaybeJson(call.arguments))
                                })
                            }
                        })
                    }
                    message.usage?.let { usage ->
                        putJsonObject("usage") {
                            put("prompt_tokens", usage.promptTokens)
                            put("completion_tokens", usage.completionTokens)
                            put("total_tokens", usage.totalTokens)
                        }
                    }
                }
                is AgentMessage.ToolResult -> {
                    put("tool_call_id", message.toolCallId)
                    put("is_error", message.isError)
                    put("content", message.textContent)
                }
                is AgentMessage.Platform -> {
                    put("platform", message.platform)
                    put("content", message.data.toString())
                }
            }
        }
    }

    private fun extractText(parts: List<fraggle.agent.message.ContentPart>): String =
        parts.filterIsInstance<fraggle.agent.message.ContentPart.Text>().joinToString("") { it.text }

    private fun parseMaybeJson(value: String): JsonElement {
        if (value.isEmpty()) return JsonNull
        val trimmed = value.trimStart()
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            return try {
                Json.parseToJsonElement(value)
            } catch (_: Exception) {
                JsonPrimitive(value)
            }
        }
        return JsonPrimitive(value)
    }

    private fun JsonObject.toJsonString(): String = detailJson.encodeToString(JsonObject.serializer(), this)

    private companion object {
        private val detailJson = Json { prettyPrint = false }
    }
}
