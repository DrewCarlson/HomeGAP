package homegap.provision

import homegap.config.AppConfig
import homegap.config.DashboardSsoConfig
import homegap.discovery.TraefikRouter
import homegap.model.DesiredState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The dynamic configuration for the homelab Traefik. Its HTTP provider
 * (`providers.http.endpoint`) reads it. The homelab and the nodes share one IP address for each
 * node and one wildcard certificate. Thus a browser can use a connection that it opened for a
 * homelab name to send a request for a name that only the nodes serve. Without a route, Traefik
 * answers 404. This route matches those names with TLS options that are not the options of the
 * connection. Traefik then answers 421 Misdirected Request, and the browser opens a new
 * connection, which DNS sends to a node.
 */
object HomelabTraefik {
    const val MISDIRECTED_ROUTER = "homegap-misdirected"

    /** The router and the service of the speed test and the status page. */
    const val PAGES = "homegap-pages"

    /** The router that gives the shared name of the status page to VPN devices. */
    const val PRIVATE_STATUS = "homegap-status-private"

    /** The router and the service of the dashboard. */
    const val DASHBOARD = "homegap-dashboard"

    /** The middleware that admits only the private path of VPN devices. */
    const val PRIVATE_ONLY = "homegap-private-only"

    /** The middleware that removes the sign-in headers from the request of the client. */
    const val SIGN_IN_HEADERS = "homegap-sign-in-headers"
    private const val PRIORITY = 100000

    /** Above the route that answers 421, which has the same name. */
    private const val PRIVATE_STATUS_PRIORITY = PRIORITY + 1

    /** The routes that are not routes of the homelab, although they have a name of the nodes. */
    private val OWN_ROUTERS = listOf(MISDIRECTED_ROUTER, PRIVATE_STATUS)

    /** Above a catch-all route of the homelab, and below the route that answers 421. */
    private const val PAGES_PRIORITY = 50000

    /**
     * The names that only the nodes serve: each management host, the client host, and the shared
     * names of the status page and the speed test. The result has no name that the homelab routes
     * itself. For such a name, Traefik drops the TLS options of this route, and this route hides
     * the route of the homelab. If [homelabRouters] is null, the result has no shared speed test
     * name, because the setup guide routes that name on the homelab.
     */
    fun nodeOnlyNames(plan: DesiredState, clientHost: String?, homelabRouters: List<TraefikRouter>?): List<String> {
        val nodes = plan.nodes.values
        val names = buildList {
            nodes.forEach { add(it.managementHost) }
            clientHost?.let(::add)
            nodes.firstNotNullOfOrNull { it.statusRedirect?.first }?.let(::add)
            nodes.firstNotNullOfOrNull { it.speedtestRedirect?.first }?.let(::add)
        }.map { it.lowercase() }.distinct()
        // After Traefik loads this route, the routes of the homelab include it. It is not a homelab route.
        val routed = homelabRouters
            ?.filterNot { isOwn(it.name) }
            ?.flatMap { hostsIn(it.rule) }
            ?.toSet()
            ?: setOfNotNull(nodes.firstNotNullOfOrNull { it.speedtestRedirect?.first }?.lowercase())
        return names.filter { it !in routed }.sorted()
    }

    /**
     * Returns true if [routerName] (as the Traefik API lists it) is this route. If an entry point
     * has defaults (its `http.tls` or `http.middlewares`), Traefik copies a route without entry
     * points into one route for each entry point, with the name `<entry point>-<name>`. Without
     * this check, those copies count as routes of the homelab and remove every name. The route
     * then goes away and comes back in each pass.
     */
    fun isOwn(routerName: String): Boolean {
        val name = routerName.substringBefore('@')
        return OWN_ROUTERS.any { name == it || name.endsWith("-$it") }
    }

    /** The plain `Host(...)` names in a Traefik rule. */
    fun hostsIn(rule: String): List<String> =
        HOST.findAll(rule)
            .flatMap { match -> match.groupValues[1].split(',') }
            .map { it.trim().trim('`', '"', ' ').lowercase() }
            .filter { it.isNotEmpty() }
            .toList()

    private val HOST = Regex("""\bHost\(([^)]*)\)""")

    /**
     * The names of the speed test and the status page that the homelab Traefik sends to the
     * orchestrator: the name of each node, and the shared name of the speed test. The config
     * enables each page.
     */
    fun pageHosts(config: AppConfig): List<String> = buildList {
        if (config.speedtest.enabled) {
            add(config.speedtest.autoHost(config.domain))
            config.nodes.forEach { add(config.speedtest.nodeHost(config.domain, it.id)) }
        }
        if (config.statusPage.enabled) {
            config.nodes.forEach { add(config.statusPage.nodeHost(config.domain, it.id)) }
        }
    }

    /**
     * The route of the dashboard: its name, the address of the dashboard, and the source range
     * that can use it. A null range gives the route to each source.
     */
    class Dashboard(val host: String, val url: String, val allowFrom: String?, val sso: DashboardSsoConfig? = null)

