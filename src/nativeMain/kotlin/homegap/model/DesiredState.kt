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
) {
    /**
     * The addresses this plan publishes for [nodeId]'s management host (its A and AAAA records),
     * the same values the DNS domain writes to Bunny.
     */
    fun managementAddresses(nodeId: String): List<String> =
        dns.filter { it.purpose == "management" && it.nodeId == nodeId }.map { it.value }
}

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
    /**
     * The speed test's shared name -> this node's name. The node answers the shared name itself
     * and passes each request on to the homelab under its own name, so the page always knows the
     * node it came through, whatever address the visitor opened.
     */
    val speedtestRedirect: Pair<String, String>? = null,
    /** The status page's shared name -> this node's name, answered the same way. */
    val statusRedirect: Pair<String, String>? = null,
    /** The cluster's client address, served by this node's Pangolin (login pages and API). */
    val clientHost: String? = null,
    /**
     * The names for which the node shows the maintenance page while the homelab is unreachable.
     * The node ends TLS for each of them. `*.<domain>` stands for each name one label under the
     * domain.
     */
    val maintenanceHosts: List<String> = emptyList(),
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
    /** TCP only: the node prepends a PROXY protocol v2 header with the viewer's address. */
    val proxyProtocol: Boolean = false,
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
    /** `host` (a service by alias) or `exit-node` (all traffic, leaving for the internet at the site). */
    val mode: String = "host",
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
    /** CAA records only: `issue` or `issuewild`. */
    val tag: String? = null,
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
    const val STATUS = "hg-status"
    const val PRIVATE_PATH_PREFIX = "hg-private--"
    /** The CAA records belong to the homelab, not to a node. */
    const val CAA_OWNER = "homelab"
    private const val CAA_TTL = 3600

    /**
     * A resource only one node serves (its health check, its speed test host). In a cluster all
     * nodes share one org, so these get the node's id.
     */
    fun perNode(config: AppConfig, base: String, nodeId: String) = if (config.cluster != null) "$base-$nodeId" else base

    fun isHealth(niceId: String) = niceId == HEALTH || niceId.startsWith("$HEALTH-")

    fun plan(
        config: AppConfig,
        containers: List<DockerContainerSummary>,
        pausedServices: Set<String> = emptySet(),
        excludedNodes: Set<String> = emptySet(),
        /** The routers each front door's Traefik API reported, by API address. */
        traefikRouters: Map<String, List<TraefikRouter>> = emptyMap(),
    ): DesiredState {
        val errors = mutableListOf<LabelIssue>()
        val warnings = mutableListOf<LabelIssue>()
        val parsed = mutableListOf<ServiceSpec>()
        val nodeIds = config.nodes.map { it.id }.toSet()

        for (c in containers.sortedBy { it.name }) {
            val r = parseContainerLabels(c.id, c.name, c.labels, config.domain)
            r.errors.forEach { errors += LabelIssue(c.name, it) }
            r.warnings.forEach { warnings += LabelIssue(c.name, it) }
            for (parsedSpec in r.specs) {
                var spec = parsedSpec
                val where = spec.name?.let { "services.$it: " }.orEmpty()
                spec.privateMiddleware?.let { mw ->
                    // Names from the routers of this Traefik that use the middleware (its API).
                    val routers = traefikRouters[spec.traefikApi]
                    if (routers == null) {
                        errors += LabelIssue(
                            c.name,
                            where + "${Labels.PRIVATE_MIDDLEWARE}: the Traefik API at ${spec.traefikApi} has not answered; set ${Labels.TRAEFIK_API} if it is elsewhere"
                        )
                        return@let
                    }
                    val (hosts, unreadable) = routerHosts(routers, mw)
                    val (inside, outside) = hosts.partition { it == spec.hostname || it.endsWith(".${spec.hostname}") }
                    outside.forEach {
                        warnings += LabelIssue(
                            c.name,
                            where + "$it uses $mw but is outside ${spec.hostname}: not given a private path"
                        )
                    }
                    unreadable.forEach {
                        warnings += LabelIssue(
                            c.name,
                            where + "router $it uses $mw but its names can't be read from its rule (HostRegexp or no Host); list them in ${Labels.PRIVATE_HOSTS}"
                        )
                    }
                    spec = spec.copy(privateHosts = (spec.privateHosts + inside).distinct())
                }
                spec.privateNetwork?.let { net ->
                    // Compose prefixes a project's networks with the project name.
                    val (onPrivate, others) = c.networkSettings.networks.entries.filter { it.value.ipAddress.isNotEmpty() }
                        .partition { (name, _) -> name == net || name.endsWith("_$net") }
                    val ip = onPrivate.singleOrNull()?.value?.ipAddress
                    if (ip == null) {
                        // Public traffic is unaffected: only the private path is left out.
                        errors += LabelIssue(
                            c.name,
                            where + "${Labels.PRIVATE_NETWORK}: the container has no address on a network named '$net'"
                        )
                        spec = spec.copy(privateNetwork = null, privateHosts = emptyList())
                        return@let
                    }
                    // Public traffic must not reach it over the private network: by name, Docker may
                    // answer with that address (the tunnels container is on both), and the service
                    // would see public traffic come from the private range. So it goes to the
                    // address on its other network, unless homegap.host names one.
                    if (Labels.HOST !in c.labels) {
                        when (others.size) {
                            1 -> spec = spec.copy(targetHost = others.single().value.ipAddress)
                            0 -> errors += LabelIssue(
                                c.name,
                                where + "${Labels.PRIVATE_NETWORK}: the container is only on '$net'; public traffic needs another network shared with the tunnels"
                            )

                            else -> errors += LabelIssue(
                                c.name,
                                "${Labels.PRIVATE_NETWORK}: the container is on several other networks (${others.joinToString { it.key }}); set ${Labels.HOST} to its address on the one shared with the tunnels, or public traffic may arrive over '$net'",
                            )
                        }
                    }
                    // A client resolves every name an alias covers into the tunnel, its own Pangolin
                    // address too: it would lose the cluster on its next reconnect.
                    val reserved =
                        listOfNotNull(config.clientHost) + config.nodes.map { it.managementHost(config.domain) }
                    val (clash, ok) = spec.privateHosts.partition { alias -> reserved.any { aliasCovers(alias, it) } }
                    clash.forEach { alias ->
                        errors += LabelIssue(
                            c.name,
                            where + "${Labels.PRIVATE_HOSTS}: '$alias' covers ${
                                reserved.first {
                                    aliasCovers(
                                        alias,
                                        it
                                    )
                                }
                            }, which Pangolin clients must reach directly; name the services instead"
                        )
                    }
                    spec = spec.copy(privateHost = ip, privateHosts = ok)
                }
                val unknown = spec.regions?.filter { it !in nodeIds }.orEmpty()
                if (unknown.isNotEmpty()) warnings += LabelIssue(
                    c.name,
                    where + "regions reference unknown node(s): ${unknown.joinToString()}"
                )
                parsed += spec.copy(paused = spec.paused || spec.key in pausedServices)
            }
        }

        val claimed = claim(parsed, errors, config.reservedHosts(), config.cluster?.mesh?.port)
        val frontDoor =
            claimed.firstOrNull { it.hostname == config.domain && it.access == Access.PUBLIC && !it.protocol.isRaw }
        val services = refuseCoveredPassthrough(claimed, frontDoor, errors)
        val activeNodes = config.nodes.filter { it.id !in excludedNodes }

        val planned = config.nodes.associate { node -> node.id to planNode(config, node, services, frontDoor) }
        val nodes = if (config.cluster != null) sharePassthroughPorts(config, planned) else planned
        val dns = planDns(config, activeNodes, services, frontDoor)
        return DesiredState(services, errors, warnings, frontDoor, nodes, dns)
    }

    /**
     * Which services keep their claims, in container-name order (first wins; the rest are
     * reported): one http/https service per hostname; raw services may share a hostname (with
     * each other and with one http/https service); one service per key; and on nodes they share,
     * one public service per public port and protocol.
     */
    private fun claim(parsed: List<ServiceSpec>, errors: MutableList<LabelIssue>, reserved: Set<String>, meshPort: Int?): List<ServiceSpec> {
        val kept = mutableListOf<ServiceSpec>()
        for (s in parsed) {
            fun refuse(why: String) {
                errors += LabelIssue(s.containerName, (s.name?.let { "services.$it: " }.orEmpty()) + why)
            }

            if (s.hostname.lowercase() in reserved) {
                refuse("hostname ${s.hostname} is reserved for HomeGAP"); continue
            }
            val byHost = kept.filter { it.hostname == s.hostname }
            val httpOwner = byHost.firstOrNull { !it.protocol.isRaw }
            if (!s.protocol.isRaw && httpOwner != null) {
                refuse("hostname ${s.hostname} is already claimed by ${httpOwner.label}"); continue
            }
            kept.firstOrNull { it.key == s.key }
                ?.let {
                    refuse("hostname ${s.hostname} is already claimed by ${it.label}; give this service a name under ${Labels.SERVICES_PREFIX}")
                    continue
                }
            if (s.access == Access.PUBLIC) {
                val mine = s.publicPorts()
                if (mine.any { it.port == meshPort && it.protocol == "udp" }) {
                    refuse("public port $meshPort/udp is the port of the cluster mesh"); continue
                }
                val clash = kept.filter { o -> o.access == Access.PUBLIC && overlaps(o.regions, s.regions) }
                    .firstNotNullOfOrNull { o -> o.publicPorts().firstOrNull { it in mine }?.let { o to it } }
                if (clash != null) {
                    refuse("public port ${clash.second.port}/${clash.second.protocol} is already used by ${clash.first.label}")
                    continue
                }
            }
            kept += s
        }
        return kept
    }

    /**
     * A front door in `terminate` mode with a wildcard puts the wildcard certificate on the nodes.
     * That certificate covers each name one label under the domain, so TLS passthrough for such a
     * name protects nothing. HomeGAP refuses the service and does not publish it.
     */
    private fun refuseCoveredPassthrough(services: List<ServiceSpec>, frontDoor: ServiceSpec?, errors: MutableList<LabelIssue>): List<ServiceSpec> {
        if (frontDoor == null || !frontDoor.wildcard || frontDoor.tls != TlsMode.TERMINATE) return services
        return services.filter { s ->
            val covered = s != frontDoor && s.access == Access.PUBLIC && s.isHttpLike() && s.tls == TlsMode.PASSTHROUGH &&
                homegap.acme.CertNames.covers(frontDoor.hostname, s.hostname)
            if (covered) errors += LabelIssue(
                s.containerName,
                (s.name?.let { "services.$it: " }.orEmpty()) + "${Labels.TLS}=passthrough is refused for ${s.hostname}: the front door ends TLS for *.${frontDoor.hostname} on the nodes, so the nodes hold a certificate for this name. Set ${Labels.TLS}=terminate here, or passthrough on the front door",
            )
            !covered
        }
    }

    private fun overlaps(a: List<String>?, b: List<String>?) = a == null || b == null || a.any { it in b }

    /** The public ports a raw service takes on its nodes. */
    private fun ServiceSpec.publicPorts(): Set<RawPort> =
        ports.flatMap { m -> m.protocols.map { RawPort(m.publicPort, it.label) } }.toSet()

    /**
     * A raw service's Pangolin resource for one port and protocol. A top-level service with a single
     * port keeps the id it had before port lists existed (`hg-<host>-tcp`), so upgrading recreates
     * nothing; the others add the public port.
     */
    fun rawNiceId(s: ServiceSpec, m: PortMapping, proto: Protocol): String =
        if (s.name == null && s.ports.size == 1 && m.protocols.size == 1) "$NICE_PREFIX${s.key}-${proto.label}"
        else "$NICE_PREFIX${s.key}-${m.publicPort}-${proto.label}"

    private fun planNode(
        config: AppConfig,
        node: NodeConfig,
        services: List<ServiceSpec>,
        frontDoor: ServiceSpec?
    ): NodeDesired {
        val site = config.siteNiceId(node.id)
        val mgmt = node.managementHost(config.domain)
        val mine = services.filter { it.servesNode(node.id) }
        val publicRes = mutableListOf<PublicResource>()
        val sni = mutableListOf<SniRoute>()
        val redirects = mutableListOf<String>()
        val raw = mutableSetOf<RawPort>()
        val passthroughKeys = mutableListOf<String>()
        val maintenance = mutableListOf<String>()
        var wildcardRedirect: String? = null

        val fd = frontDoor?.takeIf { it.servesNode(node.id) }
        val dedicated = mine.filter { it.access == Access.PUBLIC && it != frontDoor }
        val terminateDedicated = dedicated.filter { it.isHttpLike() && it.tls == TlsMode.TERMINATE }.map { it.hostname }

        val st = config.speedtest.takeIf { it.enabled && fd != null }
        val stHosts = st?.let { listOf(it.autoHost(config.domain), it.nodeHost(config.domain, node.id)) }.orEmpty()
        // The status page: the shared name is answered by the node itself (a redirect to its own
        // name, so the page knows the node), the per-node name rides the front door to the homelab.
        val sp = config.statusPage.takeIf { it.enabled && fd != null }
        val spAuto = sp?.autoHost(config.domain)
        val spNode = sp?.nodeHost(config.domain, node.id)

        if (fd != null) {
            val method = if (fd.protocol == Protocol.HTTPS) "https" else "http"
            if (fd.tls == TlsMode.PASSTHROUGH && fd.protocol == Protocol.HTTPS) {
                passthroughKeys += FRONTDOOR_TCP
                publicRes += PublicResource(
                    niceId = FRONTDOOR_TCP, serviceKey = fd.key, mode = ResourceMode.TCP, fullDomain = null,
                    proxyPort = 0, ssl = false, target = Target(fd.targetHost, fd.port, null),
                    healthcheck = fd.healthcheck, paused = fd.paused, proxyProtocol = fd.proxyProtocol,
                )
                sni += SniRoute(
                    name = "homegap-frontdoor",
                    resourceNiceId = FRONTDOOR_TCP,
                    // The per-node speed test and status names ride the front door to the homelab;
                    // the shared ones are answered by the node itself.
                    exactHosts = listOf(fd.hostname) + if (fd.wildcard) emptyList() else stHosts.drop(1) + listOfNotNull(
                        spNode
                    ),
                    wildcardOf = if (fd.wildcard) fd.hostname else null,
                    excludeHosts = (listOfNotNull(
                        mgmt,
                        config.clientHost,
                        spAuto,
                        stHosts.firstOrNull()
                    ) + terminateDedicated).distinct().sorted(),
                    priority = 100,
                )
                redirects += fd.hostname
                if (fd.wildcard) wildcardRedirect = fd.hostname
            } else {
                if (fd.maintenancePage) maintenance += listOf(fd.hostname) + (if (fd.wildcard) listOf("*.${fd.hostname}") else stHosts.drop(1) + listOfNotNull(spNode))
                publicRes += httpResource(NICE_PREFIX + fd.key, fd, fd.hostname, method)
                if (fd.wildcard) publicRes += httpResource(
                    NICE_PREFIX + "wildcard-" + fd.key,
                    fd,
                    "*." + fd.hostname,
                    method
                )
                if (st != null) {
                    publicRes += httpResource(
                        perNode(config, SPEEDTEST, node.id),
                        fd,
                        stHosts[1],
                        method
                    ).copy(auth = AuthMode.NONE, roles = emptyList(), pinEnv = null)
                }
                if (spNode != null) {
                    publicRes += httpResource(
                        perNode(config, STATUS, node.id),
                        fd,
                        spNode,
                        method
                    ).copy(auth = AuthMode.NONE, roles = emptyList(), pinEnv = null)
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
                            niceId = nice,
                            serviceKey = s.key,
                            mode = ResourceMode.TCP,
                            fullDomain = null,
                            proxyPort = 0,
                            ssl = false,
                            target = Target(s.targetHost, s.port, null),
                            healthcheck = s.healthcheck,
                            paused = s.paused,
                            proxyProtocol = s.proxyProtocol,
                        )
                        sni += SniRoute("homegap-${s.key}", nice, listOf(s.hostname), null, emptyList(), 200)
                        redirects += s.hostname
                    } else {
                        if (s.maintenancePage) maintenance += s.hostname
                        publicRes += httpResource(
                            NICE_PREFIX + s.key,
                            s,
                            s.hostname,
                            if (s.protocol == Protocol.HTTPS) "https" else "http"
                        )
                    }
                }

                Protocol.TCP, Protocol.UDP, Protocol.TCP_UDP -> for (m in s.ports) for (proto in m.protocols.sortedBy { it.ordinal }) {
                    val tcp = proto == Protocol.TCP
                    raw += RawPort(m.publicPort, proto.label)
                    publicRes += PublicResource(
                        niceId = rawNiceId(s, m, proto),
                        serviceKey = s.key,
                        mode = if (tcp) ResourceMode.TCP else ResourceMode.UDP,
                        fullDomain = null,
                        proxyPort = m.publicPort,
                        ssl = false,
                        target = Target(s.targetHost, m.port, null),
                        healthcheck = s.healthcheck?.takeIf { tcp },
                        paused = s.paused,
                        proxyProtocol = s.proxyProtocol && tcp,
                    )
                }
            }
        }

        // Stable internal proxy ports for passthrough TCP resources (never published).
        val ports = allocatePorts(passthroughKeys, config.pangolin.passthroughPortBase, raw.map { it.port }.toSet())
        val withPorts =
            publicRes.map { r -> if (r.niceId in ports) r.copy(proxyPort = ports.getValue(r.niceId)) else r }
                .toMutableList()

        // Health resource: lets Bunny's port-80 monitor observe node -> tunnel -> origin.
        val healthTarget = fd ?: dedicated.firstOrNull { it.isHttpLike() }
        var healthNice: String? = null
        var healthPath = "/"
        if (healthTarget != null) {
            healthNice = perNode(config, HEALTH, node.id)
            healthPath = (healthTarget.healthcheck as? HealthCheck.Http)?.path ?: "/"
            withPorts += PublicResource(
                niceId = healthNice, serviceKey = healthTarget.key, mode = ResourceMode.HTTP,
                fullDomain = "homegap-health-${node.id}.${config.domain}", proxyPort = null, ssl = false,
                target = Target(
                    healthTarget.targetHost,
                    healthTarget.port,
                    if (healthTarget.protocol == Protocol.HTTPS) "https" else "http"
                ),
                hostHeader = healthTarget.hostname,
                tlsServerName = healthTarget.hostname.takeIf { healthTarget.protocol == Protocol.HTTPS },
            )
        }

        val privateRes = mine.filter { it.access == Access.PRIVATE }.map { privateResource(it, site) } +
                mine.filter { it.access == Access.PUBLIC && it.privateHost != null }.flatMap { privatePath(it, site) }

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
            statusRedirect = if (spAuto != null && spNode != null) spAuto to spNode else null,
            clientHost = config.clientHost,
            // The node ends TLS for the shared names in each mode, so they always get the page.
            maintenanceHosts = (maintenance + listOfNotNull(stHosts.firstOrNull(), spAuto)).distinct().sorted(),
        )
    }

    private fun httpResource(nice: String, s: ServiceSpec, fullDomain: String, method: String) = PublicResource(
        niceId = nice, serviceKey = s.key, mode = ResourceMode.HTTP, fullDomain = fullDomain, proxyPort = null,
        ssl = true, target = Target(s.targetHost, s.port, method), auth = s.auth, roles = s.roles, pinEnv = s.pinEnv,
        healthcheck = s.healthcheck, paused = s.paused,
    )

    /**
     * A public service's private path (`homegap.private_network`): Pangolin clients resolve its
     * names to private resources that go to the container's address on that network, so the
     * service sees their traffic arrive from it rather than as public traffic. A wildcard front
     * resource per name in [ServiceSpec.privateHosts]. (Pangolin only takes a wildcard alias
     * when the destination is an IP address, so it is the address, not a name.)
     */
    private fun privatePath(s: ServiceSpec, site: String): List<PrivateResource> {
        val ports =
            (listOf(s.port) + if (s.protocol == Protocol.HTTPS && s.port != 80) listOf(80) else emptyList()).sorted()
                .joinToString(",")
        return s.privateHosts.map { alias ->
            PrivateResource(
                // "--" never occurs in a service key, so these never collide with a service's own.
                niceId = PRIVATE_PATH_PREFIX + slug(alias),
                serviceKey = s.key,
                name = "$alias (private)",
                siteNiceId = site,
                destination = s.privateHost!!,
                alias = alias,
                tcpPorts = ports,
                udpPorts = "",
                roles = s.roles,
                paused = s.paused,
            )
        }
    }

    private fun privateResource(s: ServiceSpec, site: String): PrivateResource {
        // A private resource reaches the container's own ports: a public:port mapping has no part here.
        fun on(p: Protocol) = s.ports.filter { p in it.protocols }.map { it.port }.distinct().sorted().joinToString(",")
        val tcp = if (s.protocol.isRaw) on(Protocol.TCP) else s.port.toString()
        val udp = if (s.protocol.isRaw) on(Protocol.UDP) else ""
        return PrivateResource(
            niceId = NICE_PREFIX + s.key,
            serviceKey = s.key,
            name = s.hostname,
            siteNiceId = site,
            destination = s.targetHost,
            alias = s.hostname,
            tcpPorts = tcp,
            udpPorts = udp,
            roles = s.roles,
            paused = s.paused,
        )
    }

    private fun planDns(
        config: AppConfig,
        nodes: List<NodeConfig>,
        services: List<ServiceSpec>,
        frontDoor: ServiceSpec?
    ): List<DesiredDnsRecord> {
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
                    out += DesiredDnsRecord(
                        rel(fqdn),
                        fqdn,
                        "A",
                        ip,
                        n.id,
                        geo,
                        loc?.latitude,
                        loc?.longitude,
                        n.latencyZone,
                        config.bunny.monitor,
                        config.bunny.ttl,
                        purpose
                    )
                }
                n.publicIpv6?.let { ip ->
                    out += DesiredDnsRecord(
                        rel(fqdn),
                        fqdn,
                        "AAAA",
                        ip,
                        n.id,
                        geo,
                        loc?.latitude,
                        loc?.longitude,
                        n.latencyZone,
                        config.bunny.monitor,
                        config.bunny.ttl,
                        purpose
                    )
                }
            }
        }

        if (config.acme.caa) {
            // Only this certificate authority, and only with the DNS challenge. A node has no DNS
            // key, so it cannot order a certificate for a name under the domain.
            val value = "${config.acme.caaIssuer}; validationmethods=dns-01"
            for (tag in listOf("issue", "issuewild")) {
                out += DesiredDnsRecord(rel(config.domain), config.domain, "CAA", value, CAA_OWNER, false, null, null, null, "none", CAA_TTL, "caa", tag)
            }
        }
        if (frontDoor != null) {
            val eligible = nodes.filter { frontDoor.servesNode(it.id) }
            geoSet(frontDoor.hostname, "root", eligible)
            if (frontDoor.wildcard) geoSet("*.${frontDoor.hostname}", "wildcard", eligible)
        }
        // One record set per hostname, however many services share it (raw ports on one name), on
        // every node that serves any of them. The front door's own name already has its set.
        services.filter { it != frontDoor && it.access == Access.PUBLIC && it.hostname != frontDoor?.hostname }
            .groupBy { it.hostname }
            .forEach { (host, group) ->
                geoSet(
                    host,
                    "service:${group.first().key}",
                    nodes.filter { n -> group.any { it.servesNode(n.id) } })
            }
        if (frontDoor != null && config.speedtest.enabled) {
            val eligible = nodes.filter { frontDoor.servesNode(it.id) }
            val st = config.speedtest
            // The auto name is covered by the wildcard set when there is one.
            if (!frontDoor.wildcard) geoSet(st.autoHost(config.domain), "speedtest", eligible)
            for (n in eligible) {
                val h = st.nodeHost(config.domain, n.id)
                n.ipv4()?.let {
                    out += DesiredDnsRecord(
                        rel(h),
                        h,
                        "A",
                        it,
                        n.id,
                        false,
                        null,
                        null,
                        null,
                        "none",
                        config.bunny.ttl,
                        "speedtest"
                    )
                }
                n.publicIpv6?.let {
                    out += DesiredDnsRecord(
                        rel(h),
                        h,
                        "AAAA",
                        it,
                        n.id,
                        false,
                        null,
                        null,
                        null,
                        "none",
                        config.bunny.ttl,
                        "speedtest"
                    )
                }
            }
        }
        if (frontDoor != null && config.statusPage.enabled) {
            val eligible = nodes.filter { frontDoor.servesNode(it.id) }
            val sp = config.statusPage
            // The shared name gets its own set even under a wildcard: every node answers it itself.
            geoSet(sp.autoHost(config.domain), "status", eligible)
            for (n in eligible) {
                val h = sp.nodeHost(config.domain, n.id)
                n.ipv4()?.let {
                    out += DesiredDnsRecord(
                        rel(h),
                        h,
                        "A",
                        it,
                        n.id,
                        false,
                        null,
                        null,
                        null,
                        "none",
                        config.bunny.ttl,
                        "status"
                    )
                }
                n.publicIpv6?.let {
                    out += DesiredDnsRecord(
                        rel(h),
                        h,
                        "AAAA",
                        it,
                        n.id,
                        false,
                        null,
                        null,
                        null,
                        "none",
                        config.bunny.ttl,
                        "status"
                    )
                }
            }
        }
        // Every node serves the cluster's login pages and client API.
        config.clientHost?.let { geoSet(it, "client", nodes) }
        for (n in nodes) {
            val mgmt = n.managementHost(config.domain)
            n.ipv4()?.let {
                out += DesiredDnsRecord(
                    rel(mgmt),
                    mgmt,
                    "A",
                    it,
                    n.id,
                    false,
                    null,
                    null,
                    null,
                    "none",
                    config.bunny.ttl,
                    "management"
                )
            }
            n.publicIpv6?.let {
                out += DesiredDnsRecord(
                    rel(mgmt),
                    mgmt,
                    "AAAA",
                    it,
                    n.id,
                    false,
                    null,
                    null,
                    null,
                    "none",
                    config.bunny.ttl,
                    "management"
                )
            }
        }
        return out.sortedWith(compareBy({ it.name }, { it.type }, { it.nodeId }))
    }

    /** Deterministic, collision-free port assignment: hash into a window, probe forward. */
    /**
     * In a cluster every node serves one shared resource per passthrough service, so its internal
     * proxy port must be the same on every node: allocated over all nodes' keys and raw ports.
     */
    private fun sharePassthroughPorts(config: AppConfig, nodes: Map<String, NodeDesired>): Map<String, NodeDesired> {
        val keys = nodes.values.flatMap { n -> n.sniRoutes.map { it.resourceNiceId } }
        val avoid = nodes.values.flatMap { n -> n.rawPorts.map { it.port } }.toSet()
        val ports = allocatePorts(keys, config.pangolin.passthroughPortBase, avoid)
        return nodes.mapValues { (_, n) ->
            n.copy(publicResources = n.publicResources.map { r ->
                ports[r.niceId]?.let { r.copy(proxyPort = it) } ?: r
            })
        }
    }

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
