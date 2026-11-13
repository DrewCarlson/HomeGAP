package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.dashboard.DashboardAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Mounts every [RoutingController] of the graph under `/api`, behind the dashboard bearer token
 * (Ktor's `Authentication` with a `bearer` provider). There is always a token (see
 * [DashboardAuth]): the API gives control of every node, so no request gets it without a token.
 * An [OpenRoutingController] goes outside the token and does its own check.
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
    private val auth: DashboardAuth,
    private val open: Set<OpenRoutingController>,
) {
    fun init(application: Application) {
        application.install(Authentication) {
            bearer(AUTH) {
                authenticate { credential ->
                    if (auth.accepts(credential.token, auth.client(request))) UserIdPrincipal("dashboard") else null
                }
            }
        }
        application.routing {
            // Their own checks (the homelab Traefik polls without the token).
            open.forEach { it.init(this, auth) }
            route("/api") {
                install(BlockedAddresses) { this.auth = this@RoutingControllers.auth }
                authenticate(AUTH) {
                    controllers.forEach { it.init(this) }
                }
            }
        }
    }

    private companion object {
        const val AUTH = "dashboard"
    }
}

private fun DashboardAuth.client(request: ApplicationRequest): String =
    client(request.local.remoteAddress, request.headers[HttpHeaders.XForwardedFor])

private class BlockedConfig {
    lateinit var auth: DashboardAuth
}

/** Answers 429 to an address that sent too many wrong tokens, before the token check. */
private val BlockedAddresses = createRouteScopedPlugin("BlockedAddresses", ::BlockedConfig) {
    val auth = pluginConfig.auth
    onCall { call ->
        if (auth.blocked(auth.client(call.request))) {
            val refusal = ActionResponse(false, "Too many wrong tokens. Try again in a minute.")
            val body = ApiJson.encodeToString(ActionResponse.serializer(), refusal)
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
        }
    }
}

/** A group of related `/api` routes. `@ContributesIntoSet` adds it to the graph. */
interface RoutingController {
    fun init(parent: Route)
}

/**
 * Routes outside the dashboard token, each with its own check against [DashboardAuth].
 * `@ContributesIntoSet` adds it to the graph.
 */
interface OpenRoutingController {
    fun init(parent: Route, auth: DashboardAuth)
}
