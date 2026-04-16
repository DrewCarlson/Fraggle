package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Settings and configuration routes.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SettingsRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/settings") {
            get("/config") { getConfig() }
        }
    }

    suspend fun RoutingContext.getConfig() {
        call.respond(services.config.getConfig())
    }
}
