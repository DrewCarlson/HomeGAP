package homegap.model

import homegap.acme.CertNames
import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.discovery.Access
import homegap.discovery.AuthMode
import homegap.discovery.DockerContainerSummary
import homegap.discovery.HealthCheck
import homegap.discovery.Labels
import homegap.discovery.NetworkEndpoint
import homegap.discovery.PortMapping
import homegap.discovery.Protocol
import homegap.discovery.ServiceSpec
import homegap.discovery.TlsMode
import homegap.discovery.TraefikRouter
import homegap.discovery.aliasCovers
import homegap.discovery.parseContainerLabels
import homegap.discovery.routerHosts
import homegap.discovery.slug

/** All that the controller wants to exist. The planner computes it from the labels and the config only. */
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
    /** The SNI passthrough routes of the node's Traefik on :443 (file provider). */
    val sniRoutes: List<SniRoute>,
    /** The hostnames whose plain HTTP (:80) requests the node redirects to HTTPS. */
    val httpsRedirectHosts: List<String>,
    val wildcardRedirectDomain: String?,
    /** The raw TCP/UDP ports that the node must publish (Traefik entrypoints and firewall). */
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
)

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
    /** `host` (a service by alias) or `exit-node` (all traffic, which leaves for the internet at the site). */
    val mode: String = "host",
)

data class SniRoute(
    val name: String,
    /** The niceId of the Pangolin TCP resource whose service carries the stream. */
    val resourceNiceId: String,
    val exactHosts: List<String>,
    /** If not null, the route also matches each name one label under this domain (`*.domain`). */
    val wildcardOf: String?,
    val excludeHosts: List<String>,
    val priority: Int,
)

