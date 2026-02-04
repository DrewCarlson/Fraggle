package org.drewcarlson.fraggle.backend

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.backend.routes.*
import org.drewcarlson.fraggle.backend.websocket.configureWebSockets
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the Fraggle API server.
 */
data class ApiServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val corsEnabled: Boolean = true,
    val corsAllowedOrigins: List<String> = emptyList(),
    val dashboardEnabled: Boolean = false,
    val dashboardStaticPath: Path? = null,
)

/**
 * Creates and configures the Fraggle API server.
 *
 * @param services The fraggle services to expose via the API.
 * @param config The API server configuration.
 * @return An embedded server ready to be started.
 */
fun createApiServer(
    services: FraggleServices,
    config: ApiServerConfig,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val logger = LoggerFactory.getLogger("FraggleApi")

    return embeddedServer(Netty, port = config.port, host = config.host) {
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
        if (config.corsEnabled) {
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

                if (config.corsAllowedOrigins.isEmpty()) {
                    // Default: allow localhost origins for development
                    allowHost("localhost:8840", schemes = listOf("http"))
                    allowHost("localhost:3001", schemes = listOf("http"))
                    allowHost("127.0.0.1:8840", schemes = listOf("http"))
                    allowHost("127.0.0.1:3001", schemes = listOf("http"))
                } else {
                    config.corsAllowedOrigins.forEach { origin ->
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
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        // Routing
        routing {
            // API routes
            route("/api/v1") {
                statusRoutes(services)
                conversationRoutes(services)
                bridgeRoutes(services)
                skillRoutes(services)
                memoryRoutes(services)
                schedulerRoutes(services)
            }

            // WebSocket endpoint for real-time updates
            configureWebSockets(services)

            // Dashboard static files
            if (config.dashboardEnabled) {
                configureDashboard(config.dashboardStaticPath)
            }
        }

        logger.info("Fraggle API server configured on ${config.host}:${config.port}")
        if (config.dashboardEnabled) {
            logger.info("Dashboard enabled${config.dashboardStaticPath?.let { " from $it" } ?: " (embedded)"}")
        }
    }
}
