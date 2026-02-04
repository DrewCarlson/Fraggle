package org.drewcarlson.fraggle.backend.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices

/**
 * Settings and configuration routes.
 */
fun Route.settingsRoutes(services: FraggleServices) {
    route("/settings") {
        /**
         * GET /api/v1/settings/config
         * Get the current configuration (YAML and structured data).
         */
        get("/config") {
            val config = services.config.getConfig()
            call.respond(config)
        }
    }
}
