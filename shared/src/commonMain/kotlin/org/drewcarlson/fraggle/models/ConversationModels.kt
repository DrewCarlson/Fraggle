package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Summary of a conversation for list views.
 */
@Serializable
data class ConversationSummary(
    val id: String,
    val chatId: String,
    val messageCount: Int,
    val lastMessageAt: Instant?,
)

/**
 * Detailed conversation information including messages.
 */
@Serializable
data class ConversationDetail(
    val id: String,
    val chatId: String,
    val messages: List<MessageInfo>,
)

/**
 * Information about a single message.
 */
@Serializable
data class MessageInfo(
    val role: String,
    val content: String,
    val timestamp: Instant,
)
