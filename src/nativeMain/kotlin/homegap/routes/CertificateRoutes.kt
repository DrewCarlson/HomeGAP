package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.reconcile.Controller
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** The actions of the Certificates screen. None of them moves certificate material. */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class CertificateRoutes(private val controller: Controller) : RoutingController {
    override fun init(parent: Route) {
        parent.post("/certificates/order") {
            call.respond(ActionResponse(true, controller.orderCertificate(replaceKey = false)))
        }
        parent.post("/certificates/replace-key") {
            call.respond(ActionResponse(true, controller.orderCertificate(replaceKey = true)))
        }
        parent.post("/certificates/check") {
            call.respond(ActionResponse(true, controller.checkCertificates()))
        }
        parent.post("/certificates/nodes/{id}/resend") {
            val id = requireNotNull(call.parameters["id"]) { "node id required" }
            call.respond(ActionResponse(true, controller.resendCertificate(id)))
        }
    }
}
