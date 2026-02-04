package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Events emitted for real-time WebSocket updates.
 */
@Serializable
sealed class FraggleEvent {
    abstract val timestamp: Instant

    /**
     * A new message was received.
     */
    @Serializable
    data class MessageReceived(
        override val timestamp: Instant,
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
        override val timestamp: Instant,
        val chatId: String,
        val content: String,
    ) : FraggleEvent()

    /**
     * Bridge connection status changed.
     */
    @Serializable
    data class BridgeStatusChanged(
        override val timestamp: Instant,
        val bridgeName: String,
        val connected: Boolean,
        val error: String? = null,
    ) : FraggleEvent()

    /**
     * A scheduled task was triggered.
     */
    @Serializable
    data class TaskTriggered(
        override val timestamp: Instant,
        val taskId: String,
        val taskName: String,
        val chatId: String,
    ) : FraggleEvent()

    /**
     * An error occurred.
     */
    @Serializable
    data class Error(
        override val timestamp: Instant,
        val source: String,
        val message: String,
    ) : FraggleEvent()
}
