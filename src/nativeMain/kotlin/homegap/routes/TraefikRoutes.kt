package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.dashboard.DashboardAuth
import homegap.provision.HomelabTraefik
import homegap.reconcile.Controller
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get

/**
 * `/api/traefik/homelab`: dynamic configuration for the homelab Traefik's HTTP provider (see
 * [HomelabTraefik]). Mounted outside the dashboard token: the homelab Traefik polls it without one.
 * [allowed] decides who may read it.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<OpenRoutingController>())
@Inject
class TraefikRoutes(private val controller: Controller) : OpenRoutingController {
    override fun init(parent: Route, auth: DashboardAuth) {
        parent.get(PATH) { homelab(auth) }
    }

    private suspend fun RoutingContext.homelab(auth: DashboardAuth) {
        val req = call.request
        val ok = allowed(
            auth = auth,
            authorization = req.headers[HttpHeaders.Authorization],
            peer = req.local.remoteAddress,
            forwarded = req.headers[HttpHeaders.XForwardedFor] != null || req.headers[HttpHeaders.Forwarded] != null,
            frontDoor = controller.frontDoorAddresses,
        )
        if (!ok) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }
        // Before the first plan, an empty configuration (Traefik keeps polling).
        val plan = controller.desired
        val names = plan?.let { HomelabTraefik.nodeOnlyNames(it, controller.config.clientHost, controller.homelabRouters) }.orEmpty()
        call.respondText(HomelabTraefik.misdirectedConfig(names).toString(), ContentType.Application.Json)
    }

    companion object {
        const val PATH = "/api/traefik/homelab"

        /**
         * The request carries the dashboard token, or it is the homelab Traefik's own poll. That
         * is a request whose sender is the front door container ([peer] in [frontDoor]) and that
         * Traefik did not forward for someone else: Traefik adds `X-Forwarded-For` to every
         * request it forwards (the dashboard itself is published through it), and a client cannot
         * remove it.
         */
        fun allowed(auth: DashboardAuth, authorization: String?, peer: String, forwarded: Boolean, frontDoor: Set<String>): Boolean = when {
            auth.matches(authorization?.removePrefix("Bearer ")?.takeIf { authorization.startsWith("Bearer ") }) -> true
            else -> !forwarded && peer in frontDoor
        }
    }
}
