package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.BridgeDetail
import org.drewcarlson.fraggle.models.BridgeInfo
import org.drewcarlson.fraggle.models.ErrorResponse

/**
 * Bridge management routes.
 */
fun Route.bridgeRoutes(services: FraggleServices) {
    route("/bridges") {
        /**
         * GET /api/v1/bridges
         * List all configured bridges and their status.
         */
        get {
            val bridges = services.bridges.registeredBridges().map { name ->
                val bridge = services.bridges.getBridge(name)
                val initialized = services.bridgeInit.isInitialized(name)
                BridgeInfo(
                    name = name,
                    platform = bridge?.platform?.name ?: "Unknown",
                    connected = bridge?.isConnected() ?: false,
                    initialized = initialized,
                )
            }
            call.respond(bridges)
        }

        /**
         * GET /api/v1/bridges/{name}
         * Get detailed status for a specific bridge.
         */
        get("/{name}") {
            val name = call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

            val bridge = services.bridges.getBridge(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

            val initialized = services.bridgeInit.isInitialized(name)
            val info = BridgeDetail(
                name = name,
                platform = bridge.platform.name,
                connected = bridge.isConnected(),
                initialized = initialized,
                supportsAttachments = bridge.platform.supportsAttachments,
                supportsInlineImages = bridge.platform.supportsInlineImages,
            )
            call.respond(info)
        }

        /**
         * POST /api/v1/bridges/{name}/connect
         * Trigger a bridge connection attempt.
         */
        post("/{name}/connect") {
            val name = call.parameters["name"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

            val bridge = services.bridges.getBridge(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

            try {
                bridge.connect()
                call.respond(HttpStatusCode.OK, mapOf("connected" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Connection failed: ${e.message}"))
            }
        }

        /**
         * POST /api/v1/bridges/{name}/disconnect
         * Disconnect a bridge.
         */
        post("/{name}/disconnect") {
            val name = call.parameters["name"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

            val bridge = services.bridges.getBridge(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

            try {
                bridge.disconnect()
                call.respond(HttpStatusCode.OK, mapOf("disconnected" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Disconnect failed: ${e.message}"))
            }
        }
    }
}
