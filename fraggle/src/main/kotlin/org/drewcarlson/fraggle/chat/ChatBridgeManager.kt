package org.drewcarlson.fraggle.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Incoming message with information about which bridge it came from.
 */
data class BridgedMessage(
    /**
     * The original incoming message.
     */
    val message: IncomingMessage,

    /**
     * The bridge that received this message.
     */
    val bridge: ChatBridge,

    /**
     * The qualified chat ID that includes the bridge prefix.
     * Format: "bridgeName:originalChatId"
     */
    val qualifiedChatId: String,
)

/**
 * Manages multiple chat bridges and provides a unified interface for messaging.
 *
 * Chat IDs are qualified with the bridge name as a prefix (e.g., "signal:+1234567890")
 * to ensure messages are routed to the correct bridge.
 */
class ChatBridgeManager(
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(ChatBridgeManager::class.java)

    private val bridges = ConcurrentHashMap<String, ChatBridge>()
    private val chatIdToBridge = ConcurrentHashMap<String, String>()
    private val messageJobs = ConcurrentHashMap<String, Job>()

    private val _messages = MutableSharedFlow<BridgedMessage>(extraBufferCapacity = 64)

    /**
     * Stream of incoming messages from all connected bridges.
     */
    val messages: SharedFlow<BridgedMessage> = _messages.asSharedFlow()

    /**
     * Register a bridge with a unique name.
     *
     * @param name Unique identifier for this bridge (e.g., "signal", "discord")
     * @param bridge The ChatBridge implementation
     */
    fun register(name: String, bridge: ChatBridge) {
        val normalizedName = name.lowercase()
        if (bridges.containsKey(normalizedName)) {
            logger.warn("Bridge '$normalizedName' is already registered, replacing")
            unregister(normalizedName)
        }

        bridges[normalizedName] = bridge
        logger.info("Registered bridge: $normalizedName (${bridge.platform.name})")
    }

    /**
     * Unregister a bridge by name.
     */
    fun unregister(name: String) {
        val normalizedName = name.lowercase()
        messageJobs[normalizedName]?.cancel()
        messageJobs.remove(normalizedName)
        bridges.remove(normalizedName)

        // Clean up chat ID mappings for this bridge
        chatIdToBridge.entries.removeIf { it.value == normalizedName }

        logger.info("Unregistered bridge: $normalizedName")
    }

    /**
     * Get all registered bridge names.
     */
    fun registeredBridges(): Set<String> = bridges.keys.toSet()

    /**
     * Get a bridge by name.
     */
    fun getBridge(name: String): ChatBridge? = bridges[name.lowercase()]

    /**
     * Connect all registered bridges and start collecting messages.
     */
    suspend fun connectAll() {
        for ((name, bridge) in bridges) {
            try {
                logger.info("Connecting bridge: $name")
                bridge.connect()
                startMessageCollection(name, bridge)
                logger.info("Bridge connected: $name")
            } catch (e: Exception) {
                logger.error("Failed to connect bridge $name: ${e.message}", e)
            }
        }
    }

    /**
     * Connect a specific bridge by name.
     */
    suspend fun connect(name: String) {
        val normalizedName = name.lowercase()
        val bridge = bridges[normalizedName]
            ?: throw IllegalArgumentException("Bridge '$normalizedName' not found")

        bridge.connect()
        startMessageCollection(normalizedName, bridge)
        logger.info("Bridge connected: $normalizedName")
    }

    /**
     * Disconnect all bridges.
     */
    suspend fun disconnectAll() {
        for ((name, bridge) in bridges) {
            try {
                messageJobs[name]?.cancel()
                bridge.disconnect()
                logger.info("Bridge disconnected: $name")
            } catch (e: Exception) {
                logger.error("Failed to disconnect bridge $name: ${e.message}", e)
            }
        }
        messageJobs.clear()
    }

    /**
     * Disconnect a specific bridge by name.
     */
    suspend fun disconnect(name: String) {
        val normalizedName = name.lowercase()
        val bridge = bridges[normalizedName]
            ?: throw IllegalArgumentException("Bridge '$normalizedName' not found")

        messageJobs[normalizedName]?.cancel()
        messageJobs.remove(normalizedName)
        bridge.disconnect()
        logger.info("Bridge disconnected: $normalizedName")
    }

    /**
     * Send a message to a chat.
     *
     * @param qualifiedChatId The qualified chat ID (e.g., "signal:+1234567890")
     * @param message The message to send
     */
    suspend fun send(qualifiedChatId: String, message: OutgoingMessage) {
        val (bridgeName, chatId) = parseQualifiedChatId(qualifiedChatId)
        val bridge = bridges[bridgeName]
            ?: throw IllegalArgumentException("Bridge '$bridgeName' not found for chatId: $qualifiedChatId")

        bridge.send(chatId, message)
    }

    /**
     * Set typing indicator for a chat.
     *
     * @param qualifiedChatId The qualified chat ID
     * @param typing Whether to show typing indicator
     */
    suspend fun setTyping(qualifiedChatId: String, typing: Boolean) {
        val (bridgeName, chatId) = parseQualifiedChatId(qualifiedChatId)
        val bridge = bridges[bridgeName] ?: return

        bridge.setTyping(chatId, typing)
    }

    /**
     * Get the platform for a given qualified chat ID.
     */
    fun getPlatform(qualifiedChatId: String): ChatPlatform? {
        val (bridgeName, _) = parseQualifiedChatId(qualifiedChatId)
        return bridges[bridgeName]?.platform
    }

    /**
     * Get chat info for a qualified chat ID.
     */
    suspend fun getChatInfo(qualifiedChatId: String): ChatInfo? {
        val (bridgeName, chatId) = parseQualifiedChatId(qualifiedChatId)
        val bridge = bridges[bridgeName] ?: return null
        return bridge.getChatInfo(chatId)
    }

    /**
     * Check if any bridge is connected.
     */
    fun hasConnectedBridge(): Boolean = bridges.values.any { it.isConnected() }

    /**
     * Check if a specific bridge is connected.
     */
    fun isConnected(name: String): Boolean = bridges[name.lowercase()]?.isConnected() == true

    /**
     * Create a qualified chat ID from a bridge name and raw chat ID.
     */
    fun qualifyChatId(bridgeName: String, chatId: String): String {
        return "${bridgeName.lowercase()}:$chatId"
    }

    /**
     * Parse a qualified chat ID into bridge name and raw chat ID.
     */
    fun parseQualifiedChatId(qualifiedChatId: String): Pair<String, String> {
        val colonIndex = qualifiedChatId.indexOf(':')
        return if (colonIndex > 0) {
            val bridgeName = qualifiedChatId.substring(0, colonIndex).lowercase()
            val chatId = qualifiedChatId.substring(colonIndex + 1)
            bridgeName to chatId
        } else {
            // Fall back: try to find the bridge from our mapping
            val bridgeName = chatIdToBridge[qualifiedChatId]
            if (bridgeName != null) {
                bridgeName to qualifiedChatId
            } else {
                // Last resort: use first available bridge
                val defaultBridge = bridges.keys.firstOrNull()
                    ?: throw IllegalArgumentException("No bridges registered and chatId is not qualified: $qualifiedChatId")
                logger.warn("ChatId '$qualifiedChatId' is not qualified, defaulting to bridge: $defaultBridge")
                defaultBridge to qualifiedChatId
            }
        }
    }

    private fun startMessageCollection(bridgeName: String, bridge: ChatBridge) {
        // Cancel existing job if any
        messageJobs[bridgeName]?.cancel()

        // Start collecting messages from this bridge
        val job = scope.launch {
            bridge.messages().collect { message ->
                // Create qualified chat ID
                val qualifiedChatId = qualifyChatId(bridgeName, message.chatId)

                // Track the mapping for this chat
                chatIdToBridge[message.chatId] = bridgeName

                // Emit the bridged message
                _messages.emit(
                    BridgedMessage(
                        message = message.copy(chatId = qualifiedChatId),
                        bridge = bridge,
                        qualifiedChatId = qualifiedChatId,
                    )
                )
            }
        }

        messageJobs[bridgeName] = job
    }
}
