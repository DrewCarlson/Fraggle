package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Events emitted for real-time WebSocket updates.
 */
@Serializable
sealed class FraggleEvent {
    abstract val timestamp: Long

    /**
     * A new message was received.
     */
    @Serializable
    data class MessageReceived(
        override val timestamp: Long,
        val chatId: String,
        val senderId: String,
        val senderName: String?,
        val content: String,
    ) : FraggleEvent()

    /**
     * A response was sent.
     */
    @Serializable
    data class ResponseSent(
        override val timestamp: Long,
        val chatId: String,
        val content: String,
    ) : FraggleEvent()

    /**
     * Bridge connection status changed.
     */
    @Serializable
    data class BridgeStatusChanged(
        override val timestamp: Long,
        val bridgeName: String,
        val connected: Boolean,
        val error: String? = null,
    ) : FraggleEvent()

    /**
     * A scheduled task was triggered.
     */
    @Serializable
    data class TaskTriggered(
        override val timestamp: Long,
        val taskId: String,
        val taskName: String,
        val chatId: String,
    ) : FraggleEvent()

    /**
     * An error occurred.
     */
    @Serializable
    data class Error(
        override val timestamp: Long,
        val source: String,
        val message: String,
    ) : FraggleEvent()
}
