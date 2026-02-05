package org.drewcarlson.fraggle.backend.websocket

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.drewcarlson.fraggle.api.FraggleServices
import org.slf4j.LoggerFactory

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
                        sendSerialized(event)
                    } catch (e: Exception) {
                        logger.debug("Failed to send event to client: ${e.message}")
                    }
                }
            }

            // Handle incoming messages using Ktor's serialization
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        try {
                            val message = receiveDeserialized<ClientMessage>()
                            launch { handleClientMessage(message, services) }
                        } catch (e: Exception) {
                            // Log but don't fail the connection for deserialization errors
                            logger.debug("Failed to deserialize client message: ${e.message}")
                        }
                    }
                    // Ping/Pong is handled automatically by Ktor's WebSocket plugin
                    else -> {
                        // Ignore other frame types
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
            services.bridgeInit.startInit(message.bridgeName)
        }
        is ClientMessage.BridgeInitInput -> {
            logger.debug("Received bridge init input for session: ${message.sessionId}")
            services.bridgeInit.submitInput(message.sessionId, message.input)
        }
        is ClientMessage.CancelBridgeInit -> {
            logger.info("Cancelling bridge initialization session: ${message.sessionId}")
            services.bridgeInit.cancelInit(message.sessionId)
        }
    }
}
