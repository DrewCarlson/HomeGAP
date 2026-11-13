package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.access.AccessService
import homegap.api.ActionResponse
import homegap.api.CreateAccessUserRequest
import homegap.api.SetAccessRolesRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The cluster's users, invitations and devices. */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class AccessRoutes(private val access: AccessService) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/access") {
            get { respondRevalidated(access.overview()) }
            post("/users") {
                val created = access.createUser(call.receive<CreateAccessUserRequest>())
                access.changed()
                call.respond(created)
            }
            delete("/users/{id}") {
                val (message, undo) = access.removeUser(param("id"))
                call.respond(ActionResponse(true, message, undo))
            }
            put("/users/{id}/roles") {
                val roles = call.receive<SetAccessRolesRequest>().roleIds
                val message = access.setRoles(param("id"), roles)
                access.changed()
                call.respond(ActionResponse(true, message))
            }
            post("/invitations/{id}/reissue") { call.respond(access.reissueInvitation(param("id"))) }
            delete("/invitations/{id}") {
                val message = access.removeInvitation(param("id"))
                access.changed()
                call.respond(ActionResponse(true, message))
            }
            post("/devices/{id}/{action}") {
                val id = requireNotNull(param("id").toIntOrNull()) { "device id must be a number" }
                val message = access.deviceAction(id, param("action"))
                access.changed()
                call.respond(ActionResponse(true, message))
            }
        }
    }

    private fun RoutingContext.param(name: String): String =
        requireNotNull(call.parameters[name]) { "$name required" }
}
