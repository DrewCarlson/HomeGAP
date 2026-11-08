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
