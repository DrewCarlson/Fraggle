package org.drewcarlson.fraggle.db

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Chat metadata record.
 */
data class ChatRecord(
    val id: Long = 0,
    val platform: String,
    val externalId: String,
    val name: String? = null,
    val isGroup: Boolean = false,
    val createdAt: Instant,
    val lastActiveAt: Instant,
)

/**
 * Message metadata record (no content stored).
 */
data class MessageRecord(
    val id: Long = 0,
    val chatId: Long,
    val externalId: String? = null,
    val senderId: String,
    val senderName: String? = null,
    val senderIsBot: Boolean = false,
    val contentType: MessageContentType,
    val direction: MessageDirection,
    val timestamp: Instant,
    val processingDuration: Duration? = null,
)

/**
 * Interface for persisting chat and message metadata.
 */
interface ChatHistoryStore {
    /**
     * Get or create a chat record by its qualified external ID.
     * If the chat already exists, updates [ChatRecord.lastActiveAt].
     */
    fun getOrCreateChat(
        externalId: String,
        platform: String,
        name: String? = null,
        isGroup: Boolean = false,
    ): ChatRecord

    /**
     * Get a chat by its qualified external ID.
     */
    fun getChat(externalId: String): ChatRecord?

    /**
     * Get a chat by its internal database ID.
     */
    fun getChatById(id: Long): ChatRecord?

    /**
     * List all chats, ordered by last active time descending.
     */
    fun listChats(limit: Int = 50, offset: Long = 0): List<ChatRecord>

    /**
     * Update the display name for a chat.
     */
    fun updateChatName(externalId: String, name: String)

    /**
     * Record an incoming or outgoing message.
     */
    fun recordMessage(message: MessageRecord): MessageRecord

    /**
     * Get messages for a chat, ordered by timestamp descending (newest first).
     */
    fun getMessages(chatId: Long, limit: Int = 50, offset: Long = 0): List<MessageRecord>

    /**
     * Count total messages for a chat.
     */
    fun countMessages(chatId: Long): Long

    /**
     * Get message statistics for a chat.
     */
    fun getChatStats(chatId: Long): ChatStats

    /**
     * Count total chats.
     */
    fun countChats(): Long
}

/**
 * Aggregated statistics for a chat.
 */
data class ChatStats(
    val totalMessages: Long,
    val incomingMessages: Long,
    val outgoingMessages: Long,
    val firstMessageAt: Instant?,
    val lastMessageAt: Instant?,
    val avgProcessingDuration: Duration?,
)
