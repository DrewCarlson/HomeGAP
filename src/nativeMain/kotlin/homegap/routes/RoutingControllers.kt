package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Mounts every [RoutingController] contributed to the graph under `/api`, behind the optional
 * dashboard bearer token (Ktor's `Authentication` with a `bearer` provider).
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
    private val config: AppConfig,
) {
    fun init(application: Application) {
        // Resolved once at startup, as the server is (changing the token needs a restart).
        val token = config.dashboard.authToken()?.resolve()
        if (token != null) {
            application.install(Authentication) {
                bearer(AUTH) {
                    authenticate { credential -> if (credential.token == token) UserIdPrincipal("dashboard") else null }
                }
            }
        }
        application.routing {
            route("/api") {
                if (token != null) {
                    authenticate(AUTH) { mount() }
                } else {
                    mount()
                }
            }
        }
    }

    private fun Route.mount() = controllers.forEach { it.init(this) }

    private companion object {
        const val AUTH = "dashboard"
    }
}

/** A group of related `/api` routes; contributed to the graph with `@ContributesIntoSet`. */
interface RoutingController {
    fun init(parent: Route)
}
