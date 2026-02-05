package org.drewcarlson.fraggle.discord

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Handles Discord OAuth2 authorization for user-installed apps.
 *
 * When a user authorizes the app, this creates a DM channel and sends
 * a welcome message, establishing the conversation channel.
 */
class DiscordOAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val redirectUri: String,
) {
    private val logger = LoggerFactory.getLogger(DiscordOAuth::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    companion object {
        private const val DISCORD_API_BASE = "https://discord.com/api/v10"
        private const val DISCORD_OAUTH_AUTHORIZE = "https://discord.com/oauth2/authorize"
        private const val DISCORD_OAUTH_TOKEN = "$DISCORD_API_BASE/oauth2/token"
    }

    /**
     * Generate the OAuth2 authorization URL for user installation.
     *
     * @param state Optional state parameter for CSRF protection
     */
    fun getAuthorizationUrl(state: String? = null): String {
        val params = buildList {
            add("client_id" to clientId)
            add("response_type" to "code")
            add("redirect_uri" to redirectUri)
            add("integration_type" to "1") // User install
            add("scope" to "identify applications.commands")
            state?.let { add("state" to it) }
        }

        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${v.encodeURLParameter()}"
        }

        return "$DISCORD_OAUTH_AUTHORIZE?$queryString"
    }

    /**
     * Exchange an authorization code for an access token.
     */
    suspend fun exchangeCode(code: String): OAuthTokenResponse? {
        return try {
            val response = httpClient.submitForm(
                url = DISCORD_OAUTH_TOKEN,
                formParameters = parameters {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                }
            )

            if (response.status.isSuccess()) {
                response.body<OAuthTokenResponse>()
            } else {
                logger.error("Failed to exchange code: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to exchange OAuth code: ${e.message}", e)
            null
        }
    }

    /**
     * Get the current user's information using an access token.
     */
    suspend fun getCurrentUser(accessToken: String): DiscordUser? {
        return try {
            val response = httpClient.get("$DISCORD_API_BASE/users/@me") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }

            if (response.status.isSuccess()) {
                response.body<DiscordUser>()
            } else {
                logger.error("Failed to get user: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to get current user: ${e.message}", e)
            null
        }
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresIn: Int,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val scope: String,
)

@Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    val discriminator: String,
    val avatar: String? = null,
    @SerialName("global_name")
    val globalName: String? = null,
)
