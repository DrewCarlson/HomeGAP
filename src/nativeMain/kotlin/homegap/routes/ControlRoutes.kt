package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.PauseRequest
import homegap.reconcile.Controller
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post

/** Controller-wide actions: trigger a pass, pause or resume reconciliation. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ControlRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.post("/resync") { resync() }
        parent.post("/pause") { setPause() }
        // Planned maintenance of the homelab's member (deploy/upgrade.sh, via `homegap maintenance`).
        parent.post("/cluster/maintenance/start") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: 30
            call.respond(homegap.api.ActionResponse(true, controller.beginMaintenance(minutes)))
        }
        parent.post("/cluster/maintenance/end") {
            call.respond(homegap.api.ActionResponse(true, controller.endMaintenance()))
        }
        parent.post("/cluster/switchover") {
            val to = call.receive<homegap.api.SwitchoverRequest>().to
            call.respond(homegap.api.ActionResponse(true, controller.switchPrimary(to)))
        }
    }

    suspend fun RoutingContext.resync() {
        controller.trigger("dashboard")
        call.respond(ActionResponse(true, "Resync queued"))
    }

    /** Pauses or resumes everything, one node, or one service (`PauseRequest.scope`). */
    suspend fun RoutingContext.setPause() {
        val request = call.receive<PauseRequest>()
        call.respond(ActionResponse(true, controller.setPause(request.scope, request.id, request.paused)))
    }
}
