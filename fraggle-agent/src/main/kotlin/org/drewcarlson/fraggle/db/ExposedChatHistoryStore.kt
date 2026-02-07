package org.drewcarlson.fraggle.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exposed-backed implementation of [ChatHistoryStore] using SQLite.
 */
class ExposedChatHistoryStore(
    private val database: Database,
) : ChatHistoryStore {

    override fun getOrCreateChat(
        externalId: String,
        platform: String,
        name: String?,
        isGroup: Boolean,
    ): ChatRecord = transaction(database) {
        val existing = ChatTable.selectAll()
            .where { ChatTable.externalId eq externalId }
            .singleOrNull()

        if (existing != null) {
            val now = Clock.System.now()
            ChatTable.update({ ChatTable.externalId eq externalId }) {
                it[lastActiveAt] = now
                if (name != null) {
                    it[ChatTable.name] = name
                }
            }
            existing.toChatRecord().copy(lastActiveAt = now, name = name ?: existing[ChatTable.name])
        } else {
            val now = Clock.System.now()
            val insertedId = ChatTable.insert {
                it[ChatTable.platform] = platform
                it[ChatTable.externalId] = externalId
                it[ChatTable.name] = name
                it[ChatTable.isGroup] = isGroup
                it[createdAt] = now
                it[lastActiveAt] = now
            }[ChatTable.id]

            ChatRecord(
                id = insertedId,
                platform = platform,
                externalId = externalId,
                name = name,
                isGroup = isGroup,
                createdAt = now,
                lastActiveAt = now,
            )
        }
    }

    override fun getChat(externalId: String): ChatRecord? = transaction(database) {
        ChatTable.selectAll()
            .where { ChatTable.externalId eq externalId }
            .singleOrNull()
            ?.toChatRecord()
    }

    override fun getChatById(id: Long): ChatRecord? = transaction(database) {
        ChatTable.selectAll()
            .where { ChatTable.id eq id }
            .singleOrNull()
            ?.toChatRecord()
    }

    override fun listChats(limit: Int, offset: Long): List<ChatRecord> = transaction(database) {
        ChatTable.selectAll()
            .orderBy(ChatTable.lastActiveAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset)
            .map { it.toChatRecord() }
    }

    override fun updateChatName(externalId: String, name: String): Unit = transaction(database) {
        ChatTable.update({ ChatTable.externalId eq externalId }) {
            it[ChatTable.name] = name
        }
    }

    override fun recordMessage(message: MessageRecord): MessageRecord = transaction(database) {
        val insertedId = MessageTable.insert {
            it[chatId] = message.chatId
            it[externalId] = message.externalId
            it[senderId] = message.senderId
            it[senderName] = message.senderName
            it[senderIsBot] = message.senderIsBot
            it[contentType] = message.contentType
            it[direction] = message.direction
            it[timestamp] = message.timestamp
            it[processingDuration] = message.processingDuration
        }[MessageTable.id]

        // Update chat's last active time
        ChatTable.update({ ChatTable.id eq message.chatId }) {
            it[lastActiveAt] = message.timestamp
        }

        message.copy(id = insertedId)
    }

    override fun getMessages(chatId: Long, limit: Int, offset: Long): List<MessageRecord> =
        transaction(database) {
            MessageTable.selectAll()
                .where { MessageTable.chatId eq chatId }
                .orderBy(MessageTable.timestamp, SortOrder.DESC)
                .limit(limit)
                .offset(offset)
                .map { it.toMessageRecord() }
        }

    override fun countMessages(chatId: Long): Long = transaction(database) {
        MessageTable.selectAll()
            .where { MessageTable.chatId eq chatId }
            .count()
    }

    override fun getChatStats(chatId: Long): ChatStats = transaction(database) {
        val total = MessageTable.selectAll()
            .where { MessageTable.chatId eq chatId }
            .count()

        val incoming = MessageTable.selectAll()
            .where { (MessageTable.chatId eq chatId) and (MessageTable.direction eq MessageDirection.INCOMING) }
            .count()

        val outgoing = MessageTable.selectAll()
            .where { (MessageTable.chatId eq chatId) and (MessageTable.direction eq MessageDirection.OUTGOING) }
            .count()

        val minCol = MessageTable.timestamp.min()
        val maxCol = MessageTable.timestamp.max()
        val timestamps = MessageTable
            .select(minCol, maxCol)
            .where { MessageTable.chatId eq chatId }
            .single()

        val minTimestamp = timestamps[minCol]
        val maxTimestamp = timestamps[maxCol]

        // Average processing duration for outgoing messages
        val avgCol = MessageTable.processingDuration.avg()
        val avgDuration = MessageTable
            .select(avgCol)
            .where {
                (MessageTable.chatId eq chatId) and
                    (MessageTable.direction eq MessageDirection.OUTGOING) and
                    (MessageTable.processingDuration.isNotNull())
            }
            .single()[avgCol]

        ChatStats(
            totalMessages = total,
            incomingMessages = incoming,
            outgoingMessages = outgoing,
            firstMessageAt = minTimestamp,
            lastMessageAt = maxTimestamp,
            avgProcessingDuration = avgDuration?.toLong()?.milliseconds,
        )
    }

    override fun countChats(): Long = transaction(database) {
        ChatTable.selectAll().count()
    }

    private fun ResultRow.toChatRecord() = ChatRecord(
        id = this[ChatTable.id],
        platform = this[ChatTable.platform],
        externalId = this[ChatTable.externalId],
        name = this[ChatTable.name],
        isGroup = this[ChatTable.isGroup],
        createdAt = this[ChatTable.createdAt],
        lastActiveAt = this[ChatTable.lastActiveAt],
    )

    private fun ResultRow.toMessageRecord() = MessageRecord(
        id = this[MessageTable.id],
        chatId = this[MessageTable.chatId],
        externalId = this[MessageTable.externalId],
        senderId = this[MessageTable.senderId],
        senderName = this[MessageTable.senderName],
        senderIsBot = this[MessageTable.senderIsBot],
        contentType = this[MessageTable.contentType],
        direction = this[MessageTable.direction],
        timestamp = this[MessageTable.timestamp],
        processingDuration = this[MessageTable.processingDuration],
    )
}
