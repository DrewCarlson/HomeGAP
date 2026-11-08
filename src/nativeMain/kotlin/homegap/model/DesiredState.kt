package homegap.model

import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.discovery.*

/** Everything the controller wants to exist, computed purely from labels + config. */
data class DesiredState(
    val services: List<ServiceSpec>,
    val labelErrors: List<LabelIssue>,
    val labelWarnings: List<LabelIssue>,
    val frontDoor: ServiceSpec?,
    val nodes: Map<String, NodeDesired>,
    val dns: List<DesiredDnsRecord>,
    val primaryPrivate: List<PrivateResource>,
)

data class LabelIssue(val container: String, val message: String)

data class NodeDesired(
    val node: NodeConfig,
    val managementHost: String,
    val publicResources: List<PublicResource>,
    val privateResources: List<PrivateResource>,
    /** SNI passthrough routes added to the node's Traefik on :443 (file provider). */
    val sniRoutes: List<SniRoute>,
    /** Hostnames whose plain-HTTP (:80) requests are redirected to HTTPS by the node. */
    val httpsRedirectHosts: List<String>,
    val wildcardRedirectDomain: String?,
    /** Raw TCP/UDP ports that must be published on the node (Traefik entrypoints + firewall). */
    val rawPorts: List<RawPort>,
    val healthResourceNiceId: String?,
    val healthPath: String,
    /** Speed test entry redirect on :80 (auto hostname -> this node's hostname), when enabled. */
    val speedtestRedirect: Pair<String, String>? = null,
) {
    val id: String get() = node.id
}

data class RawPort(val port: Int, val protocol: String) : Comparable<RawPort> {
    val entryPoint: String get() = "$protocol-$port"
    override fun compareTo(other: RawPort): Int = compareValuesBy(this, other, { it.port }, { it.protocol })
}

enum class ResourceMode(val wire: String) { HTTP("http"), TCP("tcp"), UDP("udp") }

data class Target(val host: String, val port: Int, val method: String?)

data class PublicResource(
    val niceId: String,
    val serviceKey: String,
    val mode: ResourceMode,
    val fullDomain: String?,
    val proxyPort: Int?,
    val ssl: Boolean,
    val target: Target,
    val auth: AuthMode = AuthMode.NONE,
    val roles: List<String> = emptyList(),
    val pinEnv: String? = null,
    val hostHeader: String? = null,
    val tlsServerName: String? = null,
    val healthcheck: HealthCheck? = null,
    val paused: Boolean = false,
) {
    /** Traefik service name Pangolin generates for this resource (needs its numeric id). */
    fun traefikService(resourceId: Int): String = "$resourceId-$niceId-service@http"
}

data class PrivateResource(
    val niceId: String,
    val serviceKey: String,
    val name: String,
    val siteNiceId: String,
    val destination: String,
    val alias: String,
    val tcpPorts: String,
    val udpPorts: String,
    val roles: List<String>,
    val paused: Boolean,
)

data class SniRoute(
    val name: String,
    /** niceId of the Pangolin TCP resource whose generated service carries the stream. */
    val resourceNiceId: String,
    val exactHosts: List<String>,
    /** When set, also match any single label under this domain (`*.domain`). */
    val wildcardOf: String?,
    val excludeHosts: List<String>,
    val priority: Int,
)

data class DesiredDnsRecord(
    /** Record name relative to the zone ("" = apex, "*" = wildcard). */
    val name: String,
    val fqdn: String,
    val type: String,
    val value: String,
    val nodeId: String,
    val geo: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val latencyZone: String?,
    val monitor: String,
    val ttl: Int,
    val purpose: String,
) {
    /** Managed-ownership marker stored in the Bunny record comment. */
    val comment: String get() = "$OWNER_TAG $purpose node=$nodeId"

    companion object {
        const val OWNER_TAG = "managed-by:homegap"
    }
}

object Planner {
    const val NICE_PREFIX = "hg-"
    const val FRONTDOOR_TCP = "hg-frontdoor"
    const val HEALTH = "hg-health"
    const val SPEEDTEST = "hg-speedtest"
    const val SPEEDTEST_AUTO = "hg-speedtest-auto"

