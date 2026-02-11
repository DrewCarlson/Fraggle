package fraggle.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Summary of a persisted chat for list views.
 */
@Serializable
data class ChatSummary(
    val id: Long,
    val platform: String,
    @SerialName("external_id")
    val externalId: String,
    val name: String? = null,
    @SerialName("is_group")
    val isGroup: Boolean = false,
    @SerialName("message_count")
    val messageCount: Long,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("last_active_at")
    val lastActiveAt: Instant,
)

/**
 * Detailed chat with statistics.
 */
@Serializable
data class ChatDetail(
    val id: Long,
    val platform: String,
    @SerialName("external_id")
    val externalId: String,
    val name: String? = null,
    @SerialName("is_group")
    val isGroup: Boolean = false,
    @SerialName("created_at")
    val createdAt: Instant,
    @SerialName("last_active_at")
    val lastActiveAt: Instant,
    val stats: ChatStatsInfo,
)

/**
 * Chat statistics.
 */
@Serializable
data class ChatStatsInfo(
    @SerialName("total_messages")
    val totalMessages: Long,
    @SerialName("incoming_messages")
    val incomingMessages: Long,
    @SerialName("outgoing_messages")
    val outgoingMessages: Long,
    @SerialName("first_message_at")
    val firstMessageAt: Instant? = null,
    @SerialName("last_message_at")
    val lastMessageAt: Instant? = null,
    @SerialName("avg_processing_duration")
    val avgProcessingDuration: Duration? = null,
)

/**
 * Message metadata record (no content stored).
 */
@Serializable
data class ChatMessageRecord(
    val id: Long,
    @SerialName("sender_id")
    val senderId: String,
    @SerialName("sender_name")
    val senderName: String? = null,
    @SerialName("sender_is_bot")
    val senderIsBot: Boolean = false,
    @SerialName("content_type")
    val contentType: String,
    val direction: String,
    val timestamp: Instant,
    @SerialName("processing_duration")
    val processingDuration: Duration? = null,
)
