package fraggle.backend.routes

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import fraggle.di.AppScope
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * A self-contained unit of API routing. Implementations are registered into the
 * Metro graph via `@ContributesIntoSet(..., binding = binding<RoutingController>())`
 * and collected by [RoutingControllers], which installs them all under `/api/v1`.
 */
interface RoutingController {
    fun init(parent: Route)
}

/**
 * Aggregates every [RoutingController] contributed to [AppScope] and installs them
 * under a shared `/api/v1` prefix when the Ktor [Application] is configured.
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
) {
    fun init(application: Application) {
        application.routing {
            route("/api/v1") {
                controllers.forEach { it.init(this) }
            }
        }
    }
}
