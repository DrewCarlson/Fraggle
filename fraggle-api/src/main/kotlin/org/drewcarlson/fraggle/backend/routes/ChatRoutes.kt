package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ErrorResponse

/**
 * Chat history routes backed by the database.
 */
fun Route.chatRoutes(services: FraggleServices) {
    route("/chats") {
        /**
         * GET /api/v1/chats?limit=50&offset=0
         * List all persisted chats with message counts.
         */
        get {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0L
            val chats = services.chatHistory.listChats(limit, offset)
            call.respond(chats)
        }

        /**
         * GET /api/v1/chats/{id}
         * Get a specific chat with statistics.
         */
        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid chat ID"))

            val chat = services.chatHistory.getChat(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Chat not found"))

            call.respond(chat)
        }

        /**
         * GET /api/v1/chats/{id}/messages?limit=50&offset=0
         * Get message metadata for a chat.
         */
        get("/{id}/messages") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid chat ID"))

            val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.parameters["offset"]?.toLongOrNull() ?: 0L
            val messages = services.chatHistory.getMessages(id, limit, offset)
            call.respond(messages)
        }
    }
}
