package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ActionResponse
import homegap.api.ApiJson
import homegap.api.ShareLinkDto
import homegap.config.AppConfig
import homegap.dashboard.ShareLinks
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Mounts every [RoutingController] contributed to the graph under `/api`, behind the optional
 * dashboard bearer token (Ktor's `Authentication` with a `bearer` provider). An
 * [OpenRoutingController] is mounted outside it and does its own check.
 *
 * With a token set, a [ShareLinks] token also signs in, as a [Viewer]: it may only read (GET), and
 * never the routes of a controller marked [RoutingController.adminOnly].
 */
@SingleIn(AppScope::class)
@Inject
class RoutingControllers(
    private val controllers: Set<RoutingController>,
    private val config: AppConfig,
    private val open: Set<OpenRoutingController>,
    private val shareLinks: ShareLinks,
) {
    fun init(application: Application) {
        // Resolved once at startup, as the server is (changing the token needs a restart).
        val token = config.dashboard.authToken()?.resolve()
        if (token != null) {
            application.install(Authentication) {
                bearer(AUTH) {
                    authenticate { credential ->
                        when {
                            credential.token == token -> UserIdPrincipal("dashboard")
                            else -> shareLinks.verify(credential.token)?.let(::Viewer)
                        }
                    }
                }
            }
        }
        application.routing {
            // Their own checks (the homelab Traefik polls without the token).
            open.forEach { it.init(this, token) }
            route("/api") {
                if (token != null) {
                    authenticate(AUTH) {
                        install(ReadOnlyViewers)
                        mount()
                    }
                } else {
                    mount()
                }
            }
        }
    }

    private fun Route.mount() {
        val (adminOnly, shared) = controllers.partition { it.adminOnly }
        shared.forEach { it.init(this) }
        if (adminOnly.isNotEmpty()) {
            createChild(AdminOnlySelector).apply {
                install(AdminOnly)
                adminOnly.forEach { it.init(this) }
            }
        }
    }

    private companion object {
        const val AUTH = "dashboard"
    }
}

/** Signed in with a share link: may look, never change. */
data class Viewer(val link: ShareLinkDto)

/** A viewer only reads: anything but GET and HEAD is refused. */
private val ReadOnlyViewers = createRouteScopedPlugin("ReadOnlyViewers") {
    on(AuthenticationChecked) { call ->
        if (call.principal<Viewer>() != null && call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head) {
            call.forbid("This is a read-only view.")
        }
    }
}

/** Refuses viewers on every method. */
private val AdminOnly = createRouteScopedPlugin("AdminOnly") {
    on(AuthenticationChecked) { call ->
        if (call.principal<Viewer>() != null) {
            call.forbid("Only an admin can see this.")
        }
    }
}

/** 403 with an [ActionResponse], encoded here so it does not depend on content negotiation. */
private suspend fun ApplicationCall.forbid(message: String) = respondText(
    text = ApiJson.encodeToString(ActionResponse(false, message)),
    contentType = ContentType.Application.Json,
    status = HttpStatusCode.Forbidden,
)

/** Groups an admin-only controller's routes without adding to their paths. */
private object AdminOnlySelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent

    override fun toString() = "(admin only)"
}

/** A group of related `/api` routes; contributed to the graph with `@ContributesIntoSet`. */
interface RoutingController {
    /** Hidden from share-link viewers, even for reading (people's details, the share links). */
    val adminOnly: Boolean get() = false

    fun init(parent: Route)
}

/**
 * Routes mounted outside the dashboard token, each with its own check; [token] is the dashboard
 * token, or null when none is set. Contributed with `@ContributesIntoSet`.
 */
interface OpenRoutingController {
    fun init(parent: Route, token: String?)
}