    fun plan(
        config: AppConfig,
        containers: List<DockerContainerSummary>,
        pausedServices: Set<String> = emptySet(),
        excludedNodes: Set<String> = emptySet(),
    ): DesiredState {
        val errors = mutableListOf<LabelIssue>()
        val warnings = mutableListOf<LabelIssue>()
        val parsed = mutableListOf<ServiceSpec>()
        val nodeIds = config.nodes.map { it.id }.toSet()

        for (c in containers.sortedBy { it.name }) {
            val r = parseLabels(c.id, c.name, c.labels, config.domain)
            r.errors.forEach { errors += LabelIssue(c.name, it) }
            r.warnings.forEach { warnings += LabelIssue(c.name, it) }
            val spec = r.spec ?: continue
            val unknown = spec.regions?.filter { it !in nodeIds }.orEmpty()
            if (unknown.isNotEmpty()) warnings += LabelIssue(c.name, "regions reference unknown node(s): ${unknown.joinToString()}")
            parsed += spec.copy(paused = spec.paused || spec.key in pausedServices)
        }

        // One container per hostname: first (by name) wins, the rest are reported.
        val services = mutableListOf<ServiceSpec>()
        for ((host, group) in parsed.groupBy { it.hostname }) {
            services += group.first()
            group.drop(1).forEach { errors += LabelIssue(it.containerName, "hostname $host is already claimed by ${group.first().containerName}") }
        }

        val frontDoor = services.firstOrNull { it.hostname == config.domain && it.access == Access.PUBLIC }
        val activeNodes = config.nodes.filter { it.id !in excludedNodes }

        val nodes = config.nodes.associate { node -> node.id to planNode(config, node, services, frontDoor) }
        val dns = planDns(config, activeNodes, services, frontDoor)
        val primary = config.primaryPangolin?.let { p ->
            services.filter { it.access == Access.PRIVATE }.map { privateResource(it, p.site) }
        } ?: emptyList()

        return DesiredState(services, errors, warnings, frontDoor, nodes, dns, primary)
    }

