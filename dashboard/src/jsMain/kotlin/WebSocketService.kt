import androidx.compose.runtime.*
import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.models.FraggleEvent

/**
 * WebSocket connection state.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * Shared JSON configuration for serialization.
 */
private val jsonConfig = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Client-to-server WebSocket messages.
 */
@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("start_bridge_init")
    data class StartBridgeInit(val bridgeName: String) : ClientMessage()

    @Serializable
    @SerialName("bridge_init_input")
    data class BridgeInitInput(val sessionId: String, val input: String) : ClientMessage()

    @Serializable
    @SerialName("cancel_bridge_init")
    data class CancelBridgeInit(val sessionId: String) : ClientMessage()
}

/**
 * Ktor HttpClient configured for WebSocket connections with serialization support.
 */
private val wsClient = HttpClient(Js) {
    install(ContentNegotiation) {
        json(jsonConfig)
    }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(jsonConfig)
    }
}

/**
 * WebSocket service for real-time updates from the backend.
 * Uses Ktor's WebSocket client with kotlinx serialization for type-safe message handling.
 */
class WebSocketService(
    private val scope: CoroutineScope,
) {
    private var wsSession: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<FraggleEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<FraggleEvent> = _events.asSharedFlow()

    // Bridge initialization events
    private val _bridgeInitEvents = MutableSharedFlow<FraggleEvent>(extraBufferCapacity = 10)
    val bridgeInitEvents: SharedFlow<FraggleEvent> = _bridgeInitEvents.asSharedFlow()

    // Callbacks for specific data refresh triggers
    private val _refreshTriggers = MutableSharedFlow<RefreshTrigger>(extraBufferCapacity = 10)
    val refreshTriggers: SharedFlow<RefreshTrigger> = _refreshTriggers.asSharedFlow()

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        startConnection()
    }

    private fun startConnection() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            val location = window.location
            val protocol = if (location.protocol == "https:") "wss" else "ws"
            val wsUrl = "$protocol://${location.host}/api/v1/ws"

            try {
                wsClient.webSocket(wsUrl) {
                    wsSession = this
                    _connectionState.value = ConnectionState.CONNECTED
                    console.log("WebSocket connected via Ktor client")

                    try {
                        // Receive events using Ktor's serialization
                        while (isActive) {
                            try {
                                val event = receiveDeserialized<FraggleEvent>()
                                console.log("Received event: ${event::class.simpleName}")
                                _events.emit(event)
                                emitRefreshTrigger(event)
                            } catch (e: WebsocketDeserializeException) {
                                console.warn("Failed to deserialize WebSocket message: ${e.message}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        console.log("WebSocket receive error")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                console.error("WebSocket connection error: ${e.message}")
            } finally {
                wsSession = null
                handleDisconnect()
            }
        }
    }

    private fun handleDisconnect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            console.log("WebSocket disconnected, scheduling reconnect")
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        connectionJob?.cancel()
        scope.launch {
            wsSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnecting"))
        }
        wsSession = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Start bridge initialization.
     */
    fun startBridgeInit(bridgeName: String) {
        sendMessage(ClientMessage.StartBridgeInit(bridgeName))
    }

    /**
     * Submit user input for an active bridge initialization session.
     */
    fun submitBridgeInitInput(sessionId: String, input: String) {
        sendMessage(ClientMessage.BridgeInitInput(sessionId, input))
    }

    /**
     * Cancel an active bridge initialization session.
     */
    fun cancelBridgeInit(sessionId: String) {
        sendMessage(ClientMessage.CancelBridgeInit(sessionId))
    }

    private fun sendMessage(message: ClientMessage) {
        scope.launch {
            try {
                val json = jsonConfig.encodeToString(message)
                console.log("Sending WebSocket message: $json")
                val session = wsSession
                if (session == null) {
                    console.error("Cannot send message - WebSocket not connected")
                    return@launch
                }
                session.send(Frame.Text(json))
                console.log("WebSocket message sent successfully")
            } catch (e: Exception) {
                console.error("Failed to send WebSocket message: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private suspend fun emitRefreshTrigger(event: FraggleEvent) {
        when (event) {
            is FraggleEvent.MessageReceived -> {
                _refreshTriggers.emit(RefreshTrigger.Conversations)
            }
            is FraggleEvent.ResponseSent -> {
                _refreshTriggers.emit(RefreshTrigger.Conversations)
            }
            is FraggleEvent.BridgeStatusChanged -> {
                _refreshTriggers.emit(RefreshTrigger.Bridges)
                _refreshTriggers.emit(RefreshTrigger.Status)
            }
            is FraggleEvent.TaskTriggered -> {
                _refreshTriggers.emit(RefreshTrigger.Scheduler)
            }
            is FraggleEvent.Error -> {
                // Could trigger specific refresh based on source
            }
            is FraggleEvent.BridgeInitPrompt,
            is FraggleEvent.BridgeInitProgress,
            is FraggleEvent.BridgeInitComplete,
            is FraggleEvent.BridgeInitError -> {
                // Emit to bridge init events flow for dialog handling
                _bridgeInitEvents.emit(event)
                // Also refresh bridges on complete
                if (event is FraggleEvent.BridgeInitComplete) {
                    _refreshTriggers.emit(RefreshTrigger.Bridges)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000) // Wait 3 seconds before reconnecting
            if (_connectionState.value == ConnectionState.RECONNECTING) {
                startConnection()
            }
        }
    }
}

/**
 * Triggers for refreshing specific data.
 */
enum class RefreshTrigger {
    Status,
    Conversations,
    Bridges,
    Skills,
    Memory,
    Scheduler,
}

/**
 * Global WebSocket service instance.
 */
private var webSocketServiceInstance: WebSocketService? = null

@Composable
fun rememberWebSocketService(): WebSocketService {
    val scope = rememberCoroutineScope()

    return remember {
        webSocketServiceInstance ?: WebSocketService(scope).also {
            webSocketServiceInstance = it
            it.connect()
        }
    }
}

/**
 * Composable to observe connection state.
 */
@Composable
fun rememberConnectionState(service: WebSocketService): State<ConnectionState> {
    return service.connectionState.collectAsState()
}
