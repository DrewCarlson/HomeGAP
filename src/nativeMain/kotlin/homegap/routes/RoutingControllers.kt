package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.dashboard.DashboardAuth
import homegap.dashboard.DashboardRole
import homegap.dashboard.DashboardUser
import homegap.dashboard.SsoRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Mounts every [RoutingController] of the graph under `/api`, behind the dashboard bearer token
 * or the sign-in through the homelab Traefik (see [DashboardAuth]). There is always a token: the
 * API gives control of every node, so no request gets it without one of the two.
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
            provider(AUTH) {
                authenticate { context ->
                    val user = auth.user(context.call.request)
                    if (user != null) {
                        context.principal(user)
                    } else {
                        context.challenge(AUTH, AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
                            call.respond(HttpStatusCode.Unauthorized)
                            challenge.complete()
                        }
                    }
                }
            }
        }
        application.routing {
            // Their own checks (the homelab Traefik polls without the token).
            open.forEach { it.init(this, auth) }
            route("/api") {
                install(BlockedAddresses) { this.auth = this@RoutingControllers.auth }
                authenticate(AUTH) {
                    install(ReadOnlyUsers)
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

/** The user of [request]: the dashboard token, or the sign-in through the homelab Traefik. */
private fun DashboardAuth.user(request: ApplicationRequest): DashboardUser? {
    val authorization = request.headers[HttpHeaders.Authorization]
    val token = authorization?.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim()
    val headers = signInHeaders()
    val signIn = SsoRequest(
        peer = request.local.remoteAddress,
        host = request.headers[HttpHeaders.Host],
        user = headers?.let { request.headers[it.first] },
        groups = headers?.let { request.headers[it.second] },
    )
    return user(token, client(request), signIn)
}

private const val BEARER = "Bearer "

/** Refuses each request that changes something from a user who can only look. */
private val ReadOnlyUsers = createRouteScopedPlugin("ReadOnlyUsers") {
    on(AuthenticationChecked) { call ->
        val user = call.principal<DashboardUser>() ?: return@on
        if (user.role == DashboardRole.READ_ONLY && call.request.httpMethod != HttpMethod.Get) {
            val refusal = ActionResponse(false, "Your account can only view the dashboard.")
            val body = ApiJson.encodeToString(ActionResponse.serializer(), refusal)
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.Forbidden)
        }
    }
}

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
