package org.drewcarlson.fraggle.backend.routes

import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * Dashboard static file serving.
 */
fun Application.configureDashboard(staticPath: Path?) {

    if (
        staticPath == null ||
        !staticPath.exists() &&
        javaClass.classLoader?.getResource("dashboard") != null
    ) {
        log.debug("This instance will serve the dashboard from jar resources.")
        routing {
            singlePageApplication {
                filesPath = "dashboard"
                useResources = true
                ignoreFiles { it.startsWith("/api") }
            }
        }
    } else if (staticPath.exists()) {
        log.debug("This instance will serve the dashboard from '{}'.", staticPath)
        routing {
            singlePageApplication {
                filesPath = staticPath.absolutePathString()
                ignoreFiles { it.startsWith("/api") }
            }
        }
    } else {
        log.error("Failed to find dashboard, this instance will serve the API only.")
    }
}
