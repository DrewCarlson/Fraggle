package fraggle.agent.tracing

import fraggle.agent.event.AgentEvent
import fraggle.agent.message.AgentMessage
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import fraggle.models.TraceEventRecord
import fraggle.tracing.TraceStore
import kotlin.time.Clock
import java.util.UUID

/**
 * Bridges [AgentEvent]s to the existing [TraceStore] and [EventBus].
 * Replaces the Koog Tracing feature + FraggleTraceProcessor.
 *
 * Usage:
 * ```
 * val tracer = AgentEventTracer(traceStore, eventBus, chatId)
 * agent.subscribe(tracer::processEvent)
 * ```
 */
class AgentEventTracer(
    private val traceStore: TraceStore,
    private val eventBus: EventBus?,
    private val chatId: String,
) {
    private var sessionId: String? = null
    private var turnCount = 0

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
                addRecord("agent", "start")
            }

            is AgentEvent.AgentEnd -> {
                addRecord("agent", "end", data = mapOf(
                    "message_count" to event.messages.size.toString(),
                ))
                sessionId?.let { traceStore.completeSession(it) }
            }

            is AgentEvent.TurnStart -> {
                turnCount++
                addRecord("turn", "start", data = mapOf("turn" to turnCount.toString()))
            }

            is AgentEvent.TurnEnd -> {
                val toolCount = event.toolResults.size
                addRecord("turn", "end", data = buildMap {
                    put("turn", turnCount.toString())
                    if (toolCount > 0) put("tool_results", toolCount.toString())
                })
            }

            is AgentEvent.MessageStart -> {
                val type = messageType(event.message)
                addRecord("message", "start", data = mapOf("type" to type))
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
                        val assistant = msg
                        val toolCallCount = assistant.toolCalls.size
                        if (toolCallCount > 0) put("tool_calls", toolCallCount.toString())
                        assistant.usage?.let { usage ->
                            put("prompt_tokens", usage.promptTokens.toString())
                            put("completion_tokens", usage.completionTokens.toString())
                            put("total_tokens", usage.totalTokens.toString())
                        }
                    }
                }
                addRecord("message", "end", data = data)
            }

            is AgentEvent.ToolExecutionStart -> {
                addRecord("tool", "start", data = mapOf(
                    "tool_call_id" to event.toolCallId,
                    "tool_name" to event.toolName,
                ))
            }

            is AgentEvent.ToolExecutionEnd -> {
                addRecord("tool", "end", data = buildMap {
                    put("tool_call_id", event.toolCallId)
                    put("tool_name", event.toolName)
                    put("is_error", event.isError.toString())
                })
            }
        }
    }

    private suspend fun addRecord(
        eventType: String,
        phase: String,
        data: Map<String, String> = emptyMap(),
        detail: String? = null,
    ) {
        val sid = sessionId ?: return
        val record = TraceEventRecord(
            id = UUID.randomUUID().toString(),
            sessionId = sid,
            timestamp = Clock.System.now(),
            eventType = eventType,
            phase = phase,
            data = data,
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
}
