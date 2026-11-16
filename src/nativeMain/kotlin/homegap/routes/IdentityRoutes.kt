package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.CreateIdentityUserRequest
import homegap.api.IdentityPasswordResponse
import homegap.api.IdentityUserDto
import homegap.api.SetIdentityRolesRequest
import homegap.identity.IdentityStore
import homegap.identity.User
import homegap.reconcile.ReconcileLog
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** The users of HomeGAP's own sign-in. Each change gets a new signature of the homelab. */
@Suppress("unused") // The dependency graph creates this class.
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class IdentityRoutes(private val identity: IdentityStore, private val log: ReconcileLog) : RoutingController {
    override fun init(parent: Route) {
        parent.route("/identity/users") {
            get { call.respond(identity.users().map { it.toDto() }) }
            post {
                val request = call.receive<CreateIdentityUserRequest>()
                val (user, password) = identity.create(request.email, request.name, request.roles)
                val message = "created the user ${user.email} (${user.roles.joinToString()})"
                log.change(DOMAIN, message)
                call.respond(IdentityPasswordResponse(message, user.toDto(), password))
            }
            put("/{id}/roles") {
                val user = identity.setRoles(id(), call.receive<SetIdentityRolesRequest>().roles)
                answer("set the roles of ${user.email} to ${user.roles.joinToString()}")
            }
            post("/{id}/password") {
                val password = identity.setPassword(id())
                val user = identity.users().first { it.id == id() }
                val message = "gave ${user.email} a new password"
                log.change(DOMAIN, message)
                call.respond(IdentityPasswordResponse(message, user.toDto(), password))
            }
            post("/{id}/block") { answer("blocked ${identity.setBlocked(id(), true).email}") }
            post("/{id}/unblock") { answer("removed the block of ${identity.setBlocked(id(), false).email}") }
            delete("/{id}") { answer("removed the user ${identity.remove(id()).email}") }
        }
    }

    private suspend fun RoutingContext.answer(message: String) {
        log.change(DOMAIN, message)
        call.respond(ActionResponse(true, message))
    }

    private fun RoutingContext.id(): String = requireNotNull(call.parameters["id"]) { "id required" }

    private fun User.toDto() = IdentityUserDto(id, email, name, roles, blocked, passwordHash != null, createdAt)

    private companion object {
        const val DOMAIN = "identity"
    }
}
