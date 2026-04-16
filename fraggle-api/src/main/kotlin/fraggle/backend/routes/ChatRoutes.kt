package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import fraggle.models.ErrorResponse
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Chat history routes backed by the database.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ChatRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/chats") {
            get { listChats() }
            get("/{id}") { getChat() }
            get("/{id}/messages") { getMessages() }
        }
    }

    suspend fun RoutingContext.listChats() {
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toLongOrNull() ?: 0L
        call.respond(services.chatHistory.listChats(limit, offset))
    }

    suspend fun RoutingContext.getChat() {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid chat ID"))

        val chat = services.chatHistory.getChat(id)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Chat not found"))

        call.respond(chat)
    }

    suspend fun RoutingContext.getMessages() {
        val id = call.parameters["id"]?.toLongOrNull()
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid chat ID"))

        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toLongOrNull() ?: 0L
        call.respond(services.chatHistory.getMessages(id, limit, offset))
    }
}