    private fun planNode(config: AppConfig, node: NodeConfig, services: List<ServiceSpec>, frontDoor: ServiceSpec?): NodeDesired {
        val site = config.siteNiceId(node.id)
        val mgmt = node.managementHost(config.domain)
        val mine = services.filter { it.servesNode(node.id) }
        val publicRes = mutableListOf<PublicResource>()
        val sni = mutableListOf<SniRoute>()
        val redirects = mutableListOf<String>()
        val raw = mutableSetOf<RawPort>()
        val passthroughKeys = mutableListOf<String>()
        var wildcardRedirect: String? = null

        val fd = frontDoor?.takeIf { it.servesNode(node.id) }
        val dedicated = mine.filter { it.access == Access.PUBLIC && it != frontDoor }
        val terminateDedicated = dedicated.filter { it.isHttpLike() && it.tls == TlsMode.TERMINATE }.map { it.hostname }

        val st = config.speedtest.takeIf { it.enabled && fd != null }
        val stHosts = st?.let { listOf(it.autoHost(config.domain), it.nodeHost(config.domain, node.id)) }.orEmpty()

        if (fd != null) {
            val method = if (fd.protocol == Protocol.HTTPS) "https" else "http"
            if (fd.tls == TlsMode.PASSTHROUGH && fd.protocol == Protocol.HTTPS) {
                passthroughKeys += FRONTDOOR_TCP
                publicRes += PublicResource(
                    niceId = FRONTDOOR_TCP, serviceKey = fd.key, mode = ResourceMode.TCP, fullDomain = null,
                    proxyPort = 0, ssl = false, target = Target(fd.targetHost, fd.port, null),
                    healthcheck = fd.healthcheck, paused = fd.paused,
                )
                sni += SniRoute(
                    name = "homegap-frontdoor",
                    resourceNiceId = FRONTDOOR_TCP,
                    // The speed test hostnames ride the front door to the homelab proxy.
                    exactHosts = listOf(fd.hostname) + if (fd.wildcard) emptyList() else stHosts,
                    wildcardOf = if (fd.wildcard) fd.hostname else null,
                    excludeHosts = (listOf(mgmt) + terminateDedicated).distinct().sorted(),
                    priority = 100,
                )
                redirects += fd.hostname
                if (fd.wildcard) wildcardRedirect = fd.hostname
            } else {
                publicRes += httpResource(NICE_PREFIX + fd.key, fd, fd.hostname, method)
                if (fd.wildcard) publicRes += httpResource(NICE_PREFIX + "wildcard-" + fd.key, fd, "*." + fd.hostname, method)
                if (st != null) {
                    publicRes += httpResource(SPEEDTEST_AUTO, fd, stHosts[0], method).copy(auth = AuthMode.NONE, roles = emptyList(), pinEnv = null)
                    publicRes += httpResource(SPEEDTEST, fd, stHosts[1], method).copy(auth = AuthMode.NONE, roles = emptyList(), pinEnv = null)
                }
            }
        }

        for (s in dedicated) {
            when (s.protocol) {
                Protocol.HTTP, Protocol.HTTPS -> {
                    if (s.tls == TlsMode.PASSTHROUGH) {
                        val nice = NICE_PREFIX + s.key
                        passthroughKeys += nice
                        publicRes += PublicResource(
                            niceId = nice, serviceKey = s.key, mode = ResourceMode.TCP, fullDomain = null, proxyPort = 0,
                            ssl = false, target = Target(s.targetHost, s.port, null), healthcheck = s.healthcheck, paused = s.paused,
                        )
                        sni += SniRoute("homegap-${s.key}", nice, listOf(s.hostname), null, emptyList(), 200)
                        redirects += s.hostname
                    } else {
                        publicRes += httpResource(NICE_PREFIX + s.key, s, s.hostname, if (s.protocol == Protocol.HTTPS) "https" else "http")
                    }
                }
                Protocol.TCP, Protocol.UDP -> {
                    val proto = s.protocol.label
                    val pp = s.publicPort ?: s.port
                    raw += RawPort(pp, proto)
                    publicRes += PublicResource(
                        niceId = "$NICE_PREFIX${s.key}-$proto", serviceKey = s.key,
                        mode = if (s.protocol == Protocol.TCP) ResourceMode.TCP else ResourceMode.UDP,
                        fullDomain = null, proxyPort = pp, ssl = false, target = Target(s.targetHost, s.port, null),
                        healthcheck = s.healthcheck?.takeIf { s.protocol == Protocol.TCP }, paused = s.paused,
                    )
                }
            }
        }

        // Stable internal proxy ports for passthrough TCP resources (never published).
        val ports = allocatePorts(passthroughKeys, config.pangolin.passthroughPortBase, raw.map { it.port }.toSet())
        val withPorts = publicRes.map { r -> if (r.niceId in ports) r.copy(proxyPort = ports.getValue(r.niceId)) else r }.toMutableList()

        // Health resource: lets Bunny's port-80 monitor observe node -> tunnel -> origin.
        val healthTarget = fd ?: dedicated.firstOrNull { it.isHttpLike() }
        var healthNice: String? = null
        var healthPath = "/"
        if (healthTarget != null) {
            healthNice = HEALTH
            healthPath = (healthTarget.healthcheck as? HealthCheck.Http)?.path ?: "/"
            withPorts += PublicResource(
                niceId = HEALTH, serviceKey = healthTarget.key, mode = ResourceMode.HTTP,
                fullDomain = "homegap-health-${node.id}.${config.domain}", proxyPort = null, ssl = false,
                target = Target(healthTarget.targetHost, healthTarget.port, if (healthTarget.protocol == Protocol.HTTPS) "https" else "http"),
                hostHeader = healthTarget.hostname,
                tlsServerName = healthTarget.hostname.takeIf { healthTarget.protocol == Protocol.HTTPS },
            )
        }

        val privateRes = mine.filter { it.access == Access.PRIVATE }.map { privateResource(it, site) }

        return NodeDesired(
            node = node,
            managementHost = mgmt,
            publicResources = withPorts.sortedBy { it.niceId },
            privateResources = privateRes.sortedBy { it.niceId },
            sniRoutes = sni.sortedBy { it.name },
            httpsRedirectHosts = redirects.distinct().sorted(),
            wildcardRedirectDomain = wildcardRedirect,
            rawPorts = raw.sorted(),
            healthResourceNiceId = healthNice,
            healthPath = healthPath,
            speedtestRedirect = st?.let { stHosts[0] to stHosts[1] },
        )
    }

    private fun httpResource(nice: String, s: ServiceSpec, fullDomain: String, method: String) = PublicResource(
        niceId = nice, serviceKey = s.key, mode = ResourceMode.HTTP, fullDomain = fullDomain, proxyPort = null,
        ssl = true, target = Target(s.targetHost, s.port, method), auth = s.auth, roles = s.roles, pinEnv = s.pinEnv,
        healthcheck = s.healthcheck, paused = s.paused,
    )

