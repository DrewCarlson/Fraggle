package fraggle.executor.supervision

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Permission handler that emits [FraggleEvent.ToolPermissionRequest] events on the [EventBus]
 * and waits for a corresponding [FraggleEvent.ToolPermissionGranted] response (120s timeout).
 *
 * WebSocket handlers call [resolvePermission] to complete the request.
 */
class EventToolPermissionHandler(
    private val eventBus: EventBus,
) : ToolPermissionHandler {

    override suspend fun requestPermission(requestId: String, toolName: String, argsJson: String, chatId: String): Boolean {
        eventBus.emit(
            FraggleEvent.ToolPermissionRequest(
                timestamp = Clock.System.now(),
                requestId = requestId,
                chatId = chatId,
                toolName = toolName,
                argsJson = argsJson,
            )
        )

        val response = withTimeoutOrNull(120.seconds) {
            eventBus.events.first { event ->
                event is FraggleEvent.ToolPermissionGranted && event.requestId == requestId
            }
        }

        if (response == null) {
            eventBus.emit(
                FraggleEvent.ToolPermissionTimeout(
                    timestamp = Clock.System.now(),
                    requestId = requestId,
                )
            )
            return false
        }

        return (response as FraggleEvent.ToolPermissionGranted).approved
    }

    /**
     * Called by WebSocket handlers to resolve a pending permission request.
     */
    suspend fun resolvePermission(requestId: String, approved: Boolean) {
        eventBus.emit(
            FraggleEvent.ToolPermissionGranted(
                timestamp = Clock.System.now(),
                requestId = requestId,
                approved = approved,
            )
        )
    }
}