data class DesiredDnsRecord(
    /** The record name relative to the zone ("" = apex, "*" = wildcard). */
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
    /** The comment of the Bunny record. It marks the record as one that HomeGAP manages. */
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

    private const val FRONTDOOR_SNI_PRIORITY = 100
    private const val SERVICE_SNI_PRIORITY = 200

    /** The number of internal proxy ports from `pangolin.passthrough_port_base` up. */
    private const val PORT_WINDOW = 5000
    private const val FNV_OFFSET_BASIS = 2166136261u
    private const val FNV_PRIME = 16777619u

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
        val resolver = SpecResolver(config, traefikRouters)
        val errors = resolver.errors
        val parsed = mutableListOf<ServiceSpec>()

        for (container in containers.sortedBy { it.name }) {
            val result = parseContainerLabels(container.id, container.name, container.labels, config.domain)
            result.errors.forEach { errors += LabelIssue(container.name, it) }
            result.warnings.forEach { resolver.warnings += LabelIssue(container.name, it) }
            for (spec in result.specs) {
                val resolved = resolver.resolve(spec, container)
                parsed += resolved.copy(paused = resolved.paused || resolved.key in pausedServices)
            }
        }

        val claimed = claim(parsed, errors, config.reservedHosts(), config.cluster?.mesh?.port)
        val frontDoor = claimed.firstOrNull {
            it.hostname == config.domain && it.access == Access.PUBLIC && !it.protocol.isRaw
        }
        val services = refuseCoveredPassthrough(claimed, frontDoor, errors)
        val activeNodes = config.nodes.filter { it.id !in excludedNodes }

        val planned = config.nodes.associate { node -> node.id to planNode(config, node, services, frontDoor) }
        return DesiredState(
            services = services,
            labelErrors = errors,
            labelWarnings = resolver.warnings,
            frontDoor = frontDoor,
            nodes = if (config.cluster != null) sharePassthroughPorts(config, planned) else planned,
            dns = planDns(config, activeNodes, services, frontDoor),
        )
    }

    // ------------------------------------------------------------------ services

    /** The start of each issue of a named service. Empty for the top-level service of a container. */
    private val ServiceSpec.issuePrefix: String get() = name?.let { "services.$it: " }.orEmpty()

    private val ServiceSpec.scheme: String get() = if (protocol == Protocol.HTTPS) "https" else "http"

    /** Collects the errors and warnings of one service. Each message starts with [prefix]. */
    private class ServiceIssues(
        private val resolver: SpecResolver,
        private val container: String,
        private val prefix: String,
    ) {
        fun error(message: String) {
            resolver.errors += LabelIssue(container, prefix + message)
        }

        fun warning(message: String) {
            resolver.warnings += LabelIssue(container, prefix + message)
        }

        /** An error that does not start with [prefix]. */
        fun containerError(message: String) {
            resolver.errors += LabelIssue(container, message)
        }
    }

    /**
     * Completes each spec with what its labels alone do not give: the names from the routers of a
     * Traefik, and the addresses of the container on its Docker networks. Collects the issues of
     * the plan.
     */
    private class SpecResolver(
        private val config: AppConfig,
        private val traefikRouters: Map<String, List<TraefikRouter>>,
    ) {
        val errors = mutableListOf<LabelIssue>()
        val warnings = mutableListOf<LabelIssue>()
        private val nodeIds = config.nodes.map { it.id }.toSet()

        /** The names that a Pangolin client must reach directly, not through the tunnel. */
        private val clientHosts =
            listOfNotNull(config.clientHost) + config.nodes.map { it.managementHost(config.domain) }

        fun resolve(spec: ServiceSpec, container: DockerContainerSummary): ServiceSpec {
            val issues = ServiceIssues(this, container.name, spec.issuePrefix)
            val withRouterHosts = withRouterHosts(spec, issues)
            val resolved = withPrivateAddress(withRouterHosts, container, issues)
            val unknown = resolved.regions?.filter { it !in nodeIds }.orEmpty()
            if (unknown.isNotEmpty()) {
                issues.warning("regions reference unknown node(s): ${unknown.joinToString()}")
            }
            return resolved
        }

        /** Adds the names of the routers of this Traefik that use the middleware of [spec]. Its API gives them. */
        private fun withRouterHosts(spec: ServiceSpec, issues: ServiceIssues): ServiceSpec {
            val middleware = spec.privateMiddleware ?: return spec
            val routers = traefikRouters[spec.traefikApi]
            if (routers == null) {
                issues.error(
                    "${Labels.PRIVATE_MIDDLEWARE}: the Traefik API at ${spec.traefikApi} has not answered; " +
                        "set ${Labels.TRAEFIK_API} if it is elsewhere"
                )
                return spec
            }
            val (hosts, unreadable) = routerHosts(routers, middleware)
            val (inside, outside) = hosts.partition { it == spec.hostname || it.endsWith(".${spec.hostname}") }
            outside.forEach {
                issues.warning("$it uses $middleware but is outside ${spec.hostname}: not given a private path")
            }
            unreadable.forEach {
                issues.warning(
                    "router $it uses $middleware but its names can't be read from its rule " +
                        "(HostRegexp or no Host); list them in ${Labels.PRIVATE_HOSTS}"
                )
            }
            return spec.copy(privateHosts = (spec.privateHosts + inside).distinct())
        }

        /** Gives [spec] the address of the container on its private network (`homegap.private_network`). */
        private fun withPrivateAddress(
            spec: ServiceSpec,
            container: DockerContainerSummary,
            issues: ServiceIssues,
        ): ServiceSpec {
            val network = spec.privateNetwork ?: return spec
            // Compose prefixes a project's networks with the project name.
            val (onPrivate, others) = container.networkSettings.networks.entries
                .filter { it.value.ipAddress.isNotEmpty() }
                .partition { (name, _) -> name == network || name.endsWith("_$network") }
            val privateAddress = onPrivate.singleOrNull()?.value?.ipAddress
            if (privateAddress == null) {
                // Public traffic is unaffected: only the private path is left out.
                issues.error("${Labels.PRIVATE_NETWORK}: the container has no address on a network named '$network'")
                return spec.copy(privateNetwork = null, privateHosts = emptyList())
            }
            val targetHost = when {
                declaresHost(spec, container) -> spec.targetHost
                else -> publicTargetHost(spec, network, others, issues)
            }
            return spec.copy(
                targetHost = targetHost,
                privateHost = privateAddress,
                privateHosts = allowedPrivateHosts(spec, issues),
            )
        }

        /** True if a label gives the host of [spec]: `homegap.host`, or the `host` of its named service. */
        private fun declaresHost(spec: ServiceSpec, container: DockerContainerSummary): Boolean {
            val ownHost = spec.name?.let { Labels.SERVICES_PREFIX + it + "." + Labels.HOST.removePrefix(Labels.PREFIX) }
            return Labels.HOST in container.labels || (ownHost != null && ownHost in container.labels)
        }

        /**
         * Public traffic must not reach the service over the private network. By name, Docker can
         * answer with that address (the tunnels container is on both networks), and the service
         * would see public traffic come from the private range. So public traffic goes to the
         * address on the other network of the container, unless `homegap.host` names one.
         */
        private fun publicTargetHost(
            spec: ServiceSpec,
            network: String,
            others: List<Map.Entry<String, NetworkEndpoint>>,
            issues: ServiceIssues,
        ): String {
            when (others.size) {
                1 -> return others.single().value.ipAddress
                0 -> issues.error(
                    "${Labels.PRIVATE_NETWORK}: the container is only on '$network'; " +
                        "public traffic needs another network shared with the tunnels"
                )

                else -> issues.containerError(
                    "${Labels.PRIVATE_NETWORK}: the container is on several other networks " +
                        "(${others.joinToString { it.key }}); set ${Labels.HOST} to its address on the one " +
                        "shared with the tunnels, or public traffic may arrive over '$network'"
                )
            }
            return spec.targetHost
        }

        /**
         * A client resolves each name that an alias covers into the tunnel, its own Pangolin address
         * too. Then it loses the cluster on its next reconnect. So an alias cannot cover such a name.
         */
        private fun allowedPrivateHosts(spec: ServiceSpec, issues: ServiceIssues): List<String> {
            val (clash, allowed) = spec.privateHosts.partition { alias -> clientHosts.any { aliasCovers(alias, it) } }
            clash.forEach { alias ->
                val covered = clientHosts.first { aliasCovers(alias, it) }
                issues.error(
                    "${Labels.PRIVATE_HOSTS}: '$alias' covers $covered, " +
                        "which Pangolin clients must reach directly; name the services instead"
                )
            }
            return allowed
        }
    }

    /**
     * Returns the services that keep their claims. The services are in the order of the container
     * names. The first one wins, and each other one gets an error. The rules:
     * - One http/https service for each hostname.
     * - Raw services can share a hostname, with each other and with one http/https service.
     * - One service for each key.
     * - On the nodes that two public services share, one service for each public port and protocol.
     */
    private fun claim(
        parsed: List<ServiceSpec>,
        errors: MutableList<LabelIssue>,
        reserved: Set<String>,
        meshPort: Int?,
    ): List<ServiceSpec> {
        val kept = mutableListOf<ServiceSpec>()
        for (service in parsed) {
            val refusal = refusal(service, kept, reserved, meshPort)
            if (refusal == null) {
                kept += service
            } else {
                errors += LabelIssue(service.containerName, service.issuePrefix + refusal)
            }
        }
        return kept
    }

    /** Returns why [service] cannot keep its claims beside the services in [kept], or null if it can. */
    private fun refusal(service: ServiceSpec, kept: List<ServiceSpec>, reserved: Set<String>, meshPort: Int?): String? {
        val hostname = service.hostname
        if (hostname.lowercase() in reserved) {
            return "hostname $hostname is reserved for HomeGAP"
        }
        val httpOwner = kept.firstOrNull { it.hostname == hostname && !it.protocol.isRaw }
        if (!service.protocol.isRaw && httpOwner != null) {
            return "hostname $hostname is already claimed by ${httpOwner.label}"
        }
        val keyOwner = kept.firstOrNull { it.key == service.key }
        if (keyOwner != null) {
            val remedy = "give this service a name under ${Labels.SERVICES_PREFIX}"
            return "hostname $hostname is already claimed by ${keyOwner.label}; $remedy"
        }
        if (service.access != Access.PUBLIC) {
            return null
        }
        val ports = service.publicPorts()
        if (ports.any { it.port == meshPort && it.protocol == "udp" }) {
            return "public port $meshPort/udp is the port of the cluster mesh"
        }
        val (owner, port) = kept
            .filter { other -> other.access == Access.PUBLIC && overlaps(other.regions, service.regions) }
            .firstNotNullOfOrNull { other -> other.publicPorts().firstOrNull { it in ports }?.let { other to it } }
            ?: return null
        return "public port ${port.port}/${port.protocol} is already used by ${owner.label}"
    }

    /**
     * A front door in `terminate` mode with a wildcard puts the wildcard certificate on the nodes.
     * That certificate covers each name one label under the domain, so TLS passthrough for such a
     * name protects nothing. HomeGAP refuses the service and does not publish it.
     */
    private fun refuseCoveredPassthrough(
        services: List<ServiceSpec>,
        frontDoor: ServiceSpec?,
        errors: MutableList<LabelIssue>,
    ): List<ServiceSpec> {
        if (frontDoor == null || !frontDoor.wildcard || frontDoor.tls != TlsMode.TERMINATE) {
            return services
        }
        val (covered, kept) = services.partition { isCoveredPassthrough(it, frontDoor) }
        for (service in covered) {
            val refusal = "${Labels.TLS}=passthrough is refused for ${service.hostname}: "
            val reason = "the front door ends TLS for *.${frontDoor.hostname} on the nodes, " +
                "so the nodes hold a certificate for this name. "
            val remedy = "Set ${Labels.TLS}=terminate here, or passthrough on the front door"
            errors += LabelIssue(service.containerName, service.issuePrefix + refusal + reason + remedy)
        }
        return kept
    }

    private fun isCoveredPassthrough(service: ServiceSpec, frontDoor: ServiceSpec): Boolean =
        service != frontDoor &&
            service.access == Access.PUBLIC &&
            service.isHttpLike() &&
            service.tls == TlsMode.PASSTHROUGH &&
            CertNames.covers(frontDoor.hostname, service.hostname)

    private fun overlaps(regions: List<String>?, otherRegions: List<String>?) =
        regions == null || otherRegions == null || regions.any { it in otherRegions }

    /** The public ports a raw service takes on its nodes. */
    private fun ServiceSpec.publicPorts(): Set<RawPort> =
        ports.flatMap { mapping -> mapping.protocols.map { RawPort(mapping.publicPort, it.label) } }.toSet()

    /**
     * The Pangolin resource of a raw service for one port and protocol. A top-level service with a
     * single port keeps the id from before port lists (`hg-<host>-tcp`), so an upgrade creates no
     * resource again. Each other resource adds the public port.
     */
    fun rawNiceId(service: ServiceSpec, mapping: PortMapping, protocol: Protocol): String {
        val singlePort = service.name == null && service.ports.size == 1 && mapping.protocols.size == 1
        return when {
            singlePort -> "$NICE_PREFIX${service.key}-${protocol.label}"
            else -> "$NICE_PREFIX${service.key}-${mapping.publicPort}-${protocol.label}"
        }
    }

    // ------------------------------------------------------------------ nodes

    private fun planNode(
        config: AppConfig,
        node: NodeConfig,
        services: List<ServiceSpec>,
        frontDoor: ServiceSpec?,
    ): NodeDesired {
        val mine = services.filter { it.servesNode(node.id) }
        val nodeFrontDoor = frontDoor?.takeIf { it.servesNode(node.id) }
        val dedicated = mine.filter { it.access == Access.PUBLIC && it != frontDoor }

        val plan = NodePlan(config, node, PageHosts(config, node, served = nodeFrontDoor != null))
        if (nodeFrontDoor != null) {
            plan.addFrontDoor(nodeFrontDoor, dedicated)
        }
        dedicated.forEach { plan.addDedicated(it) }

        val site = config.siteNiceId(node.id)
        val privateServices = mine.filter { it.access == Access.PRIVATE }.map { privateResource(it, site) }
        val privatePaths = mine.filter { it.access == Access.PUBLIC && it.privateHost != null }
            .flatMap { privatePath(it, site) }
        return plan.build(
            // The health resource lets Bunny's port-80 monitor observe node -> tunnel -> origin.
            healthTarget = nodeFrontDoor ?: dedicated.firstOrNull { it.isHttpLike() },
            privateResources = privateServices + privatePaths,
        )
    }

    /**
     * The speed test and status page names of one node. A node serves them only when it serves the
     * front door ([served]). Each name is null if the node does not serve it.
     */
    private class PageHosts(config: AppConfig, node: NodeConfig, served: Boolean) {
        private val speedtest = config.speedtest.takeIf { it.enabled && served }
        private val statusPage = config.statusPage.takeIf { it.enabled && served }

        val speedtestShared: String? = speedtest?.autoHost(config.domain)
        val speedtestNode: String? = speedtest?.nodeHost(config.domain, node.id)
        val statusShared: String? = statusPage?.autoHost(config.domain)
        val statusNode: String? = statusPage?.nodeHost(config.domain, node.id)

        /** The node answers the shared names itself, with a redirect to its own name. */
        val shared: List<String> = listOfNotNull(speedtestShared, statusShared)

        /** The names of the node go through the front door to the homelab, so the page knows the node. */
        val perNode: List<String> = listOfNotNull(speedtestNode, statusNode)
    }

    /** Collects what one node serves, service by service. [build] gives the result. */
    private class NodePlan(
        private val config: AppConfig,
        private val node: NodeConfig,
        private val pages: PageHosts,
    ) {
        private val managementHost = node.managementHost(config.domain)
        private val publicResources = mutableListOf<PublicResource>()
        private val sniRoutes = mutableListOf<SniRoute>()
        private val redirectHosts = mutableListOf<String>()
        private val rawPorts = mutableSetOf<RawPort>()
        private val passthroughKeys = mutableListOf<String>()
        private val maintenanceHosts = mutableListOf<String>()
        private var wildcardRedirect: String? = null

        fun addFrontDoor(frontDoor: ServiceSpec, dedicated: List<ServiceSpec>) {
            if (frontDoor.tls == TlsMode.PASSTHROUGH && frontDoor.protocol == Protocol.HTTPS) {
                val terminated = dedicated.filter { it.isHttpLike() && it.tls == TlsMode.TERMINATE }.map { it.hostname }
                addPassthroughFrontDoor(frontDoor, terminated)
            } else {
                addTerminatedFrontDoor(frontDoor)
            }
        }

        /** [terminatedHosts] are the names of the dedicated services for which the node ends TLS. */
        private fun addPassthroughFrontDoor(frontDoor: ServiceSpec, terminatedHosts: List<String>) {
            val nodeHosts = listOfNotNull(managementHost, config.clientHost) + pages.shared
            passthroughKeys += FRONTDOOR_TCP
            publicResources += passthroughResource(FRONTDOOR_TCP, frontDoor)
            sniRoutes += SniRoute(
                name = "homegap-frontdoor",
                resourceNiceId = FRONTDOOR_TCP,
                exactHosts = listOf(frontDoor.hostname) + if (frontDoor.wildcard) emptyList() else pages.perNode,
                wildcardOf = if (frontDoor.wildcard) frontDoor.hostname else null,
                excludeHosts = (nodeHosts + terminatedHosts).distinct().sorted(),
                priority = FRONTDOOR_SNI_PRIORITY,
            )
            redirectHosts += frontDoor.hostname
            if (frontDoor.wildcard) {
                wildcardRedirect = frontDoor.hostname
            }
        }

        private fun addTerminatedFrontDoor(frontDoor: ServiceSpec) {
            val hostname = frontDoor.hostname
            if (frontDoor.maintenancePage) {
                maintenanceHosts += hostname
                maintenanceHosts += if (frontDoor.wildcard) listOf("*.$hostname") else pages.perNode
            }
            publicResources += httpResource(NICE_PREFIX + frontDoor.key, frontDoor, hostname)
            if (frontDoor.wildcard) {
                publicResources += httpResource(NICE_PREFIX + "wildcard-" + frontDoor.key, frontDoor, "*.$hostname")
            }
            if (pages.speedtestNode != null) {
                publicResources += openResource(perNode(config, SPEEDTEST, node.id), frontDoor, pages.speedtestNode)
            }
            if (pages.statusNode != null) {
                publicResources += openResource(perNode(config, STATUS, node.id), frontDoor, pages.statusNode)
            }
        }

        fun addDedicated(service: ServiceSpec) {
            when (service.protocol) {
                Protocol.HTTP, Protocol.HTTPS -> when (service.tls) {
                    TlsMode.PASSTHROUGH -> addPassthrough(service)
                    TlsMode.TERMINATE -> addTerminated(service)
                }

                Protocol.TCP, Protocol.UDP, Protocol.TCP_UDP -> addRawPorts(service)
            }
        }

        private fun addPassthrough(service: ServiceSpec) {
            val niceId = NICE_PREFIX + service.key
            passthroughKeys += niceId
            publicResources += passthroughResource(niceId, service)
            sniRoutes += SniRoute(
                name = "homegap-${service.key}",
                resourceNiceId = niceId,
                exactHosts = listOf(service.hostname),
                wildcardOf = null,
                excludeHosts = emptyList(),
                priority = SERVICE_SNI_PRIORITY,
            )
            redirectHosts += service.hostname
        }

        private fun addTerminated(service: ServiceSpec) {
            if (service.maintenancePage) {
                maintenanceHosts += service.hostname
            }
            publicResources += httpResource(NICE_PREFIX + service.key, service, service.hostname)
        }

        private fun addRawPorts(service: ServiceSpec) {
            for (mapping in service.ports) {
                for (protocol in mapping.protocols.sortedBy { it.ordinal }) {
                    val tcp = protocol == Protocol.TCP
                    rawPorts += RawPort(mapping.publicPort, protocol.label)
                    publicResources += PublicResource(
                        niceId = rawNiceId(service, mapping, protocol),
                        serviceKey = service.key,
                        mode = if (tcp) ResourceMode.TCP else ResourceMode.UDP,
                        fullDomain = null,
                        proxyPort = mapping.publicPort,
                        ssl = false,
                        target = Target(service.targetHost, mapping.port, method = null),
                        healthcheck = service.healthcheck?.takeIf { tcp },
                        paused = service.paused,
                        proxyProtocol = service.proxyProtocol && tcp,
                    )
                }
            }
        }

        /** [healthTarget] is the service that the health resource of the node goes to, or null for none. */
        fun build(healthTarget: ServiceSpec?, privateResources: List<PrivateResource>): NodeDesired {
            // Stable internal proxy ports for passthrough TCP resources (never published).
            val ports = allocatePorts(
                keys = passthroughKeys,
                base = config.pangolin.passthroughPortBase,
                avoid = rawPorts.map { it.port }.toSet(),
            )
            val withPorts = publicResources.map { resource ->
                ports[resource.niceId]?.let { resource.copy(proxyPort = it) } ?: resource
            }
            val health = healthTarget?.let { healthResource(it) }
            return NodeDesired(
                node = node,
                managementHost = managementHost,
                publicResources = (withPorts + listOfNotNull(health)).sortedBy { it.niceId },
                privateResources = privateResources.sortedBy { it.niceId },
                sniRoutes = sniRoutes.sortedBy { it.name },
                httpsRedirectHosts = redirectHosts.distinct().sorted(),
                wildcardRedirectDomain = wildcardRedirect,
                rawPorts = rawPorts.sorted(),
                healthResourceNiceId = health?.niceId,
                healthPath = (healthTarget?.healthcheck as? HealthCheck.Http)?.path ?: "/",
                speedtestRedirect = redirect(pages.speedtestShared, pages.speedtestNode),
                statusRedirect = redirect(pages.statusShared, pages.statusNode),
                clientHost = config.clientHost,
                // The node ends TLS for the shared names in each mode, so they always get the page.
                maintenanceHosts = (maintenanceHosts + pages.shared).distinct().sorted(),
            )
        }

        private fun redirect(shared: String?, ofNode: String?): Pair<String, String>? =
            if (shared != null && ofNode != null) shared to ofNode else null

        private fun healthResource(target: ServiceSpec) = PublicResource(
            niceId = perNode(config, HEALTH, node.id),
            serviceKey = target.key,
            mode = ResourceMode.HTTP,
            fullDomain = "homegap-health-${node.id}.${config.domain}",
            proxyPort = null,
            ssl = false,
            target = Target(target.targetHost, target.port, target.scheme),
            hostHeader = target.hostname,
            tlsServerName = target.hostname.takeIf { target.protocol == Protocol.HTTPS },
        )
    }

    private fun httpResource(niceId: String, service: ServiceSpec, fullDomain: String) = PublicResource(
        niceId = niceId,
        serviceKey = service.key,
        mode = ResourceMode.HTTP,
        fullDomain = fullDomain,
        proxyPort = null,
        ssl = true,
        target = Target(service.targetHost, service.port, service.scheme),
        auth = service.auth,
        roles = service.roles,
        pinEnv = service.pinEnv,
        healthcheck = service.healthcheck,
        paused = service.paused,
    )

    /** An HTTP resource of [service] that has no authentication: the speed test and the status page. */
    private fun openResource(niceId: String, service: ServiceSpec, fullDomain: String) =
        httpResource(niceId, service, fullDomain).copy(auth = AuthMode.NONE, roles = emptyList(), pinEnv = null)

    /** The TCP resource that carries the TLS stream of [service] to the homelab. [NodePlan.build] sets its port. */
    private fun passthroughResource(niceId: String, service: ServiceSpec) = PublicResource(
        niceId = niceId,
        serviceKey = service.key,
        mode = ResourceMode.TCP,
        fullDomain = null,
        proxyPort = 0,
        ssl = false,
        target = Target(service.targetHost, service.port, method = null),
        healthcheck = service.healthcheck,
        paused = service.paused,
        proxyProtocol = service.proxyProtocol,
    )

    /**
     * The private path of a public service (`homegap.private_network`). Pangolin clients resolve
     * its names to private resources that go to the address of the container on that network. So
     * the service sees their traffic arrive from that network and not as public traffic.
     *
     * Each name in [ServiceSpec.privateHosts] gets one resource. Pangolin takes a wildcard alias
     * only when the destination is an IP address, so the destination is the address, not a name.
     */
    private fun privatePath(service: ServiceSpec, site: String): List<PrivateResource> {
        val ports = buildList {
            add(service.port)
            if (service.protocol == Protocol.HTTPS && service.port != 80) {
                add(80)
            }
        }
        val tcpPorts = ports.sorted().joinToString(",")
        return service.privateHosts.map { alias ->
            PrivateResource(
                // "--" never occurs in a service key, so these never collide with a service's own.
                niceId = PRIVATE_PATH_PREFIX + slug(alias),
                serviceKey = service.key,
                name = "$alias (private)",
                siteNiceId = site,
                destination = service.privateHost!!,
                alias = alias,
                tcpPorts = tcpPorts,
                udpPorts = "",
                roles = service.roles,
                paused = service.paused,
            )
        }
    }

    private fun privateResource(service: ServiceSpec, site: String): PrivateResource {
        val raw = service.protocol.isRaw
        return PrivateResource(
            niceId = NICE_PREFIX + service.key,
            serviceKey = service.key,
            name = service.hostname,
            siteNiceId = site,
            destination = service.targetHost,
            alias = service.hostname,
            tcpPorts = if (raw) containerPorts(service, Protocol.TCP) else service.port.toString(),
            udpPorts = if (raw) containerPorts(service, Protocol.UDP) else "",
            roles = service.roles,
            paused = service.paused,
        )
    }

    /** A private resource reaches the ports of the container itself. A public:port mapping has no part here. */
    private fun containerPorts(service: ServiceSpec, protocol: Protocol): String =
        service.ports.asSequence()
            .filter { protocol in it.protocols }
            .map { it.port }
            .distinct()
            .sorted()
            .joinToString(",")

    // ------------------------------------------------------------------ DNS

    private fun planDns(
        config: AppConfig,
        nodes: List<NodeConfig>,
        services: List<ServiceSpec>,
        frontDoor: ServiceSpec?,
    ): List<DesiredDnsRecord> {
        val domain = config.domain
        val plan = DnsPlan(config)
        val frontDoorNodes = if (frontDoor == null) emptyList() else nodes.filter { frontDoor.servesNode(it.id) }

        if (config.acme.caa) {
            plan.addCaaRecords()
        }
        if (frontDoor != null) {
            plan.addGeoSet(frontDoor.hostname, "root", frontDoorNodes)
            if (frontDoor.wildcard) {
                plan.addGeoSet("*.${frontDoor.hostname}", "wildcard", frontDoorNodes)
            }
        }
        // One record set per hostname, however many services share it (raw ports on one name), on
        // every node that serves any of them. The front door's own name already has its set.
        services.filter { it != frontDoor && it.access == Access.PUBLIC && it.hostname != frontDoor?.hostname }
            .groupBy { it.hostname }
            .forEach { (host, group) ->
                val eligible = nodes.filter { node -> group.any { it.servesNode(node.id) } }
                plan.addGeoSet(host, "service:${group.first().key}", eligible)
            }
        if (frontDoor != null && config.speedtest.enabled) {
            // The auto name is covered by the wildcard set when there is one.
            if (!frontDoor.wildcard) {
                plan.addGeoSet(config.speedtest.autoHost(domain), "speedtest", frontDoorNodes)
            }
            for (node in frontDoorNodes) {
                plan.addNodeRecords(config.speedtest.nodeHost(domain, node.id), "speedtest", node)
            }
        }
        if (frontDoor != null && config.statusPage.enabled) {
            // The shared name gets its own set even under a wildcard: every node answers it itself.
            plan.addGeoSet(config.statusPage.autoHost(domain), "status", frontDoorNodes)
            for (node in frontDoorNodes) {
                plan.addNodeRecords(config.statusPage.nodeHost(domain, node.id), "status", node)
            }
        }
        // Every node serves the cluster's login pages and client API.
        config.clientHost?.let { plan.addGeoSet(it, "client", nodes) }
        for (node in nodes) {
            plan.addNodeRecords(node.managementHost(domain), "management", node)
        }
        return plan.records.sortedWith(compareBy({ it.name }, { it.type }, { it.nodeId }))
    }

    /** Collects the DNS records of one plan. */
    private class DnsPlan(private val config: AppConfig) {
        val records = mutableListOf<DesiredDnsRecord>()
        private val geo = config.bunny.routing != "none"

        /**
         * Adds the record set of a name that each node in [nodes] serves. Bunny sends a viewer to
         * one member of the set and removes a member that is offline. A single record cannot fail
         * over.
         */
        fun addGeoSet(fqdn: String, purpose: String, nodes: List<NodeConfig>) {
            for (node in nodes) {
                val location = node.effectiveLocation()
                for ((type, address) in addressRecords(node)) {
                    records += DesiredDnsRecord(
                        name = relativeName(fqdn),
                        fqdn = fqdn,
                        type = type,
                        value = address,
                        nodeId = node.id,
                        geo = geo,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        latencyZone = node.latencyZone,
                        monitor = config.bunny.monitor,
                        ttl = config.bunny.ttl,
                        purpose = purpose,
                    )
                }
            }
        }

        /** Adds the records of a name that only [node] serves. They have no routing and no monitor. */
        fun addNodeRecords(fqdn: String, purpose: String, node: NodeConfig) {
            for ((type, address) in addressRecords(node)) {
                records += DesiredDnsRecord(
                    name = relativeName(fqdn),
                    fqdn = fqdn,
                    type = type,
                    value = address,
                    nodeId = node.id,
                    geo = false,
                    latitude = null,
                    longitude = null,
                    latencyZone = null,
                    monitor = "none",
                    ttl = config.bunny.ttl,
                    purpose = purpose,
                )
            }
        }

        /**
         * The CAA records let only this certificate authority issue, and only with the DNS
         * challenge. A node has no DNS key, so it cannot order a certificate for a name under the
         * domain.
         */
        fun addCaaRecords() {
            val value = "${config.acme.caaIssuer}; validationmethods=dns-01"
            for (tag in listOf("issue", "issuewild")) {
                records += DesiredDnsRecord(
                    name = relativeName(config.domain),
                    fqdn = config.domain,
                    type = "CAA",
                    value = value,
                    nodeId = CAA_OWNER,
                    geo = false,
                    latitude = null,
                    longitude = null,
                    latencyZone = null,
                    monitor = "none",
                    ttl = CAA_TTL,
                    purpose = "caa",
                    tag = tag,
                )
            }
        }

        /** The record type and the address of each public address of [node]: A, then AAAA. */
        private fun addressRecords(node: NodeConfig): List<Pair<String, String>> =
            listOfNotNull(node.ipv4()?.let { "A" to it }, node.publicIpv6?.let { "AAAA" to it })

        private fun relativeName(fqdn: String) = config.bunny.relativeName(fqdn)
    }

    // ------------------------------------------------------------------ passthrough ports

    /**
     * In a cluster every node serves one shared resource per passthrough service, so its internal
     * proxy port must be the same on every node: allocated over all nodes' keys and raw ports.
     */
    private fun sharePassthroughPorts(config: AppConfig, nodes: Map<String, NodeDesired>): Map<String, NodeDesired> {
        val keys = nodes.values.flatMap { node -> node.sniRoutes.map { it.resourceNiceId } }
        val avoid = nodes.values.flatMap { node -> node.rawPorts.map { it.port } }.toSet()
        val ports = allocatePorts(keys, config.pangolin.passthroughPortBase, avoid)
        return nodes.mapValues { (_, node) ->
            node.copy(publicResources = node.publicResources.map { resource ->
                ports[resource.niceId]?.let { resource.copy(proxyPort = it) } ?: resource
            })
        }
    }

    /**
     * Gives each key a port from [base] up. The same keys always get the same ports, and no two keys
     * get the same port: the hash of a key selects a port in a window, and the next free port after
     * it is the result.
     */
    fun allocatePorts(keys: List<String>, base: Int, avoid: Set<Int>): Map<String, Int> {
        val used = avoid.toMutableSet()
        val ports = mutableMapOf<String, Int>()
        for (key in keys.distinct().sorted()) {
            var port = base + (fnv1a(key) % PORT_WINDOW.toUInt()).toInt()
            while (port in used) {
                port = base + ((port - base + 1) % PORT_WINDOW)
            }
            used += port
            ports[key] = port
        }
        return ports
    }

    fun fnv1a(text: String): UInt {
        var hash = FNV_OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toUInt() and 0xffu)
            hash *= FNV_PRIME
        }
        return hash
    }
}

fun ServiceSpec.isHttpLike(): Boolean = protocol == Protocol.HTTP || protocol == Protocol.HTTPS
