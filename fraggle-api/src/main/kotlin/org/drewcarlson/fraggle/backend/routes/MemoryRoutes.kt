package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.memory.MemoryScope
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.FactInfo
import org.drewcarlson.fraggle.models.MemoryResponse
import org.drewcarlson.fraggle.models.MemoryScopeInfo
import org.drewcarlson.fraggle.models.MemoryScopesResponse
import org.drewcarlson.fraggle.models.UpdateFactRequest

/**
 * Memory store routes.
 */
fun Route.memoryRoutes(services: FraggleServices) {
    route("/memory") {
        /**
         * GET /api/v1/memory/scopes
         * List all available memory scopes with fact counts.
         */
        get("/scopes") {
            val scopes = services.memory.listScopes()
            val scopeInfos = scopes.map { scope ->
                val memory = services.memory.load(scope)
                when (scope) {
                    is MemoryScope.Global -> MemoryScopeInfo(
                        type = "global",
                        id = "global",
                        label = "Global",
                        factCount = memory.facts.size,
                    )
                    is MemoryScope.Chat -> MemoryScopeInfo(
                        type = "chat",
                        id = scope.chatId,
                        label = scope.chatId,
                        factCount = memory.facts.size,
                    )
                    is MemoryScope.User -> MemoryScopeInfo(
                        type = "user",
                        id = scope.userId,
                        label = scope.userId,
                        factCount = memory.facts.size,
                    )
                }
            }
            call.respond(MemoryScopesResponse(scopeInfos))
        }

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
                        updatedAt = fact.updatedAt,
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
                        updatedAt = fact.updatedAt,
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
                        updatedAt = fact.updatedAt,
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

        /**
         * PUT /api/v1/memory/{type}/{id}/facts/{index}
         * Update a specific fact's content.
         */
        put("/{type}/{id}/facts/{index}") {
            val scope = resolveScope(call.parameters["type"], call.parameters["id"])
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid scope type"))
            val index = call.parameters["index"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid fact index"))

            val request = call.receive<UpdateFactRequest>()
            try {
                services.memory.updateFact(scope, index, request.content)
            } catch (e: IllegalArgumentException) {
                return@put call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Fact not found"))
            }

            val memory = services.memory.load(scope)
            call.respond(MemoryResponse(
                scope = scope.toString(),
                facts = memory.facts.map { fact ->
                    FactInfo(
                        content = fact.content,
                        source = fact.source,
                        createdAt = fact.timestamp,
                        updatedAt = fact.updatedAt,
                    )
                },
            ))
        }

        /**
         * DELETE /api/v1/memory/{type}/{id}/facts/{index}
         * Delete a specific fact.
         */
        delete("/{type}/{id}/facts/{index}") {
            val scope = resolveScope(call.parameters["type"], call.parameters["id"])
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid scope type"))
            val index = call.parameters["index"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid fact index"))

            try {
                services.memory.deleteFact(scope, index)
            } catch (e: IllegalArgumentException) {
                return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Fact not found"))
            }

            call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
        }
    }
}

private fun resolveScope(type: String?, id: String?): MemoryScope? {
    return when (type) {
        "global" -> MemoryScope.Global
        "chat" -> id?.let { MemoryScope.Chat(it) }
        "user" -> id?.let { MemoryScope.User(it) }
        else -> null
    }
}
