package org.drewcarlson.fraggle.backend.websocket

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.api.FraggleEvent
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebSocket")
private val json = Json { encodeDefaults = true }

/**
 * Configure WebSocket endpoint for real-time updates.
 */
fun Routing.configureWebSockets(services: FraggleServices) {
    webSocket("/ws") {
        logger.info("WebSocket client connected: ${call.request.local.remoteAddress}")

        try {
            // Launch a coroutine to send events to this client
            val eventJob = launch {
                services.events.collectLatest { event ->
                    try {
                        val message = json.encodeToString(event)
                        send(Frame.Text(message))
                    } catch (e: Exception) {
                        logger.debug("Failed to send event to client: ${e.message}")
                    }
                }
            }

            // Handle incoming messages (for future use - commands, subscriptions, etc.)
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        handleClientMessage(text, services)
                    }
                    is Frame.Ping -> {
                        send(Frame.Pong(frame.data))
                    }
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
 * Currently supports:
 * - ping: Responds with pong
 * - subscribe: Subscribe to specific event types (future)
 */
private suspend fun DefaultWebSocketServerSession.handleClientMessage(
    message: String,
    services: FraggleServices,
) {
    try {
        // Simple ping/pong for connection health
        if (message == "ping") {
            send(Frame.Text("pong"))
            return
        }

        // For now, just echo unknown commands
        logger.debug("Received WebSocket message: $message")
    } catch (e: Exception) {
        logger.debug("Error handling WebSocket message: ${e.message}")
    }
}
