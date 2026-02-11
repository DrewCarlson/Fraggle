package fraggle.backend.websocket

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import fraggle.api.FraggleServices
import org.slf4j.LoggerFactory

private val wsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val logger = LoggerFactory.getLogger("WebSocket")

/**
 * Client-to-server WebSocket messages.
 */
@Serializable
sealed class ClientMessage {
    /**
     * Subscribe to specific event types.
     */
    @Serializable
    @SerialName("subscribe")
    data class Subscribe(val eventTypes: List<String>) : ClientMessage()

    /**
     * Unsubscribe from event types.
     */
    @Serializable
    @SerialName("unsubscribe")
    data class Unsubscribe(val eventTypes: List<String>) : ClientMessage()

    /**
     * Start bridge initialization.
     */
    @Serializable
    @SerialName("start_bridge_init")
    data class StartBridgeInit(val bridgeName: String) : ClientMessage()

    /**
     * Submit user input for active bridge initialization session.
     */
    @Serializable
    @SerialName("bridge_init_input")
    data class BridgeInitInput(val sessionId: String, val input: String) : ClientMessage()

    /**
     * Cancel an active bridge initialization session.
     */
    @Serializable
    @SerialName("cancel_bridge_init")
    data class CancelBridgeInit(val sessionId: String) : ClientMessage()

    /**
     * Respond to a tool permission request.
     */
    @Serializable
    @SerialName("tool_permission_response")
    data class ToolPermissionResponse(val requestId: String, val granted: Boolean) : ClientMessage()
}

/**
 * Configure WebSocket endpoint for real-time updates.
 *
 * Uses Ktor's built-in ping/pong mechanism and kotlinx serialization for type-safe messaging.
 * Ping/pong is automatically handled by the WebSockets plugin configuration in FraggleApiServer.
 */
fun Route.configureWebSockets(services: FraggleServices) {
    webSocket("/ws") {
        logger.info("WebSocket client connected: ${call.request.local.remoteAddress}")

        try {
            // Launch a coroutine to send events to this client
            val eventJob = launch {
                services.events.collectLatest { event ->
                    try {
                        logger.info("Sending event to client: ${event::class.simpleName}")
                        sendSerialized(event)
                        logger.debug("Event sent successfully")
                    } catch (e: Exception) {
                        logger.warn("Failed to send event to client: ${e.message}", e)
                    }
                }
            }

            // Handle incoming messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        try {
                            val text = frame.readText()
                            logger.info("Received WebSocket message: $text")
                            val message = wsJson.decodeFromString<ClientMessage>(text)
                            launch { handleClientMessage(message, services) }
                        } catch (e: Exception) {
                            // Log but don't fail the connection for deserialization errors
                            logger.warn("Failed to deserialize client message: ${e.message}", e)
                        }
                    }
                    // Ping/Pong is handled automatically by Ktor's WebSocket plugin
                    else -> {
                        logger.debug("Received non-text frame: ${frame.frameType}")
                    }
                }
            }

            eventJob.cancel()
        } catch (e: Exception) {
            logger.debug("WebSocket error: ${e.message}")
        } finally {
            logger.info("WebSocket client disconnected")
        }
    }
}

/**
 * Handle incoming WebSocket messages from clients.
 */
private suspend fun handleClientMessage(
    message: ClientMessage,
    services: FraggleServices,
) {
    logger.info("Handling WebSocket message: ${message::class.simpleName}")
    when (message) {
        is ClientMessage.Subscribe -> {
            logger.debug("Client subscribed to: ${message.eventTypes}")
            // Future: implement selective event subscription
        }
        is ClientMessage.Unsubscribe -> {
            logger.debug("Client unsubscribed from: ${message.eventTypes}")
            // Future: implement selective event unsubscription
        }
        is ClientMessage.StartBridgeInit -> {
            logger.info("Starting bridge initialization for: ${message.bridgeName}")
            val sessionId = services.bridgeInit.startInit(message.bridgeName)
            logger.info("Bridge init started, session ID: $sessionId")
        }
        is ClientMessage.BridgeInitInput -> {
            logger.info("Received bridge init input for session: ${message.sessionId} (input length: ${message.input.length})")
            services.bridgeInit.submitInput(message.sessionId, message.input)
            logger.info("Bridge init input submitted for session: ${message.sessionId}")
        }
        is ClientMessage.CancelBridgeInit -> {
            logger.info("Cancelling bridge initialization session: ${message.sessionId}")
            services.bridgeInit.cancelInit(message.sessionId)
        }
        is ClientMessage.ToolPermissionResponse -> {
            logger.info("Tool permission response for ${message.requestId}: ${message.granted}")
            services.resolveToolPermission(message.requestId, message.granted)
        }
    }
}
