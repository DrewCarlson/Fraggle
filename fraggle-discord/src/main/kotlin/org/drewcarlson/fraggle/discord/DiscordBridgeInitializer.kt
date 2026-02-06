package org.drewcarlson.fraggle.discord

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.chat.BridgeInitializer
import org.drewcarlson.fraggle.chat.InitStepResult
import org.slf4j.LoggerFactory

/**
 * Handles Discord bot initialization via OAuth2.
 *
 * For Discord, the bot token should already be configured in fraggle.yaml.
 * This initializer provides the OAuth2 authorization link that allows the bot
 * to send a welcome DM to establish the conversation channel.
 */
class DiscordBridgeInitializer(
    private val config: DiscordConfig,
) : BridgeInitializer {
    private val logger = LoggerFactory.getLogger(DiscordBridgeInitializer::class.java)

    override val bridgeName: String = "discord"
    override val description: String = "Discord bot integration"

    // Lazy HTTP client for token validation (avoids gateway connection)
    private val httpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    override suspend fun isInitialized(): Boolean {
        // Discord is considered initialized if we have a non-blank token
        // We do a lightweight REST API check instead of creating a Kord instance
        // which would connect to the gateway and cause rate limiting
        if (config.token.isBlank()) {
            return false
        }

        return try {
            val botInfo = validateTokenViaRest()
            botInfo != null
        } catch (e: Exception) {
            logger.debug("Token validation failed: ${e.message}")
            false
        }
    }

    override suspend fun initialize(userInput: String?): InitStepResult {
        // Check if token is configured
        if (config.token.isBlank()) {
            return InitStepResult.Error(
                message = "Discord bot token is not configured. Please add your bot token to fraggle.yaml.",
                recoverable = false,
            )
        }

        // Validate the token via REST API (no gateway connection)
        val botInfo = try {
            validateTokenViaRest()
        } catch (e: Exception) {
            logger.error("Token validation failed: ${e.message}")
            null
        }

        if (botInfo == null) {
            return InitStepResult.Error(
                message = "Invalid bot token. Please check your token in fraggle.yaml.",
                recoverable = false,
            )
        }

        // Check if OAuth is configured
        if (!config.isOAuthConfigured()) {
            return InitStepResult.Complete(
                """
                |Bot: ${botInfo.username}
                |
                |OAuth2 is not configured. Add to fraggle.yaml:
                |  client_secret: "your-secret"
                |  oauth_redirect_uri: "http://localhost:9191/api/v1/discord/oauth/callback"
                """.trimMargin()
            )
        }

        // Generate the OAuth authorization URL
        val clientId = config.getEffectiveClientId()
            ?: return InitStepResult.Error(
                message = "Could not determine client ID from token",
                recoverable = false,
            )

        val authUrl = buildAuthorizationUrl(clientId)

        return InitStepResult.Complete(
            """
            |Bot: ${botInfo.username}
            |
            |Click below to authorize and start chatting via DM:
            |$authUrl
            """.trimMargin()
        )
    }

    /**
     * Validate the bot token using Discord's REST API.
     * This avoids creating a gateway connection which causes rate limiting.
     */
    private suspend fun validateTokenViaRest(): DiscordUser? {
        return try {
            val response = httpClient.get("https://discord.com/api/v10/users/@me") {
                header(HttpHeaders.Authorization, "Bot ${config.token}")
            }

            if (response.status.isSuccess()) {
                val user = response.body<DiscordUser>()
                logger.debug("Discord bot validated via REST: {}", user.username)
                user
            } else {
                logger.debug("Token validation failed with status: {}", response.status)
                null
            }
        } catch (e: Exception) {
            logger.debug("REST token validation failed: {}", e.message)
            null
        }
    }

    private fun buildAuthorizationUrl(clientId: String): String {
        val redirectUri = config.oauthRedirectUri ?: return ""

        val params = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "integration_type" to "1", // User install
            "scope" to "identify applications.commands",
        )

        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }

        return "https://discord.com/oauth2/authorize?$queryString"
    }

    override fun reset() {
        // No state to reset
    }
}
