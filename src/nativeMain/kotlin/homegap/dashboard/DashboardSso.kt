package homegap.dashboard

import homegap.config.DashboardSsoConfig

/** What a user of the dashboard can do. */
enum class DashboardRole { ADMIN, READ_ONLY }

/** The user of a request: the name from the sign-in service, or [TOKEN_USER] for the dashboard token. */
data class DashboardUser(val name: String, val role: DashboardRole) {
    companion object {
        const val TOKEN_USER = "token"
    }
}

/** The parts of a request that the sign-in check reads. */
class SsoRequest(val peer: String, val host: String?, val user: String?, val groups: String?)

/**
 * Sign-in to the dashboard through a forward-auth middleware of the homelab Traefik, for example
 * Authelia. Traefik asks the sign-in service for each request to [host] and adds the name and the
 * groups of the user as headers. The route that HomeGAP gives Traefik removes these headers from
 * the request of the client first, so a client cannot write them.
 */
class DashboardSso(val host: String, private val config: DashboardSsoConfig) {
    /** The names of the headers with the user and the groups. */
    val headers: Pair<String, String> get() = config.userHeader to config.groupsHeader

    /**
     * The user of [request], or null if the request has no sign-in that counts. Only the front
     * door ([frontDoor]) sends such a request, and only for the name of the dashboard. A client
     * that reaches the orchestrator in another way can write each header.
     */
    fun user(request: SsoRequest, frontDoor: Set<String>): DashboardUser? {
        if (request.peer !in frontDoor || !host.equals(request.host?.substringBefore(':'), ignoreCase = true)) {
            return null
        }
        val name = request.user?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val groups = request.groups.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val role = when {
            config.groups.any { it in groups } -> DashboardRole.ADMIN
            config.readOnlyGroups.any { it in groups } -> DashboardRole.READ_ONLY
            // With no groups for full access in the config, each other user has full access.
            config.groups.isEmpty() -> DashboardRole.ADMIN
            else -> return null
        }
        return DashboardUser(name, role)
    }
}
