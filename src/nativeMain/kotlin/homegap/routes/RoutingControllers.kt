package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.dashboard.DashboardAuth
import homegap.reconcile.ControllerLoop
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
 * Mounts every [RoutingController] contributed to the graph under `/api`, behind the dashboard
 * bearer token (Ktor's `Authentication` with a `bearer` provider). There is always a token (see
 * [DashboardAuth]): the API gives control of every node, so it is never served without one. An
 * [OpenRoutingController] is mounted outside it and does its own check.
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
    private val auth: DashboardAuth,
    private val open: Set<OpenRoutingController>,
    private val loop: ControllerLoop,
) {
    fun init(application: Application) {
        application.install(Authentication) {
            bearer(AUTH) {
                authenticate { credential ->
                    if (auth.matches(credential.token)) UserIdPrincipal("dashboard") else null
                }
            }
        }
        application.routing {
            // Their own checks (the homelab Traefik polls without the token).
            open.forEach { it.init(this, auth) }
            route("/api") {
                authenticate(AUTH) {
                    install(OnControllerLoop) { dispatcher = { loop.dispatcher } }
                    controllers.forEach { it.init(this) }
                }
            }
        }
    }

    private companion object {
        const val AUTH = "dashboard"
    }
}

class OnControllerLoopConfig {
    var dispatcher: () -> CoroutineDispatcher? = { null }
}

/** Runs each call of its routes on the event loop of the controller. See [ControllerLoop]. */
private val OnControllerLoop = createRouteScopedPlugin("OnControllerLoop", ::OnControllerLoopConfig) {
    val dispatcher = pluginConfig.dispatcher
    on(AroundCall) { proceed ->
        val loop = dispatcher()
        if (loop == null) proceed() else withContext(loop) { proceed() }
    }
}

private object AroundCall : Hook<suspend (suspend () -> Unit) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (suspend () -> Unit) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) { handler { proceed() } }
    }
}

/** A group of related `/api` routes; contributed to the graph with `@ContributesIntoSet`. */
interface RoutingController {
    fun init(parent: Route)
}

/**
 * Routes mounted outside the dashboard token, each with its own check against [DashboardAuth].
 * Contributed with `@ContributesIntoSet`.
 */
interface OpenRoutingController {
    fun init(parent: Route, auth: DashboardAuth)
}
