package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import fraggle.api.FraggleServices
import fraggle.api.OAuthCallbackResult
import fraggle.di.AppScope
import fraggle.models.ErrorResponse

/**
 * Discord OAuth routes for user installation flow.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class DiscordOAuthRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/discord/oauth") {
            get("/status") { getStatus() }
            get("/authorize") { getAuthorize() }
            get("/callback") { getCallback() }
        }
    }

    suspend fun RoutingContext.getStatus() {
        val discordOAuth = services.discordOAuth
        call.respond(DiscordOAuthStatus(
            configured = discordOAuth?.isConfigured() ?: false,
        ))
    }

    suspend fun RoutingContext.getAuthorize() {
        val discordOAuth = services.discordOAuth
            ?: return call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Discord OAuth not configured")
            )

        val state = call.request.queryParameters["state"]
        val url = discordOAuth.getAuthorizationUrl(state)
            ?: return call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Failed to generate authorization URL")
            )

        call.respond(DiscordOAuthAuthorizeResponse(url))
    }

    suspend fun RoutingContext.getCallback() {
        val discordOAuth = services.discordOAuth
            ?: return call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Discord OAuth not configured")
            )

        val code = call.request.queryParameters["code"]
            ?: return call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing authorization code")
            )

        val state = call.request.queryParameters["state"]

        val error = call.request.queryParameters["error"]
        if (error != null) {
            val errorDescription = call.request.queryParameters["error_description"]
                ?: "Authorization denied"
            return call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Discord authorization failed: $errorDescription")
            )
        }

        when (val result = discordOAuth.handleCallback(code, state)) {
            is OAuthCallbackResult.Success -> {
                call.respondText(
                    contentType = ContentType.Text.Html,
                    text = buildSuccessPage(result.username)
                )
            }
            is OAuthCallbackResult.Error -> {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(result.message)
                )
            }
        }
    }
}

@Serializable
data class DiscordOAuthStatus(
    val configured: Boolean,
)

@Serializable
data class DiscordOAuthAuthorizeResponse(
    val url: String,
)

/**
 * Build a simple HTML success page for the OAuth callback.
 */
private fun buildSuccessPage(username: String): String = """
<!DOCTYPE html>
<html>
<head>
    <title>Fraggle - Discord Connected</title>
    <meta charset="UTF-8">
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            margin: 0;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            color: #fff;
        }
        .container {
            text-align: center;
            padding: 2rem;
            background: rgba(255,255,255,0.1);
            border-radius: 16px;
            backdrop-filter: blur(10px);
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
            max-width: 400px;
        }
        h1 {
            color: #7289da;
            margin-bottom: 1rem;
        }
        p {
            color: #b9bbbe;
            line-height: 1.6;
        }
        .username {
            color: #fff;
            font-weight: bold;
        }
        .checkmark {
            font-size: 48px;
            margin-bottom: 1rem;
        }
        .info {
            margin-top: 1.5rem;
            padding: 1rem;
            background: rgba(114, 137, 218, 0.2);
            border-radius: 8px;
            font-size: 0.9rem;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="checkmark">✓</div>
        <h1>Connected!</h1>
        <p>Welcome, <span class="username">$username</span>!</p>
        <p>Your Fraggle bot has been installed to your Discord account.</p>
        <div class="info">
            Check your Discord DMs - your bot should have sent you a welcome message!
        </div>
        <p style="margin-top: 1.5rem; font-size: 0.85rem; color: #72767d;">
            You can close this window now.
        </p>
    </div>
    <script>
        // Auto-close after 10 seconds if opened as popup
        setTimeout(() => {
            if (window.opener) {
                window.close();
            }
        }, 10000);
    </script>
</body>
</html>
""".trimIndent()
