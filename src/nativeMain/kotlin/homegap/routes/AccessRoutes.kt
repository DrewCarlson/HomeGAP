package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.access.AccessService
import homegap.api.ActionResponse
import homegap.api.CreateAccessMachineRequest
import homegap.api.CreateAccessUserRequest
import homegap.api.SetAccessRolesRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The users and devices of HomeGAP's own sign-in, for the Access page. */
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
            post("/machines") {
                val created = access.createMachine(call.receive<CreateAccessMachineRequest>())
                access.changed()
                call.respond(created)
            }
            delete("/users/{id}") { answer(access.removeUser(param("id"))) }
            put("/users/{id}/roles") {
                val roles = call.receive<SetAccessRolesRequest>().roleIds
                answer(access.setRoles(param("id"), roles))
            }
            post("/users/{id}/password") {
                val created = access.newPassword(param("id"))
                access.changed()
                call.respond(created)
            }
            post("/users/{id}/{action}") { answer(access.userAction(param("id"), param("action"))) }
            post("/devices/{id}/{action}") {
                val id = requireNotNull(param("id").toIntOrNull()) { "device id must be a number" }
                answer(access.deviceAction(id, param("action")))
            }
        }
    }

    private suspend fun RoutingContext.answer(message: String) {
        access.changed()
        call.respond(ActionResponse(true, message))
    }

    private fun RoutingContext.param(name: String): String =
        requireNotNull(call.parameters[name]) { "$name required" }
}
