package fraggle.backend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import fraggle.api.FraggleServices
import fraggle.backend.routes.*
import fraggle.backend.websocket.configureWebSockets
import fraggle.models.ApiConfig
import fraggle.models.DashboardConfig
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Creates and configures the Fraggle API server.
 *
 * @param services The fraggle services to expose via the API.
 * @param apiConfig The API server configuration.
 * @param dashboardConfig The Dashboard client configuration.
 * @return An embedded server ready to be started.
 */
fun createApiServer(
    services: FraggleServices,
    apiConfig: ApiConfig,
    dashboardConfig: DashboardConfig,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val logger = LoggerFactory.getLogger("FraggleApi")

    return embeddedServer(Netty, port = apiConfig.port, host = apiConfig.host) {
        // JSON serialization
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        // CORS
        if (apiConfig.cors.enabled) {
            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Accept)
                allowCredentials = true
                if (apiConfig.cors.allowedOrigins.isEmpty()) {
                    // Note: allowCredentials cannot be used with anyHost()
                    anyHost()
                } else {
                    apiConfig.cors.allowedOrigins.forEach { origin ->
                        val url = Url(origin)
                        allowHost(url.host + (url.port.takeIf { it != 80 && it != 443 }?.let { ":$it" } ?: ""),
                            schemes = listOf(url.protocol.name))
                    }
                }
            }
        }

        // Request logging
        install(CallId) {
            header(HttpHeaders.XRequestId)
            generate { java.util.UUID.randomUUID().toString() }
        }
        install(CallLogging) {
            callIdMdc("call-id")
        }

        // Auto HEAD responses
        install(AutoHeadResponse)

        // WebSockets
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            contentConverter = KotlinxWebsocketSerializationConverter(Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        // Routing
        routing {
            // API routes
            route("/api/v1") {
                statusRoutes(services)
                chatRoutes(services)
                bridgeRoutes(services)
                discordOAuthRoutes(services)
                toolRoutes(services)
                skillRoutes(services)
                memoryRoutes(services)
                schedulerRoutes(services)
                tracingRoutes(services)
                settingsRoutes(services)
                configureWebSockets(services)
            }
        }

        // Dashboard static files
        if (dashboardConfig.enabled) {
            configureDashboard(dashboardConfig.staticPath?.run(::Path))
        }

        logger.info("Fraggle API server configured on ${apiConfig.host}:${apiConfig.port}")
    }
}
