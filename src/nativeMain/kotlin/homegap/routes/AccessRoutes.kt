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
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class AccessRoutes(private val access: AccessService) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/access") {
            get { call.respond(access.overview()) }
            post("/users") { call.respond(access.createUser(call.receive<CreateAccessUserRequest>())) }
            delete("/users/{id}") { call.respond(ActionResponse(true, access.removeUser(param("id")))) }
            put("/users/{id}/roles") {
                call.respond(ActionResponse(true, access.setRoles(param("id"), call.receive<SetAccessRolesRequest>().roleIds)))
            }
            delete("/invitations/{id}") { call.respond(ActionResponse(true, access.removeInvitation(param("id")))) }
            post("/devices/{id}/{action}") {
                val id = requireNotNull(param("id").toIntOrNull()) { "device id must be a number" }
                call.respond(ActionResponse(true, access.deviceAction(id, param("action"))))
            }
        }
    }

    private fun RoutingContext.param(name: String): String = requireNotNull(call.parameters[name]) { "$name required" }
}
