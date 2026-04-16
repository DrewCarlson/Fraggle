package fraggle.backend.routes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import fraggle.api.FraggleServices
import fraggle.di.AppScope
import fraggle.models.BridgeDetail
import fraggle.models.BridgeInfo
import fraggle.models.ErrorResponse

/**
 * Bridge management routes.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class BridgeRoutes(
    private val services: FraggleServices,
) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/bridges") {
            get { listBridges() }
            get("/{name}") { getBridge() }
            post("/{name}/connect") { connectBridge() }
            post("/{name}/disconnect") { disconnectBridge() }
        }
    }

    suspend fun RoutingContext.listBridges() {
        val bridges = services.bridges.registeredBridges().mapNotNull { name ->
            val bridge = services.bridges.getBridge(name) ?: return@mapNotNull null
            val initialized = services.bridgeInit.isInitialized(name)
            BridgeInfo(
                name = name,
                platform = bridge.platform.name,
                connected = bridge.isConnected(),
                initialized = initialized,
                persistentActivation = bridge.platform.persistentActivation,
            )
        }
        call.respond(bridges)
    }

    suspend fun RoutingContext.getBridge() {
        val name = call.parameters["name"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

        val bridge = services.bridges.getBridge(name)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

        val initialized = services.bridgeInit.isInitialized(name)
        val info = BridgeDetail(
            name = name,
            platform = bridge.platform.name,
            connected = bridge.isConnected(),
            initialized = initialized,
            supportsAttachments = bridge.platform.supportsAttachments,
            supportsInlineImages = bridge.platform.supportsInlineImages,
        )
        call.respond(info)
    }

    suspend fun RoutingContext.connectBridge() {
        val name = call.parameters["name"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

        services.bridges.getBridge(name)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

        try {
            services.bridges.connect(name)
            call.respond(HttpStatusCode.OK, mapOf("connected" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Connection failed: ${e.message}"))
        }
    }

    suspend fun RoutingContext.disconnectBridge() {
        val name = call.parameters["name"]
            ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing bridge name"))

        services.bridges.getBridge(name)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorResponse("Bridge not found"))

        try {
            services.bridges.disconnect(name)
            call.respond(HttpStatusCode.OK, mapOf("disconnected" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Disconnect failed: ${e.message}"))
        }
    }
}
