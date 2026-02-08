package org.drewcarlson.fraggle.models

import kotlinx.serialization.SerialName
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
    @SerialName("message_received")
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
    @SerialName("response_sent")
    data class ResponseSent(
        override val timestamp: Instant,
        val chatId: String,
        val content: String,
    ) : FraggleEvent()

    /**
     * Bridge connection status changed.
     */
    @Serializable
    @SerialName("bridge_status_changed")
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
    @SerialName("task_triggered")
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
    @SerialName("error")
    data class Error(
        override val timestamp: Instant,
        val source: String,
        val message: String,
    ) : FraggleEvent()

    /**
     * Bridge initialization requires user input.
     */
    @Serializable
    @SerialName("bridge_init_prompt")
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
    @SerialName("bridge_init_progress")
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
    @SerialName("bridge_init_complete")
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
    @SerialName("bridge_init_error")
    data class BridgeInitError(
        override val timestamp: Instant,
        val bridgeName: String,
        val sessionId: String,
        val message: String,
        val recoverable: Boolean,
    ) : FraggleEvent()

    /**
     * A tool is requesting permission to execute.
     */
    @Serializable
    @SerialName("tool_permission_request")
    data class ToolPermissionRequest(
        override val timestamp: Instant,
        val requestId: String,
        val chatId: String,
        val toolName: String,
        val argsJson: String,
    ) : FraggleEvent()

    /**
     * A tool permission request was granted or denied.
     */
    @Serializable
    @SerialName("tool_permission_granted")
    data class ToolPermissionGranted(
        override val timestamp: Instant,
        val requestId: String,
        val approved: Boolean,
    ) : FraggleEvent()

    /**
     * A tool permission request timed out.
     */
    @Serializable
    @SerialName("tool_permission_timeout")
    data class ToolPermissionTimeout(
        override val timestamp: Instant,
        val requestId: String,
    ) : FraggleEvent()
}
