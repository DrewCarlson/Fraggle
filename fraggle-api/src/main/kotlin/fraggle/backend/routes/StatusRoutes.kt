package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Status and health check routes.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class StatusRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.apply {
            get("/status") {
                call.respond(services.getStatus())
            }
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }
        }
    }
}
