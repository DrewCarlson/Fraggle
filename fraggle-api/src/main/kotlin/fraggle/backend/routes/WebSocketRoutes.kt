package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import fraggle.api.FraggleServices
import fraggle.backend.websocket.configureWebSockets
import fraggle.di.AppScope
import io.ktor.server.routing.*

/**
 * WebSocket endpoint for real-time event streaming.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class WebSocketRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.configureWebSockets(services)
    }
}
