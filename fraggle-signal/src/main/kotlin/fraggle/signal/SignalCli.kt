package fraggle.signal

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Safely get string content from a JsonPrimitive, returning null if not a string.
 */
private val JsonPrimitive.contentOrNull: String?
    get() = if (this.isString) this.content else this.content.takeIf { it != "null" }

/**
 * Connection state for SignalCli.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState()
    data class Failed(val reason: String, val recoverable: Boolean) : ConnectionState()
}

/**
 * Wrapper for signal-cli JSON-RPC interface.
 *
 * signal-cli must be installed and registered with the configured phone number.
 * This class uses JSON-RPC mode for bidirectional communication.
 *
 * Features:
 * - Automatic signal-cli installation if not found in PATH
 * - Automatic reconnection on unexpected disconnects
 * - Exponential backoff for reconnection attempts
 * - Connection state tracking
 *
 * Usage:
 * ```
 * val cli = SignalCli(config)
 * cli.start()
 *
 * // Listen for messages
 * cli.messages().collect { message ->
 *     println("Received: ${message.content}")
 * }
 *
 * // Send messages
 * cli.send(recipient = "+1234567890", message = "Hello!")
 *
 * cli.stop()
 * ```
 */
class SignalCli(
    private val config: SignalConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val maxReconnectAttempts: Int = 5,
    private val initialReconnectDelayMs: Long = 1000,
    private val maxReconnectDelayMs: Long = 30000,
    private val installer: SignalCliInstaller? = createInstaller(config),
) {
    companion object {
        private fun createInstaller(config: SignalConfig): SignalCliInstaller? {
            if (!config.autoInstall) return null
            val appsDir = config.appsDir?.let { java.nio.file.Path.of(it) }
                ?: java.nio.file.Path.of("data/apps")
            return SignalCliInstaller(appsDir, config.signalCliVersion)
        }
    }
    private val logger = LoggerFactory.getLogger(SignalCli::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null
    private var reconnectJob: Job? = null

    private val requestId = AtomicInteger(0)
    private val pendingRequests = mutableMapOf<Int, CompletableDeferred<JsonElement>>()
    private val messageChannel = Channel<SignalMessage>(Channel.BUFFERED)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Track if stop was requested explicitly
    private var stopRequested = false

    // Track last error from stderr for context
    private var lastStderrError: String? = null

    /**
     * Start the signal-cli JSON-RPC process.
     */
    suspend fun start() {
        if (_isRunning.value) {
            logger.warn("SignalCli is already running")
            return
        }

        stopRequested = false
        startProcess()
    }

    private suspend fun startProcess() {
        _connectionState.value = ConnectionState.Connecting

        withContext(Dispatchers.IO) {
            try {
                val command = buildCommand()
                logger.info("Starting signal-cli: ${command.joinToString(" ")}")

                val processBuilder = ProcessBuilder(command)
                    .redirectErrorStream(false)

                process = processBuilder.start()
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
                reader = BufferedReader(InputStreamReader(process!!.inputStream))

                _isRunning.value = true
                lastStderrError = null

                // Start reading responses
                readerJob = scope.launch {
                    try {
                        readResponses()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error reading from signal-cli: ${e.message}")
                        handleUnexpectedDisconnect("Read error: ${e.message}")
                    }
                }

                // Start error stream reader
                stderrJob = scope.launch {
                    readStderr()
                }

                // Wait briefly to check if process starts successfully
                delay(100)
                if (process?.isAlive != true) {
                    val exitCode = process?.exitValue() ?: -1
                    throw SignalCliException("signal-cli exited immediately with code $exitCode")
                }

                _connectionState.value = ConnectionState.Connected
                logger.info("Ready to receive messages")

            } catch (e: Exception) {
                logger.error("Failed to start signal-cli: ${e.message}")
                _isRunning.value = false
                _connectionState.value = ConnectionState.Failed(
                    reason = e.message ?: "Unknown error",
                    recoverable = isRecoverableError(e.message)
                )
                throw e
            }
        }
    }

    private suspend fun readStderr() {
        try {
            process?.errorStream?.bufferedReader()?.useLines { lines ->
                for (line in lines) {
                    handleStderrLine(line)
                }
            }
        } catch (e: Exception) {
            // Stream closed, ignore
        }
    }

    private fun handleStderrLine(line: String) {
        // Check for fatal errors first, regardless of log level classification.
        // Some fatal messages (e.g., "User X is not registered.") don't contain "Error"
        // and would otherwise be missed, causing infinite reconnect loops.
        if (isFatalError(line)) {
            logger.error("signal-cli stderr (fatal): $line")
            lastStderrError = line
            scope.launch {
                handleFatalError(line)
            }
            return
        }

        // Classify the severity of stderr messages
        when {
            line.contains("Error", ignoreCase = true) -> {
                logger.error("signal-cli stderr: $line")
                lastStderrError = line

                if (isTransientError(line)) {
                    // Transient errors will be handled by reconnection logic
                    logger.warn("Transient error detected, will attempt reconnection if stream closes")
                }
            }
            line.contains("WARN", ignoreCase = true) -> {
                logger.warn("signal-cli stderr: $line")
                // Some warnings are important for tracking state
                if (isTransientError(line)) {
                    lastStderrError = line
                }
            }
            line.contains("INFO", ignoreCase = true) -> {
                // Log at debug level to reduce noise - INFO from signal-cli is often verbose
                logger.debug("signal-cli: $line")
            }
            else -> {
                logger.debug("signal-cli stderr: $line")
            }
        }
    }

    private fun isFatalError(errorMessage: String): Boolean {
        // These errors indicate the account is not properly set up
        // and reconnecting won't help
        val fatalPatterns = listOf(
            "not registered",
            "not found",
            "authorization failed",
            "invalid phone number",
            "unregistered user",
            "identity key changed",  // Identity key mismatch - needs manual trust reset
        )
        return fatalPatterns.any { errorMessage.contains(it, ignoreCase = true) }
    }

    private fun isTransientError(errorMessage: String): Boolean {
        // These errors are likely temporary and reconnection should help
        val transientPatterns = listOf(
            "closed unexpectedly",
            "connection closed",
            "timeout",
            "network",
            "socket",
            "reconnecting",
        )
        return transientPatterns.any { errorMessage.contains(it, ignoreCase = true) }
    }

    private fun isRecoverableError(errorMessage: String?): Boolean {
        if (errorMessage == null) return true
        return !isFatalError(errorMessage)
    }

    private suspend fun handleFatalError(errorMessage: String) {
        logger.error("Fatal signal-cli error, stopping: $errorMessage")
        _connectionState.value = ConnectionState.Failed(
            reason = errorMessage,
            recoverable = false
        )
        stopInternal()
    }

    private suspend fun handleUnexpectedDisconnect(reason: String) {
        if (stopRequested) return

        // If a fatal error was already detected (e.g., "not registered"),
        // don't attempt reconnection — the fatal handler already set Failed state.
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Failed && !currentState.recoverable) {
            logger.warn("Disconnect after fatal error, not reconnecting: $reason")
            _isRunning.value = false
            cleanupProcess()
            pendingRequests.values.forEach { it.completeExceptionally(SignalCliException("Connection lost: $reason")) }
            pendingRequests.clear()
            return
        }

        logger.warn("Unexpected disconnect: $reason")
        _isRunning.value = false

        // Clean up current connection
        cleanupProcess()

        // Fail pending requests
        val error = SignalCliException("Connection lost: $reason")
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()

        // Attempt to reconnect if error is recoverable
        val fullReason = lastStderrError ?: reason
        if (isRecoverableError(fullReason)) {
            attemptReconnect(fullReason)
        } else {
            _connectionState.value = ConnectionState.Failed(
                reason = fullReason,
                recoverable = false
            )
        }
    }

    private fun attemptReconnect(reason: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var delayMs = initialReconnectDelayMs

            while (attempt < maxReconnectAttempts && !stopRequested) {
                attempt++
                _connectionState.value = ConnectionState.Reconnecting(attempt, reason)
                logger.info("Attempting to reconnect (attempt $attempt/$maxReconnectAttempts) after ${delayMs}ms...")

                delay(delayMs)

                if (stopRequested) break

                try {
                    startProcess()
                    logger.info("Reconnected successfully")
                    return@launch
                } catch (e: Exception) {
                    logger.warn("Reconnection attempt $attempt failed: ${e.message}")
                    delayMs = (delayMs * 2).coerceAtMost(maxReconnectDelayMs)
                }
            }

            if (!stopRequested) {
                logger.error("Failed to reconnect after $maxReconnectAttempts attempts")
                _connectionState.value = ConnectionState.Failed(
                    reason = "Failed to reconnect after $maxReconnectAttempts attempts: $reason",
                    recoverable = true
                )
            }
        }
    }

    /**
     * Stop the signal-cli process.
     */
    suspend fun stop() {
        stopRequested = true
        reconnectJob?.cancel()
        stopInternal()
    }

    private suspend fun stopInternal() {
        if (!_isRunning.value && _connectionState.value is ConnectionState.Disconnected) return

        withContext(Dispatchers.IO) {
            _isRunning.value = false

            cleanupProcess()

            pendingRequests.values.forEach {
                it.completeExceptionally(Exception("SignalCli stopped"))
            }
            pendingRequests.clear()

            _connectionState.value = ConnectionState.Disconnected
            logger.info("SignalCli stopped")
        }
    }

    private fun cleanupProcess() {
        try {
            readerJob?.cancel()
            stderrJob?.cancel()
            writer?.close()
            reader?.close()
            process?.destroy()
        } catch (e: Exception) {
            logger.debug("Error during cleanup: ${e.message}")
        }
        process = null
        writer = null
        reader = null
    }

    /**
     * Flow of incoming messages.
     */
    fun messages(): Flow<SignalMessage> = messageChannel.receiveAsFlow()

    /**
     * Send a text message with optional formatting.
     *
     * @param recipient The phone number to send to (for direct messages)
     * @param message The message text
     * @param groupId Optional group ID (for group messages)
     * @param textStyles Optional list of text styles in "start:length:STYLE" format
     */
    suspend fun send(
        recipient: String,
        message: String,
        groupId: String? = null,
        textStyles: List<String>? = null,
    ): Boolean {
        val params = buildJsonObject {
            if (groupId != null) {
                put("groupId", groupId)
            } else {
                put("recipient", JsonArray(listOf(JsonPrimitive(recipient))))
            }
            put("message", message)
            if (!textStyles.isNullOrEmpty()) {
                put("textStyle", JsonArray(textStyles.map { JsonPrimitive(it) }))
            }
        }

        return try {
            rpcCall("send", params)
            true
        } catch (e: Exception) {
            logger.error("Failed to send message: ${e.message}")
            false
        }
    }

    /**
     * Send a message with attachments and optional formatting.
     *
     * @param recipient The phone number to send to (for direct messages)
     * @param message Optional text message to include
     * @param attachments List of file paths to attach
     * @param groupId Optional group ID (for group messages)
     * @param textStyles Optional list of text styles in "start:length:STYLE" format
     */
    suspend fun sendWithAttachments(
        recipient: String,
        message: String? = null,
        attachments: List<String>,
        groupId: String? = null,
        textStyles: List<String>? = null,
    ): Boolean {
        val params = buildJsonObject {
            if (groupId != null) {
                put("groupId", groupId)
            } else {
                put("recipient", JsonArray(listOf(JsonPrimitive(recipient))))
            }
            if (message != null) {
                put("message", message)
                if (!textStyles.isNullOrEmpty()) {
                    put("textStyle", JsonArray(textStyles.map { JsonPrimitive(it) }))
                }
            }
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        }

        return try {
            rpcCall("send", params)
            true
        } catch (e: Exception) {
            logger.error("Failed to send message with attachments: ${e.message}")
            false
        }
    }

    /**
     * Send a typing indicator.
     */
    suspend fun sendTyping(recipient: String, groupId: String? = null, stop: Boolean = false) {
        val params = buildJsonObject {
            if (groupId != null) {
                put("groupId", groupId)
            } else {
                put("recipient", recipient)
            }
            put("stop", stop)
        }

        try {
            rpcCall("sendTyping", params)
        } catch (e: Exception) {
            logger.debug("Failed to send typing indicator: ${e.message}")
        }
    }

    /**
     * Send a reaction to a message.
     */
    suspend fun sendReaction(
        recipient: String,
        targetAuthor: String,
        targetTimestamp: Long,
        emoji: String,
        groupId: String? = null,
    ): Boolean {
        val params = buildJsonObject {
            if (groupId != null) {
                put("groupId", groupId)
            } else {
                put("recipient", recipient)
            }
            put("targetAuthor", targetAuthor)
            put("targetTimestamp", targetTimestamp)
            put("emoji", emoji)
        }

        return try {
            rpcCall("sendReaction", params)
            true
        } catch (e: Exception) {
            logger.error("Failed to send reaction: ${e.message}")
            false
        }
    }

    /**
     * Get a list of groups.
     */
    suspend fun listGroups(): List<SignalGroup> {
        val result = rpcCall("listGroups", JsonObject(emptyMap()))
        return json.decodeFromJsonElement(result)
    }

    /**
     * Get group information.
     */
    suspend fun getGroup(groupId: String): SignalGroup? {
        val params = buildJsonObject {
            put("groupId", groupId)
        }
        return try {
            val result = rpcCall("getGroup", params)
            json.decodeFromJsonElement(result)
        } catch (e: Exception) {
            logger.error("Failed to get group: ${e.message}")
            null
        }
    }

    /**
     * Get user status for the configured account.
     * Returns true if the account is properly registered and authenticated.
     */
    suspend fun getUserStatus(): Boolean {
        if (!_isRunning.value) {
            return false
        }
        val params = buildJsonObject {
            put("recipient", config.phoneNumber)
        }
        return try {
            rpcCall("getUserStatus", params)
            true
        } catch (e: Exception) {
            logger.debug("getUserStatus failed: ${e.message}")
            false
        }
    }

    /**
     * Update the Signal account profile name.
     * This sets the display name that recipients see instead of "Unknown".
     */
    suspend fun updateProfile(name: String) {
        // signal-cli JSON-RPC uses camelCase for parameter names (matching other
        // methods like send's groupId, textStyle, etc.)
        val params = buildJsonObject {
            put("givenName", name)
        }
        try {
            val result = rpcCall("updateProfile", params)
            logger.info("Signal profile name set to '{}', response: {}", name, result)
        } catch (e: Exception) {
            logger.error("Failed to update Signal profile name: ${e.message}", e)
        }
    }

    private var resolvedCliPath: String? = null

    private suspend fun buildCommand(): List<String> {
        val cli = resolveSignalCliPath()
        return listOf(
            cli,
            "-a", config.phoneNumber,
            "--config", config.configDir,
            "--trust-new-identities", "always",
            "jsonRpc"
        )
    }

    /**
     * Resolve the path to signal-cli executable.
     * Priority:
     * 1. Explicit path from config
     * 2. Cached resolved path
     * 3. Auto-installed version
     * 4. System PATH
     */
    private suspend fun resolveSignalCliPath(): String {
        // Use explicit path if configured
        config.signalCliPath?.let { return it }

        // Use cached path if already resolved
        resolvedCliPath?.let { return it }

        // Try auto-install if enabled
        if (config.autoInstall && installer != null) {
            // Check if already installed
            installer.getSignalCliPath()?.let {
                resolvedCliPath = it.toString()
                logger.info("Using installed signal-cli at $it")
                return it.toString()
            }

            // Check if in PATH before downloading
            if (SignalCliInstaller.isInPath()) {
                resolvedCliPath = "signal-cli"
                logger.info("Using signal-cli from system PATH")
                return "signal-cli"
            }

            // Install signal-cli
            val installed = installer.ensureInstalled()
            if (installed != null) {
                resolvedCliPath = installed.toString()
                return installed.toString()
            }
        }

        // Fall back to system PATH
        resolvedCliPath = "signal-cli"
        return "signal-cli"
    }

    private suspend fun readResponses() {
        val reader = this.reader ?: return

        while (_isRunning.value) {
            val line = withContext(Dispatchers.IO) {
                try {
                    reader.readLine()
                } catch (e: Exception) {
                    null
                }
            }

            if (line == null) {
                if (_isRunning.value && !stopRequested) {
                    handleUnexpectedDisconnect(lastStderrError ?: "Stream closed unexpectedly")
                }
                break
            }

            if (line.isBlank()) continue

            try {
                handleJsonRpcMessage(line)
            } catch (e: Exception) {
                logger.error("Failed to parse JSON-RPC message: ${e.message}")
                logger.debug("Raw message: $line")
            }
        }
    }

    private suspend fun handleJsonRpcMessage(line: String) {
        val jsonElement = json.parseToJsonElement(line)
        val jsonObject = jsonElement.jsonObject

        // Log raw message at debug level for troubleshooting
        logger.debug("Received JSON-RPC: $line")

        // Check if it's a response or notification
        if (jsonObject.containsKey("id") && jsonObject["id"] !is JsonNull) {
            // It's a response
            val id = jsonObject["id"]?.jsonPrimitive?.intOrNull ?: return

            val deferred = pendingRequests.remove(id)
            if (deferred != null) {
                if (jsonObject.containsKey("error")) {
                    val error = jsonObject["error"]?.jsonObject
                    val message = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    deferred.completeExceptionally(SignalCliException(message))
                } else {
                    val result = jsonObject["result"] ?: JsonNull
                    deferred.complete(result)
                }
            }
        } else if (jsonObject.containsKey("method")) {
            // It's a notification
            val method = jsonObject["method"]?.jsonPrimitive?.content
            val params = jsonObject["params"]?.jsonObject

            if (method == "receive" && params != null) {
                handleReceiveNotification(params)
            }
        }
    }

    private suspend fun handleReceiveNotification(params: JsonObject) {
        val envelope = params["envelope"]?.jsonObject
        if (envelope == null) {
            logger.debug("Received notification without envelope: $params")
            return
        }

        // Extract source - try multiple possible field names
        // signal-cli can use: source, sourceNumber, or sourceAddress.number
        val source = envelope["source"]?.jsonPrimitive?.contentOrNull
        val sourceNumber = envelope["sourceNumber"]?.jsonPrimitive?.contentOrNull
        val sourceAddress = envelope["sourceAddress"]?.jsonObject
        val sourceAddressNumber = sourceAddress?.get("number")?.jsonPrimitive?.contentOrNull
        val sourceUuid = envelope["sourceUuid"]?.jsonPrimitive?.contentOrNull
            ?: sourceAddress?.get("uuid")?.jsonPrimitive?.contentOrNull

        val effectiveSource = sourceNumber ?: source ?: sourceAddressNumber ?: sourceUuid

        val sourceName = envelope["sourceName"]?.jsonPrimitive?.contentOrNull
            ?: sourceAddress?.get("name")?.jsonPrimitive?.contentOrNull
        val timestamp = envelope["timestamp"]?.jsonPrimitive?.longOrNull
            ?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

        // Handle data message
        val dataMessage = envelope["dataMessage"]?.jsonObject
        if (dataMessage != null) {
            if (effectiveSource == null) {
                logger.warn("Received dataMessage but could not determine source. Envelope: $envelope")
                return
            }

            val messageText = dataMessage["message"]?.jsonPrimitive?.contentOrNull

            // Extract group info - try multiple possible structures
            val groupInfo = dataMessage["groupInfo"]?.jsonObject
            val groupV2 = dataMessage["groupV2"]?.jsonObject
            val groupId = groupInfo?.get("groupId")?.jsonPrimitive?.contentOrNull
                ?: groupV2?.get("id")?.jsonPrimitive?.contentOrNull

            // Parse attachments
            val attachments = dataMessage["attachments"]?.jsonArray?.mapNotNull { attachmentEl ->
                val att = attachmentEl.jsonObject
                val contentType = att["contentType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SignalAttachment(
                    contentType = contentType,
                    filename = att["filename"]?.jsonPrimitive?.contentOrNull,
                    id = att["id"]?.jsonPrimitive?.contentOrNull,
                    size = att["size"]?.jsonPrimitive?.longOrNull,
                    file = att["file"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList()

            if (messageText != null || attachments.isNotEmpty()) {
                logger.debug("Received message from $effectiveSource: ${messageText?.take(50) ?: "[attachment]"}...")
                val message = SignalMessage(
                    source = effectiveSource,
                    sourceName = sourceName,
                    timestamp = timestamp,
                    message = messageText ?: "",
                    groupId = groupId,
                    isGroupMessage = groupId != null,
                    attachments = attachments,
                )
                messageChannel.send(message)
            }
        }

        // Handle sync message (messages sent from other linked devices)
        val syncMessage = envelope["syncMessage"]?.jsonObject
        if (syncMessage != null) {
            val sentMessage = syncMessage["sentMessage"]?.jsonObject
            if (sentMessage != null) {
                val messageText = sentMessage["message"]?.jsonPrimitive?.contentOrNull
                val destination = sentMessage["destination"]?.jsonPrimitive?.contentOrNull
                val destinationNumber = sentMessage["destinationNumber"]?.jsonPrimitive?.contentOrNull
                val groupInfo = sentMessage["groupInfo"]?.jsonObject
                val groupV2 = sentMessage["groupV2"]?.jsonObject
                val groupId = groupInfo?.get("groupId")?.jsonPrimitive?.contentOrNull
                    ?: groupV2?.get("id")?.jsonPrimitive?.contentOrNull

                // For sync messages, we typically don't want to process our own sent messages
                logger.debug("Received sync message (sent by self), ignoring")
            }
        }
    }

    private suspend fun rpcCall(method: String, params: JsonElement): JsonElement {
        if (!_isRunning.value) {
            throw SignalCliException("SignalCli is not running")
        }

        val id = requestId.incrementAndGet()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred

        withContext(Dispatchers.IO) {
            writer?.write(request.toString())
            writer?.newLine()
            writer?.flush()
        }

        return withTimeoutOrNull(config.commandTimeoutMs) {
            deferred.await()
        } ?: run {
            pendingRequests.remove(id)
            throw SignalCliException("Request timed out")
        }
    }
}

/**
 * Exception thrown by SignalCli operations.
 */
class SignalCliException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Incoming Signal message.
 */
data class SignalMessage(
    val source: String,
    val sourceName: String?,
    val timestamp: Instant,
    val message: String,
    val groupId: String?,
    val isGroupMessage: Boolean,
    val attachments: List<SignalAttachment> = emptyList(),
)

/**
 * Attachment metadata from signal-cli JSON-RPC.
 */
data class SignalAttachment(
    val contentType: String,
    val filename: String?,
    val id: String?,
    val size: Long?,
    val file: String?,
)

/**
 * Signal group information.
 */
@Serializable
data class SignalGroup(
    val id: String,
    val name: String? = null,
    @SerialName("isMember")
    val isMember: Boolean = false,
    @SerialName("isBlocked")
    val isBlocked: Boolean = false,
    val members: List<String> = emptyList(),
)
