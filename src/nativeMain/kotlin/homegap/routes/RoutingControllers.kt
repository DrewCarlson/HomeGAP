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
 * dashboard bearer token (Ktor's `Authentication` with a `bearer` provider). An
 * [OpenRoutingController] is mounted outside it and does its own check.
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
    private val config: AppConfig,
    private val open: Set<OpenRoutingController>,
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
            // Their own checks (the homelab Traefik polls without the token).
            open.forEach { it.init(this, token) }
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

/**
 * Routes mounted outside the dashboard token, each with its own check; [token] is the dashboard
 * token, or null when none is set. Contributed with `@ContributesIntoSet`.
 */
interface OpenRoutingController {
    fun init(parent: Route, token: String?)
}
