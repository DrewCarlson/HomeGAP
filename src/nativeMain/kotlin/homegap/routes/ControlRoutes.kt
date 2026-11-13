package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.PauseRequest
import homegap.api.SwitchoverRequest
import homegap.reconcile.APPLY_ONCE
import homegap.reconcile.Controller
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post

/** Controller-wide actions: trigger a pass, pause or resume reconciliation. */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ControlRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.post("/resync") { resync() }
        parent.post("/pause") { setPause() }
        // Planned maintenance of the homelab's member (deploy/upgrade.sh, through `homegap maintenance`).
        parent.post("/cluster/maintenance/start") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: DEFAULT_MAINTENANCE_MINUTES
            call.respond(ActionResponse(true, controller.beginMaintenance(minutes)))
        }
        parent.post("/cluster/maintenance/end") {
            call.respond(ActionResponse(true, controller.endMaintenance()))
        }
        parent.post("/undo/{id}") {
            val id = requireNotNull(call.parameters["id"]) { "undo id required" }
            call.respond(ActionResponse(true, controller.undo(id)))
        }
        parent.post("/tunnels/{id}/restart") {
            val id = requireNotNull(call.parameters["id"]) { "node id required" }
            call.respond(ActionResponse(true, controller.restartTunnel(id)))
        }
        parent.post("/cluster/switchover") {
            val to = call.receive<SwitchoverRequest>().to
            call.respond(ActionResponse(true, controller.switchPrimary(to)))
        }
    }

    /** `?apply=once` runs the pass without the global pause, for this pass only ("apply just this change"). */
    suspend fun RoutingContext.resync() {
        controller.trigger("dashboard")
        if (call.request.queryParameters["apply"] == "once") {
            controller.trigger(APPLY_ONCE)
            call.respond(ActionResponse(true, "Applying the waiting changes once; changes stay paused"))
        } else {
            call.respond(ActionResponse(true, "Resync queued"))
        }
    }

    /** Pauses or resumes everything, one node, or one service (`PauseRequest.scope`). */
    suspend fun RoutingContext.setPause() {
        val request = call.receive<PauseRequest>()
        call.respond(ActionResponse(true, controller.setPause(request.scope, request.id, request.paused)))
    }

    private companion object {
        const val DEFAULT_MAINTENANCE_MINUTES = 30
    }
}
