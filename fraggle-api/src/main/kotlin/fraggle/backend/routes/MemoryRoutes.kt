package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import fraggle.memory.MemoryScope
import fraggle.models.ErrorResponse
import fraggle.models.FactInfo
import fraggle.models.MemoryResponse
import fraggle.models.MemoryScopeInfo
import fraggle.models.MemoryScopesResponse
import fraggle.models.UpdateFactRequest

/**
 * Memory store routes.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class MemoryRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/memory") {
            get("/scopes") { listScopes() }
            get("/global") { getGlobal() }
            get("/chat/{chatId}") { getChat() }
            get("/user/{userId}") { getUser() }
            delete("/global") { clearGlobal() }
            delete("/chat/{chatId}") { clearChat() }
            delete("/user/{userId}") { clearUser() }
            put("/{type}/{id}/facts/{index}") { updateFact() }
            delete("/{type}/{id}/facts/{index}") { deleteFact() }
        }
    }

    suspend fun RoutingContext.listScopes() {
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

    suspend fun RoutingContext.getGlobal() {
        val memory = services.memory.load(MemoryScope.Global)
        call.respond(memory.toResponse("global"))
    }

    suspend fun RoutingContext.getChat() {
        val chatId = call.parameters["chatId"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing chat ID"))

        val memory = services.memory.load(MemoryScope.Chat(chatId))
        call.respond(memory.toResponse("chat:$chatId"))
    }

    suspend fun RoutingContext.getUser() {
        val userId = call.parameters["userId"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))

        val memory = services.memory.load(MemoryScope.User(userId))
        call.respond(memory.toResponse("user:$userId"))
    }

    suspend fun RoutingContext.clearGlobal() {
        services.memory.clear(MemoryScope.Global)
        call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
    }

    suspend fun RoutingContext.clearChat() {
        val chatId = call.parameters["chatId"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing chat ID"))

        services.memory.clear(MemoryScope.Chat(chatId))
        call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
    }

    suspend fun RoutingContext.clearUser() {
        val userId = call.parameters["userId"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))

        services.memory.clear(MemoryScope.User(userId))
        call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
    }

    suspend fun RoutingContext.updateFact() {
        val scope = resolveScope(call.parameters["type"], call.parameters["id"])
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid scope type"))
        val index = call.parameters["index"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid fact index"))

        val request = call.receive<UpdateFactRequest>()
        try {
            services.memory.updateFact(scope, index, request.content)
        } catch (e: IllegalArgumentException) {
            return call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Fact not found"))
        }

        val memory = services.memory.load(scope)
        call.respond(memory.toResponse(scope.toString()))
    }

    suspend fun RoutingContext.deleteFact() {
        val scope = resolveScope(call.parameters["type"], call.parameters["id"])
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid scope type"))
        val index = call.parameters["index"]?.toIntOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid fact index"))

        try {
            services.memory.deleteFact(scope, index)
        } catch (e: IllegalArgumentException) {
            return call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Fact not found"))
        }

        call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
    }
}

private fun fraggle.memory.Memory.toResponse(scope: String): MemoryResponse = MemoryResponse(
    scope = scope,
    facts = facts.map { fact ->
        FactInfo(
            content = fact.content,
            source = fact.source,
            createdAt = fact.timestamp,
            updatedAt = fact.updatedAt,
        )
    },
)

private fun resolveScope(type: String?, id: String?): MemoryScope? {
    return when (type) {
        "global" -> MemoryScope.Global
        "chat" -> id?.let { MemoryScope.Chat(it) }
        "user" -> id?.let { MemoryScope.User(it) }
        else -> null
    }
}
