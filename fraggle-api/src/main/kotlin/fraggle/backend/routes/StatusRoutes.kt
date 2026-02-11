package fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.api.FraggleServices

/**
 * Status and health check routes.
 */
fun Route.statusRoutes(services: FraggleServices) {
    /**
     * GET /api/v1/status
     * Returns the current system status.
     */
    get("/status") {
        val status = services.getStatus()
        call.respond(status)
    }

    /**
     * GET /api/v1/health
     * Simple health check endpoint.
     */
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}