    private fun privateResource(s: ServiceSpec, site: String): PrivateResource {
        val tcp = if (s.protocol != Protocol.UDP) s.port.toString() else ""
        val udp = if (s.protocol == Protocol.UDP) s.port.toString() else ""
        return PrivateResource(
            niceId = NICE_PREFIX + s.key, serviceKey = s.key, name = s.hostname, siteNiceId = site,
            destination = s.targetHost, alias = s.hostname, tcpPorts = tcp, udpPorts = udp, roles = s.roles, paused = s.paused,
        )
    }

    private fun planDns(config: AppConfig, nodes: List<NodeConfig>, services: List<ServiceSpec>, frontDoor: ServiceSpec?): List<DesiredDnsRecord> {
        val out = mutableListOf<DesiredDnsRecord>()
        val zone = config.bunny.zone
        fun rel(fqdn: String) = if (fqdn == zone) "" else fqdn.removeSuffix(".$zone")
        val geo = config.bunny.routing != "none"

        fun geoSet(fqdn: String, purpose: String, eligible: List<NodeConfig>) {
            if (eligible.isEmpty()) return
            // A single record cannot fail over; Bunny only filters offline members of a set.
            for (n in eligible) {
                val loc = n.effectiveLocation()
                n.ipv4()?.let { ip ->
                    out += DesiredDnsRecord(rel(fqdn), fqdn, "A", ip, n.id, geo, loc?.latitude, loc?.longitude, n.latencyZone, config.bunny.monitor, config.bunny.ttl, purpose)
                }
                n.publicIpv6?.let { ip ->
                    out += DesiredDnsRecord(rel(fqdn), fqdn, "AAAA", ip, n.id, geo, loc?.latitude, loc?.longitude, n.latencyZone, config.bunny.monitor, config.bunny.ttl, purpose)
                }
            }
        }

        if (frontDoor != null) {
            val eligible = nodes.filter { frontDoor.servesNode(it.id) }
            geoSet(frontDoor.hostname, "root", eligible)
            if (frontDoor.wildcard) geoSet("*.${frontDoor.hostname}", "wildcard", eligible)
        }
        for (s in services) {
            if (s == frontDoor || s.access != Access.PUBLIC) continue
            geoSet(s.hostname, "service:${s.key}", nodes.filter { s.servesNode(it.id) })
        }
        if (frontDoor != null && config.speedtest.enabled) {
            val eligible = nodes.filter { frontDoor.servesNode(it.id) }
            val st = config.speedtest
            // The auto name is covered by the wildcard set when there is one.
            if (!frontDoor.wildcard) geoSet(st.autoHost(config.domain), "speedtest", eligible)
            for (n in eligible) {
                val h = st.nodeHost(config.domain, n.id)
                n.ipv4()?.let { out += DesiredDnsRecord(rel(h), h, "A", it, n.id, false, null, null, null, "none", config.bunny.ttl, "speedtest") }
                n.publicIpv6?.let { out += DesiredDnsRecord(rel(h), h, "AAAA", it, n.id, false, null, null, null, "none", config.bunny.ttl, "speedtest") }
            }
        }
        for (n in nodes) {
            val mgmt = n.managementHost(config.domain)
            n.ipv4()?.let { out += DesiredDnsRecord(rel(mgmt), mgmt, "A", it, n.id, false, null, null, null, "none", config.bunny.ttl, "management") }
            n.publicIpv6?.let { out += DesiredDnsRecord(rel(mgmt), mgmt, "AAAA", it, n.id, false, null, null, null, "none", config.bunny.ttl, "management") }
        }
        return out.sortedWith(compareBy({ it.name }, { it.type }, { it.nodeId }))
    }

    /** Deterministic, collision-free port assignment: hash into a window, probe forward. */
    fun allocatePorts(keys: List<String>, base: Int, avoid: Set<Int>): Map<String, Int> {
        val window = 5000
        val used = avoid.toMutableSet()
        val out = mutableMapOf<String, Int>()
        for (k in keys.distinct().sorted()) {
            var p = base + (fnv1a(k) % window.toUInt()).toInt()
            while (p in used) p = base + ((p - base + 1) % window)
            used += p
            out[k] = p
        }
        return out
    }

    fun fnv1a(s: String): UInt {
        var h = 2166136261u
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toUInt() and 0xffu)
            h *= 16777619u
        }
        return h
    }
}

fun ServiceSpec.isHttpLike(): Boolean = protocol == Protocol.HTTP || protocol == Protocol.HTTPS
