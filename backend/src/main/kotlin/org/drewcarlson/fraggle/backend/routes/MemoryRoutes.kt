package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.memory.MemoryScope
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.FactInfo
import org.drewcarlson.fraggle.models.MemoryResponse
import kotlin.time.Instant

/**
 * Memory store routes.
 */
fun Route.memoryRoutes(services: FraggleServices) {
    route("/memory") {
        /**
         * GET /api/v1/memory/global
         * Get global memory facts.
         */
        get("/global") {
            val memory = services.memory.load(MemoryScope.Global)
            val response = MemoryResponse(
                scope = "global",
                facts = memory.facts.map { fact ->
                    FactInfo(
                        content = fact.content,
                        source = fact.source,
                        createdAt = fact.timestamp,
                    )
                },
            )
            call.respond(response)
        }

        /**
         * GET /api/v1/memory/chat/{chatId}
         * Get memory facts for a specific chat.
         */
        get("/chat/{chatId}") {
            val chatId = call.parameters["chatId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing chat ID"))

            val memory = services.memory.load(MemoryScope.Chat(chatId))
            val response = MemoryResponse(
                scope = "chat:$chatId",
                facts = memory.facts.map { fact ->
                    FactInfo(
                        content = fact.content,
                        source = fact.source,
                        createdAt = fact.timestamp,
                    )
                },
            )
            call.respond(response)
        }

        /**
         * GET /api/v1/memory/user/{userId}
         * Get memory facts for a specific user.
         */
        get("/user/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))

            val memory = services.memory.load(MemoryScope.User(userId))
            val response = MemoryResponse(
                scope = "user:$userId",
                facts = memory.facts.map { fact ->
                    FactInfo(
                        content = fact.content,
                        source = fact.source,
                        createdAt = fact.timestamp,
                    )
                },
            )
            call.respond(response)
        }

        /**
         * DELETE /api/v1/memory/global
         * Clear global memory.
         */
        delete("/global") {
            services.memory.clear(MemoryScope.Global)
            call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
        }

        /**
         * DELETE /api/v1/memory/chat/{chatId}
         * Clear memory for a specific chat.
         */
        delete("/chat/{chatId}") {
            val chatId = call.parameters["chatId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing chat ID"))

            services.memory.clear(MemoryScope.Chat(chatId))
            call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
        }

        /**
         * DELETE /api/v1/memory/user/{userId}
         * Clear memory for a specific user.
         */
        delete("/user/{userId}") {
            val userId = call.parameters["userId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))

            services.memory.clear(MemoryScope.User(userId))
            call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
        }
    }
}
