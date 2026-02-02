package org.drewcarlson.fraggle.signal

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

/**
 * Safely get string content from a JsonPrimitive, returning null if not a string.
 */
private val JsonPrimitive.contentOrNull: String?
    get() = if (this.isString) this.content else this.content.takeIf { it != "null" }

/**
 * Wrapper for signal-cli JSON-RPC interface.
 *
 * signal-cli must be installed and registered with the configured phone number.
 * This class uses JSON-RPC mode for bidirectional communication.
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
) {
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

    private val requestId = AtomicInteger(0)
    private val pendingRequests = mutableMapOf<Int, CompletableDeferred<JsonElement>>()
    private val messageChannel = Channel<SignalMessage>(Channel.BUFFERED)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start the signal-cli JSON-RPC process.
     */
    suspend fun start() {
        if (_isRunning.value) {
            logger.warn("SignalCli is already running")
            return
        }

        withContext(Dispatchers.IO) {
            val command = buildCommand()
            logger.info("Starting signal-cli: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            process = processBuilder.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            _isRunning.value = true

            // Start reading responses
            readerJob = scope.launch {
                try {
                    readResponses()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error reading from signal-cli: ${e.message}")
                    _isRunning.value = false
                }
            }

            // Start error stream reader
            scope.launch {
                try {
                    process?.errorStream?.bufferedReader()?.forEachLine { line ->
                        logger.warn("signal-cli stderr: $line")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Subscribe to receive messages
            subscribeToMessages()
        }
    }

    /**
     * Stop the signal-cli process.
     */
    suspend fun stop() {
        if (!_isRunning.value) return

        withContext(Dispatchers.IO) {
            _isRunning.value = false
            readerJob?.cancelAndJoin()
            writer?.close()
            reader?.close()
            process?.destroy()
            messageChannel.close()

            pendingRequests.values.forEach {
                it.completeExceptionally(Exception("SignalCli stopped"))
            }
            pendingRequests.clear()

            logger.info("SignalCli stopped")
        }
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

    private fun buildCommand(): List<String> {
        val cli = config.signalCliPath ?: "signal-cli"
        return listOf(
            cli,
            "-a", config.phoneNumber,
            "--config", config.configDirPath().toString(),
            "jsonRpc"
        )
    }

    private suspend fun subscribeToMessages() {
        // signal-cli JSON-RPC automatically sends messages to the client
        // No explicit subscription needed, messages come as notifications
        logger.info("Ready to receive messages")
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
                logger.info("signal-cli stream closed")
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
        val timestamp = envelope["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

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

            if (messageText != null) {
                logger.debug("Received message from $effectiveSource: ${messageText.take(50)}...")
                val message = SignalMessage(
                    source = effectiveSource,
                    sourceName = sourceName,
                    timestamp = timestamp,
                    message = messageText,
                    groupId = groupId,
                    isGroupMessage = groupId != null,
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
    val timestamp: Long,
    val message: String,
    val groupId: String?,
    val isGroupMessage: Boolean,
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
