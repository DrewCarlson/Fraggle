package org.drewcarlson.fraggle.discord

import kotlinx.serialization.Serializable

/**
 * Configuration for the Discord chat bridge.
 */
@Serializable
data class DiscordConfig(
    /**
     * The bot token from the Discord Developer Portal.
     */
    val token: String,

    /**
     * The application's client ID (for OAuth2).
     * If not provided, extracted from the bot token.
     */
    val clientId: String? = null,

    /**
     * The application's client secret (for OAuth2).
     * Required for OAuth2 user installation flow.
     */
    val clientSecret: String? = null,

    /**
     * The OAuth2 redirect URI.
     * Must match the redirect URI configured in the Discord Developer Portal.
     */
    val oauthRedirectUri: String? = null,

    /**
     * Trigger prefix for the bot to respond in guilds (e.g., "!fraggle").
     * If null, the bot responds to all messages in registered channels.
     */
    val triggerPrefix: String? = "!fraggle",

    /**
     * Whether to respond to direct messages without requiring a trigger.
     */
    val respondToDirectMessages: Boolean = true,

    /**
     * Whether to show typing indicator while processing messages.
     */
    val showTypingIndicator: Boolean = true,

    /**
     * Maximum number of images to attach per message.
     * Discord allows up to 10 attachments per message.
     * Default is 10 (free tier limit).
     */
    val maxImagesPerMessage: Int = 10,

    /**
     * Maximum file size in bytes for attachments.
     * Free: 10MB, Nitro Basic: 50MB, Nitro: 500MB.
     * Default is 10MB (free tier limit).
     */
    val maxFileSizeBytes: Long = 10 * 1024 * 1024,

    /**
     * List of guild (server) IDs that the bot is allowed to operate in.
     * If empty, the bot can respond in any guild it's invited to.
     */
    val allowedGuildIds: List<String> = emptyList(),

    /**
     * List of channel IDs that the bot is allowed to respond in.
     * If empty, the bot can respond in any channel.
     */
    val allowedChannelIds: List<String> = emptyList(),

    /**
     * List of registered chats with custom configurations.
     */
    val registeredChats: List<RegisteredDiscordChat> = emptyList(),

    /**
     * Command timeout in milliseconds.
     */
    val commandTimeoutMs: Long = 30_000,
) {
    /**
     * Check if OAuth2 is fully configured.
     */
    fun isOAuthConfigured(): Boolean {
        return !clientSecret.isNullOrBlank() && !oauthRedirectUri.isNullOrBlank()
    }

    /**
     * Get the effective client ID, either from config or extracted from bot token.
     * Bot tokens are in format: {client_id}.{timestamp}.{hmac}
     */
    fun getEffectiveClientId(): String? {
        return clientId ?: token.split(".").firstOrNull()
    }

    companion object {
        /** Discord's maximum attachments per message limit */
        const val MAX_ATTACHMENTS_PER_MESSAGE = 10

        /** File size limits by tier (in bytes) */
        const val FILE_SIZE_FREE = 10L * 1024 * 1024        // 10 MB
        const val FILE_SIZE_NITRO_BASIC = 50L * 1024 * 1024 // 50 MB
        const val FILE_SIZE_NITRO = 500L * 1024 * 1024      // 500 MB
    }
}

/**
 * Configuration for a specific Discord chat (channel or DM).
 */
@Serializable
data class RegisteredDiscordChat(
    /**
     * The channel or DM ID.
     */
    val id: String,

    /**
     * Human-readable name for this chat.
     */
    val name: String? = null,

    /**
     * Override the default trigger prefix for this chat.
     */
    val triggerOverride: String? = null,

    /**
     * Whether this chat is enabled.
     */
    val enabled: Boolean = true,
)
