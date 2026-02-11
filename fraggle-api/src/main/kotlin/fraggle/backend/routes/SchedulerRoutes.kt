package fraggle.backend.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.api.FraggleServices
import fraggle.models.ErrorResponse

/**
 * Task scheduler routes.
 */
fun Route.schedulerRoutes(services: FraggleServices) {
    route("/scheduler") {
        /**
         * GET /api/v1/scheduler/tasks
         * List all scheduled tasks.
         */
        get("/tasks") {
            val tasks = services.scheduler.getTasks()
            call.respond(tasks)
        }

        /**
         * GET /api/v1/scheduler/tasks/{id}
         * Get a specific scheduled task.
         */
        get("/tasks/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task ID"))

            val task = services.scheduler.getTask(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Task not found"))

            call.respond(task)
        }

        /**
         * DELETE /api/v1/scheduler/tasks/{id}
         * Cancel a scheduled task.
         */
        delete("/tasks/{id}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task ID"))

            val cancelled = services.scheduler.cancelTask(id)
            if (cancelled) {
                call.respond(HttpStatusCode.OK, mapOf("cancelled" to true))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Task not found"))
            }
        }
    }
}
