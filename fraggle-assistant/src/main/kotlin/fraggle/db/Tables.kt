package fraggle.db

import fraggle.db.ChatTable.externalId
import org.jetbrains.exposed.v1.core.Table

/**
 * Stores chat metadata. Each row represents a unique chat (qualified by platform).
 *
 * The [externalId] is the qualified chat ID (e.g., "signal:+1234567890", "discord:12345").
 */
object ChatTable : Table("chats") {
    val id = long("id").autoIncrement()
    val platform = varchar("platform", 50)
    val externalId = varchar("external_id", 255).uniqueIndex()
    val name = varchar("name", 255).nullable()
    val isGroup = bool("is_group").default(false)
    val createdAt = instant("created_at")
    val lastActiveAt = instant("last_active_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Content type of a message (metadata only, no actual content stored).
 */
enum class MessageContentType {
    TEXT,
    IMAGE,
    FILE,
    AUDIO,
    STICKER,
    REACTION,
}

/**
 * Direction of a message relative to the agent.
 */
enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

/**
 * Stores message metadata. No message content is persisted, only metadata
 * like sender, content type, direction, and timing.
 */
object MessageTable : Table("messages") {
    val id = long("id").autoIncrement()
    val chatId = long("chat_id").references(ChatTable.id).index()
    val externalId = varchar("external_id", 255).nullable()
    val senderId = varchar("sender_id", 255)
    val senderName = varchar("sender_name", 255).nullable()
    val senderIsBot = bool("sender_is_bot").default(false)
    val contentType = enumerationByName<MessageContentType>("content_type", 20)
    val direction = enumerationByName<MessageDirection>("direction", 20)
    val timestamp = instant("timestamp")
    val processingDuration = duration("processing_duration").nullable()

    override val primaryKey = PrimaryKey(id)
}
