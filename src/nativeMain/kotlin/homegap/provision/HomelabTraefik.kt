package homegap.provision

import homegap.discovery.TraefikRouter
import homegap.model.DesiredState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Dynamic configuration for the homelab Traefik, served to its HTTP provider
 * (`providers.http.endpoint`). The homelab and the nodes share one IP per node and one wildcard
 * certificate, so a browser can send a request for a name only the nodes serve on a connection
 * it opened for a homelab name. Without a route, Traefik answers 404. This route matches those
 * names with TLS options other than the connection's, so Traefik answers 421 Misdirected Request
 * and the browser opens a new connection, which DNS sends to a node.
 */
object HomelabTraefik {
    const val MISDIRECTED_ROUTER = "homegap-misdirected"

    /**
     * The names only the nodes serve: each management host, the client host, and the shared status
     * and speed test names. A name the homelab routes itself is left out (Traefik would drop the
     * route's TLS options for it, and the route would hide the homelab's own). Without the homelab's
     * routes ([homelabRouters] null), the shared speed test name is left out, as the setup guide
     * routes it on the homelab.
     */
    fun nodeOnlyNames(plan: DesiredState, clientHost: String?, homelabRouters: List<TraefikRouter>?): List<String> {
        val nodes = plan.nodes.values
        val names = buildList {
            nodes.forEach { add(it.managementHost) }
            clientHost?.let(::add)
            nodes.firstNotNullOfOrNull { it.statusRedirect?.first }?.let(::add)
            nodes.firstNotNullOfOrNull { it.speedtestRedirect?.first }?.let(::add)
        }.map { it.lowercase() }.distinct()
        // This route itself comes back in the homelab's routes once loaded; it is not the homelab's.
        val routed = homelabRouters?.filterNot { it.name.substringBefore('@') == MISDIRECTED_ROUTER }
            ?.flatMap { hostsIn(it.rule) }?.toSet()
            ?: setOfNotNull(nodes.firstNotNullOfOrNull { it.speedtestRedirect?.first }?.lowercase())
        return names.filter { it !in routed }.sorted()
    }

    /** The plain `Host(...)` names in a Traefik rule. */
    fun hostsIn(rule: String): List<String> =
        HOST.findAll(rule).flatMap { m -> m.groupValues[1].split(',') }
            .map { it.trim().trim('`', '"', ' ').lowercase() }.filter { it.isNotEmpty() }.toList()

    private val HOST = Regex("""\bHost\(([^)]*)\)""")

    /** The configuration for [names]; empty when there are none. */
    fun misdirectedConfig(names: List<String>): JsonObject = buildJsonObject {
        if (names.isEmpty()) return@buildJsonObject
        putJsonObject("http") {
            putJsonObject("routers") {
                putJsonObject(MISDIRECTED_ROUTER) {
                    put("rule", names.joinToString(" || ") { "Host(`$it`)" })
                    // Above catch-all routes, which would otherwise answer these names.
                    put("priority", 100000)
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
