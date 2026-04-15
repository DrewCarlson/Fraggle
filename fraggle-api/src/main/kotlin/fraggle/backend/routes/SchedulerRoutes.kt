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
 * Task scheduler routes.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SchedulerRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.apply {
            route("/scheduler") {
                get("/tasks") {
                    call.respond(services.scheduler.getTasks())
                }

                get("/tasks/{id}") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task ID"))

                    val task = services.scheduler.getTask(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Task not found"))

                    call.respond(task)
                }

                delete("/tasks/{id}") {
                    val id = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing task ID"))

                    if (services.scheduler.cancelTask(id)) {
                        call.respond(HttpStatusCode.OK, mapOf("cancelled" to true))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Task not found"))
                    }
                }
            }
        }
    }
}
