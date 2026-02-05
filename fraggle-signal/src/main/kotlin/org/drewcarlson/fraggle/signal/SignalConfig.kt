package org.drewcarlson.fraggle.signal

import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Configuration for Signal integration.
 */
@Serializable
data class SignalConfig(
    /**
     * Phone number registered with Signal (e.g., "+1234567890").
     */
    val phoneNumber: String,

    /**
     * Path to signal-cli configuration directory.
     * Defaults to ~/.config/fraggle/signal
     */
    val configDir: String = defaultConfigDir(),

    /**
     * Trigger prefix that activates the bot in group chats.
     * Messages must start with this prefix to be processed.
     * Set to null to respond to all messages.
     */
    val triggerPrefix: String? = "@fraggle",

    /**
     * Path to signal-cli executable.
     * If null, assumes signal-cli is in PATH or uses auto-installed version.
     */
    val signalCliPath: String? = null,

    /**
     * Whether to automatically download and install signal-cli if not found.
     */
    val autoInstall: Boolean = true,

    /**
     * Version of signal-cli to auto-install.
     */
    val signalCliVersion: String = "0.13.23",

    /**
     * Base directory for app installations (e.g., data/apps).
     * Used when auto-installing signal-cli.
     */
    val appsDir: String? = null,

    /**
     * Whether to respond to messages in direct chats without trigger.
     */
    val respondToDirectMessages: Boolean = true,

    /**
     * List of registered chat IDs that the bot should respond to.
     * If empty, responds to all chats (subject to trigger rules).
     */
    val registeredChats: List<RegisteredChat> = emptyList(),

    /**
     * Timeout for signal-cli commands in milliseconds.
     */
    val commandTimeoutMs: Long = 30_000,

    /**
     * Whether to show typing indicators when processing.
     */
    val showTypingIndicator: Boolean = true,
) {
    fun configDirPath(): Path = Path(configDir.replace("~", System.getProperty("user.home")))

    companion object {
        fun defaultConfigDir(): String {
            return "${System.getProperty("user.home")}/.config/fraggle/signal"
        }
    }
}

/**
 * A registered chat configuration.
 */
@Serializable
data class RegisteredChat(
    /**
     * Chat ID (group ID or phone number for direct chats).
     */
    val id: String,

    /**
     * Human-readable name for the chat.
     */
    val name: String? = null,

    /**
     * Override trigger prefix for this specific chat.
     */
    val triggerOverride: String? = null,

    /**
     * Whether this chat is enabled.
     */
    val enabled: Boolean = true,
)
