package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.nio.file.Path

/**
 * Dashboard static file serving.
 */
fun Routing.configureDashboard(staticPath: Path?) {
    if (staticPath != null) {
        // Serve from filesystem path
        val staticDir = staticPath.toFile()
        if (staticDir.exists() && staticDir.isDirectory) {
            staticFiles("/", staticDir) {
                default("index.html")
            }
        } else {
            // Fallback: serve a simple message if dashboard files not found
            get("/") {
                call.respondText(
                    "Dashboard files not found at: $staticPath\n" +
                    "Build the dashboard with: ./gradlew :dashboard:jsBrowserProductionDist",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound,
                )
            }
        }
    } else {
        // Serve from classpath resources (embedded)
        staticResources("/", "dashboard") {
            default("index.html")
        }
    }

    // SPA fallback - serve index.html for unmatched routes
    get("/{...}") {
        if (staticPath != null) {
            val indexFile = staticPath.resolve("index.html").toFile()
            if (indexFile.exists()) {
                call.respondFile(indexFile)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        } else {
            // For embedded resources, rely on the staticResources default
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