    /**
     * The pages that the orchestrator serves: the speed test and the status page. The route of
     * [hosts] goes to the listener at [origin]. With [privateStatus], the shared name of the status
     * page has a route too, only for the source range of VPN devices.
     */
    class Pages(val hosts: List<String>, val origin: String, val privateStatus: PrivateStatus? = null) {
        val isEmpty: Boolean get() = hosts.isEmpty() && privateStatus == null
    }

    /** The shared name of the status page, and the source range of VPN devices that can use it. */
    class PrivateStatus(val host: String, val allowFrom: String)

    private val NO_PAGES = Pages(emptyList(), origin = "")

    /** The configuration for [names]. It is empty if there are no names. */
    fun misdirectedConfig(names: List<String>): JsonObject = dynamicConfig(names, NO_PAGES)

    /**
     * The full configuration: the route that answers 421 for [misdirected], and the routes of
     * [pages]. With [dashboard], it also has the route of the dashboard. Each route is there only
     * if it has a name.
     */
    fun dynamicConfig(misdirected: List<String>, pages: Pages, dashboard: Dashboard? = null): JsonObject {
        if (misdirected.isEmpty() && pages.isEmpty && dashboard == null) return JsonObject(emptyMap())
        return buildJsonObject {
            putJsonObject("http") {
                putJsonObject("routers") {
                    putRouters(misdirected, pages)
                    dashboard?.let { putDashboardRouter(it) }
                }
                if (!pages.isEmpty || dashboard != null) {
                    putJsonObject("services") {
                        if (!pages.isEmpty) putService(PAGES, pages.origin)
                        dashboard?.let { putService(DASHBOARD, it.url) }
                    }
                }
                if (dashboard?.allowFrom != null || dashboard?.sso != null) {
                    putJsonObject("middlewares") { putDashboardMiddlewares(dashboard) }
                }
            }
            if (misdirected.isNotEmpty() || pages.privateStatus != null) {
                putJsonObject("tls") {
                    putJsonObject("options") {
                        putJsonObject(NodeTemplates.ELSEWHERE_TLS_OPTIONS) { put("minVersion", "VersionTLS12") }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putService(name: String, url: String) {
        putJsonObject(name) {
            putJsonObject("loadBalancer") {
                putJsonArray("servers") { addJsonObject { put("url", url) } }
            }
        }
    }

    private fun JsonObjectBuilder.putDashboardMiddlewares(dashboard: Dashboard) {
        dashboard.allowFrom?.let { range ->
            putJsonObject(PRIVATE_ONLY) {
                putJsonObject("ipAllowList") { putJsonArray("sourceRange") { add(range) } }
            }
        }
        dashboard.sso?.let { sso ->
            // An empty value removes the header. Only the sign-in middleware, which is next, writes it.
            putJsonObject(SIGN_IN_HEADERS) {
                putJsonObject("headers") {
                    putJsonObject("customRequestHeaders") { sso.trustedHeaders.forEach { put(it, "") } }
                }
            }
        }
    }

    /** The middlewares of the dashboard route, in their order: the source range, then the sign-in. */
    private fun middlewaresOf(dashboard: Dashboard): List<String> = buildList {
        if (dashboard.allowFrom != null) add(PRIVATE_ONLY)
        dashboard.sso?.let {
            add(SIGN_IN_HEADERS)
            add(it.middleware)
        }
    }

    private fun JsonObjectBuilder.putDashboardRouter(dashboard: Dashboard) {
        putJsonObject(DASHBOARD) {
            put("rule", hostRule(listOf(dashboard.host)))
            put("priority", PAGES_PRIORITY)
            put("service", DASHBOARD)
            putJsonObject("tls") {}
            val middlewares = middlewaresOf(dashboard)
            if (middlewares.isNotEmpty()) {
                putJsonArray("middlewares") { middlewares.forEach { add(it) } }
            }
        }
    }

    private fun JsonObjectBuilder.putRouters(misdirected: List<String>, pages: Pages) {
        if (misdirected.isNotEmpty()) {
            putJsonObject(MISDIRECTED_ROUTER) {
                put("rule", hostRule(misdirected))
                // Above the catch-all routes, which answer these names if this route does not.
                put("priority", PRIORITY)
                put("service", "noop@internal")
                putJsonObject("tls") { put("options", NodeTemplates.ELSEWHERE_TLS_OPTIONS) }
            }
        }
        if (pages.hosts.isNotEmpty()) {
            putJsonObject(PAGES) {
                put("rule", hostRule(pages.hosts))
                put("priority", PAGES_PRIORITY)
                put("service", PAGES)
                // The certificate comes from the TLS settings of the homelab Traefik.
                putJsonObject("tls") {}
            }
        }
        pages.privateStatus?.let { status ->
            putJsonObject(PRIVATE_STATUS) {
                put("rule", "${hostRule(listOf(status.host))} && ClientIP(`${status.allowFrom}`)")
                put("priority", PRIVATE_STATUS_PRIORITY)
                put("service", PAGES)
                // The route that answers 421 has the same name. Traefik uses the TLS options of a
                // name only if each of its routes has the same options.
                putJsonObject("tls") { put("options", NodeTemplates.ELSEWHERE_TLS_OPTIONS) }
            }
        }
    }

    private fun hostRule(names: List<String>): String = names.joinToString(" || ") { "Host(`$it`)" }
}
