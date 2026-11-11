package homegap.routes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import homegap.api.ActionResponse
import homegap.api.CreateShareLinkRequest
import homegap.api.CreateShareLinkResponse
import homegap.api.SessionDto
import homegap.api.SessionRole
import homegap.api.ShareLinksResponse
import homegap.config.AppConfig
import homegap.dashboard.ShareLinks
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** `GET /session`: whether this token is an admin's or a share link's. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class SessionRoutes(private val config: AppConfig) : RoutingController {
    override fun init(parent: Route) {
        parent.get("/session") {
            val viewer = call.principal<Viewer>()
            call.respond(
                SessionDto(
                    role = if (viewer != null) SessionRole.VIEWER else SessionRole.ADMIN,
                    shareLinks = config.dashboard.authToken() != null,
                    label = viewer?.link?.label,
                    expiresAt = viewer?.link?.expiresAt,
                )
            )
        }
    }
}

/** Read-only share links: list, create and revoke. Share links need a dashboard token. */
@SingleIn(AppScope::class)
@ContributesIntoSet(scope = AppScope::class, binding = binding<RoutingController>())
@Inject
class ShareLinkRoutes(
    private val config: AppConfig,
    private val links: ShareLinks,
) : RoutingController {
    override val adminOnly: Boolean get() = true

    override fun init(parent: Route) {
        parent.route("/share-links") {
            get {
                val enabled = enabled()
                call.respond(
                    ShareLinksResponse(
                        enabled = enabled,
                        links = if (enabled) links.list() else emptyList(),
                    )
                )
            }
            post {
                // Without a dashboard token everyone has full control: a read-only link means nothing.
                check(enabled()) { "Set a dashboard token (dashboard.auth_token_env) to use share links." }
                val request = call.receive<CreateShareLinkRequest>()
                val days = request.expiresInDays
                require(days == null || days in 1..MAX_DAYS) { "expiresInDays must be between 1 and $MAX_DAYS" }
                val (link, token) = links.create(
                    label = request.label,
                    ttlMillis = days?.let { it * DAY_MS },
                )
                call.respond(CreateShareLinkResponse(link, token))
            }
            delete("/{id}") {
                val id = requireNotNull(call.parameters["id"]) { "id required" }
                check(links.revoke(id)) { "No share link $id." }
                call.respond(ActionResponse(true, "Share link revoked."))
            }
        }
    }

    private fun enabled(): Boolean = config.dashboard.authToken() != null

    private companion object {
        const val MAX_DAYS = 365
        const val DAY_MS = 86_400_000L
    }
}
