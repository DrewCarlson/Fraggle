import androidx.compose.runtime.*
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.models.FraggleEvent
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

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
 * WebSocket service for real-time updates from the backend.
 */
class WebSocketService(
    private val scope: CoroutineScope,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<FraggleEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<FraggleEvent> = _events.asSharedFlow()

    // Callbacks for specific data refresh triggers
    private val _refreshTriggers = MutableSharedFlow<RefreshTrigger>(extraBufferCapacity = 10)
    val refreshTriggers: SharedFlow<RefreshTrigger> = _refreshTriggers.asSharedFlow()

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        val location = window.location
        val protocol = if (location.protocol == "https:") "wss:" else "ws:"
        val wsUrl = "$protocol//${location.host}/ws"

        try {
            webSocket = WebSocket(wsUrl).apply {
                onopen = { handleOpen() }
                onclose = { handleClose() }
                onerror = { handleError(it) }
                onmessage = { handleMessage(it) }
            }
        } catch (e: Exception) {
            console.error("Failed to create WebSocket:", e.message)
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun handleOpen() {
        console.log("WebSocket connected")
        _connectionState.value = ConnectionState.CONNECTED
        startPing()
    }

    private fun handleClose() {
        console.log("WebSocket disconnected")
        pingJob?.cancel()
        webSocket = null

        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }
    }

    private fun handleError(event: Event) {
        console.error("WebSocket error")
        // Error is typically followed by close, so we don't need to do much here
    }

    private fun handleMessage(event: dynamic) {
        val data = event.data as? String ?: return

        // Handle pong response
        if (data == "pong") {
            return
        }

        // Try to parse as FraggleEvent
        try {
            val fraggleEvent = json.decodeFromString<FraggleEvent>(data)
            scope.launch {
                _events.emit(fraggleEvent)
                emitRefreshTrigger(fraggleEvent)
            }
        } catch (e: Exception) {
            console.warn("Failed to parse WebSocket message:", data)
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
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(30000) // Ping every 30 seconds
                webSocket?.send("ping")
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000) // Wait 3 seconds before reconnecting
            if (_connectionState.value == ConnectionState.RECONNECTING) {
                connect()
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
