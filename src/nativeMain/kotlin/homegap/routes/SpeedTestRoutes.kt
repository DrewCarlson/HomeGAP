package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.reconcile.Controller
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * Speed tests as driven from the dashboard: results, device test links and homelab ↔ node runs.
 * (The measurement endpoints themselves live on the separate speed test origin.)
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SpeedTestRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/speedtest") {
            get { getOverview() }
            post("/link") { createLink() }
            post("/nodes/{id}") { runTunnelTest() }
        }
    }

    suspend fun RoutingContext.getOverview() {
        call.respond(controller.speedOverview())
    }

    suspend fun RoutingContext.createLink() {
        call.respond(controller.createSpeedLink())
    }

    /** Runs a homelab ↔ node test (~30 s); `?streams=N` overrides the configured stream count. */
    suspend fun RoutingContext.runTunnelTest() {
        val nodeId = requireNotNull(call.parameters["id"]) { "node id required" }
        val streams = call.request.queryParameters["streams"]?.toIntOrNull()
        call.respond(controller.runTunnelSpeedTest(nodeId, streams))
    }
}
