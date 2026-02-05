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

    /**
     * Bridge initialization requires user input.
     */
    @Serializable
    data class BridgeInitPrompt(
        override val timestamp: Instant,
        val bridgeName: String,
        val sessionId: String,
        val prompt: String,
        val helpText: String? = null,
        val sensitive: Boolean = false,
    ) : FraggleEvent()

    /**
     * Bridge initialization progress update.
     */
    @Serializable
    data class BridgeInitProgress(
        override val timestamp: Instant,
        val bridgeName: String,
        val sessionId: String,
        val message: String,
    ) : FraggleEvent()

    /**
     * Bridge initialization completed successfully.
     */
    @Serializable
    data class BridgeInitComplete(
        override val timestamp: Instant,
        val bridgeName: String,
        val sessionId: String,
        val message: String,
    ) : FraggleEvent()

    /**
     * Bridge initialization encountered an error.
     */
    @Serializable
    data class BridgeInitError(
        override val timestamp: Instant,
        val bridgeName: String,
        val sessionId: String,
        val message: String,
        val recoverable: Boolean,
    ) : FraggleEvent()
}
