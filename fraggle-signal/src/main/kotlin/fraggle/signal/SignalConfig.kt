package fraggle.signal

import kotlinx.serialization.Serializable

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
     * Absolute path to signal-cli configuration directory.
     * Should be resolved by the caller before constructing SignalConfig.
     */
    val configDir: String,

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
    val signalCliVersion: String = "0.14.2",

    /**
     * Base directory for app installations (e.g., data/apps).
     * Used when auto-installing signal-cli.
     */
    val appsDir: String? = null,

    /**
     * Display name for the Signal account profile.
     * This is set on every connect so recipients see a name instead of "Unknown".
     */
    val profileName: String = "Fraggle",

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
)

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
