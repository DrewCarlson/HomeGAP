package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.reconcile.Controller
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * The speed tests that the dashboard controls: results, device test links and runs between the
 * homelab and a node. The measurement endpoints are on the separate speed test origin.
 */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SpeedTestRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/speedtest") {
            get { getOverview() }
            // The test between the homelab and a node that runs now. 204 when none runs.
            get("/live") {
                val live = controller.speedLive()
                if (live == null) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(live)
                }
            }
            post("/link") { createLink() }
            post("/nodes/{id}") { runTunnelTest() }
            // Every node, one at a time, on the orchestrator. The status snapshot has the progress.
            post("/runs") {
                val streams = call.request.queryParameters["streams"]?.toIntOrNull()
                call.respond(ActionResponse(true, controller.startSpeedRun(streams)))
            }
            delete("/runs") { call.respond(ActionResponse(true, controller.cancelSpeedRun())) }
        }
    }

    suspend fun RoutingContext.getOverview() {
        respondRevalidated(controller.speedOverview())
    }

    suspend fun RoutingContext.createLink() {
        call.respond(controller.createSpeedLink())
    }

    /**
     * Runs a test between the homelab and a node (about 30 s). `?streams=N` replaces the stream
     * count of the config.
     */
    suspend fun RoutingContext.runTunnelTest() {
        val nodeId = requireNotNull(call.parameters["id"]) { "node id required" }
        val streams = call.request.queryParameters["streams"]?.toIntOrNull()
        call.respond(controller.runTunnelSpeedTest(nodeId, streams))
    }
}
