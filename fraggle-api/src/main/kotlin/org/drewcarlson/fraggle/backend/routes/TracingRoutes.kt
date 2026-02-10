package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ErrorResponse

fun Route.tracingRoutes(services: FraggleServices) {
    route("/tracing") {
        get("/sessions") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
            val sessions = services.tracing.listSessions(limit, offset)
            call.respond(sessions)
        }

        get("/sessions/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))

            val detail = services.tracing.getSession(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))

            call.respond(detail)
        }
    }
}
