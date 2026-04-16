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

@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class TracingRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/tracing") {
            get("/sessions") { listSessions() }
            get("/sessions/{id}") { getSession() }
        }
    }

    suspend fun RoutingContext.listSessions() {
        val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
        val offset = call.parameters["offset"]?.toIntOrNull() ?: 0
        call.respond(services.tracing.listSessions(limit, offset))
    }

    suspend fun RoutingContext.getSession() {
        val id = call.parameters["id"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))

        val detail = services.tracing.getSession(id)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))

        call.respond(detail)
    }
}
