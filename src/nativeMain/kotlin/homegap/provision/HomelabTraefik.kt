package homegap.provision

import homegap.discovery.TraefikRouter
import homegap.model.DesiredState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private const val PRIORITY = 100000

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
        return name == MISDIRECTED_ROUTER || name.endsWith("-$MISDIRECTED_ROUTER")
    }

    /** The plain `Host(...)` names in a Traefik rule. */
    fun hostsIn(rule: String): List<String> =
        HOST.findAll(rule)
            .flatMap { match -> match.groupValues[1].split(',') }
            .map { it.trim().trim('`', '"', ' ').lowercase() }
            .filter { it.isNotEmpty() }
            .toList()

    private val HOST = Regex("""\bHost\(([^)]*)\)""")

    /** The configuration for [names]. It is empty if there are no names. */
    fun misdirectedConfig(names: List<String>): JsonObject = buildJsonObject {
        if (names.isEmpty()) return@buildJsonObject
        putJsonObject("http") {
            putJsonObject("routers") {
                putJsonObject(MISDIRECTED_ROUTER) {
                    put("rule", names.joinToString(" || ") { "Host(`$it`)" })
                    // Above the catch-all routes, which answer these names if this route does not.
                    put("priority", PRIORITY)
                    put("service", "noop@internal")
                    putJsonObject("tls") { put("options", NodeTemplates.ELSEWHERE_TLS_OPTIONS) }
                }
            }
        }
        putJsonObject("tls") {
            putJsonObject("options") {
                putJsonObject(NodeTemplates.ELSEWHERE_TLS_OPTIONS) { put("minVersion", "VersionTLS12") }
            }
        }
    }
}
