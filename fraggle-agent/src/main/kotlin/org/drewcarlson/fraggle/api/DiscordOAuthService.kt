package org.drewcarlson.fraggle.api

/**
 * Service for Discord OAuth2 operations.
 * Handles the user installation flow to establish DM conversations.
 */
interface DiscordOAuthService {
    /**
     * Check if Discord OAuth is configured and available.
     */
    fun isConfigured(): Boolean

    /**
     * Get the OAuth2 authorization URL for user installation.
     *
     * @param state Optional state parameter for CSRF protection
     * @return The authorization URL, or null if OAuth is not configured
     */
    fun getAuthorizationUrl(state: String? = null): String?

    /**
     * Handle the OAuth2 callback and send a welcome DM.
     *
     * @param code The authorization code from Discord
     * @param state The state parameter (for verification)
     * @return Result of the callback handling
     */
    suspend fun handleCallback(code: String, state: String?): OAuthCallbackResult
}

/**
 * Result of OAuth callback processing.
 */
sealed class OAuthCallbackResult {
    data class Success(val userId: String, val username: String) : OAuthCallbackResult()
    data class Error(val message: String) : OAuthCallbackResult()
}
