package org.drewcarlson.fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.drewcarlson.fraggle.api.FraggleServices
import org.drewcarlson.fraggle.models.ConversationDetail
import org.drewcarlson.fraggle.models.ConversationSummary
import org.drewcarlson.fraggle.models.ErrorResponse
import org.drewcarlson.fraggle.models.MessageInfo

/**
 * Conversation management routes.
 */
fun Route.conversationRoutes(services: FraggleServices) {
    route("/conversations") {
        /**
         * GET /api/v1/conversations
         * List all active conversations.
         */
        get {
            val conversations = services.conversations.getAll()
            val response = conversations.map { conv ->
                ConversationSummary(
                    id = conv.id,
                    chatId = conv.chatId,
                    messageCount = conv.messages.size,
                    lastMessageAt = conv.messages.lastOrNull()?.timestamp,
                )
            }
            call.respond(response)
        }

        /**
         * GET /api/v1/conversations/{id}
         * Get a specific conversation with full message history.
         */
        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID"))

            val conversation = services.conversations.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found"))

            val response = ConversationDetail(
                id = conversation.id,
                chatId = conversation.chatId,
                messages = conversation.messages.map { msg ->
                    MessageInfo(
                        role = msg.role.name.lowercase(),
                        content = msg.content,
                        timestamp = msg.timestamp,
                    )
                },
            )
            call.respond(response)
        }

        /**
         * DELETE /api/v1/conversations/{id}
         * Clear a conversation's history.
         */
        delete("/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID"))

            val cleared = services.conversations.clear(id)
            if (cleared) {
                call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found"))
            }
        }
    }
}
