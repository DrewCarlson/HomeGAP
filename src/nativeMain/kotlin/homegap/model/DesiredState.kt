package homegap.model

import homegap.provision.Mesh
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
    /** The front door of the domain of the platform. */
    val frontDoor: ServiceSpec?,
    val nodes: Map<String, NodeDesired>,
    val dns: List<DesiredDnsRecord>,
    /** The front door of each extra domain that has one. */
    val extraFrontDoors: List<ServiceSpec> = emptyList(),
) {
    /** Each front door: the one of the platform domain first. */
    val frontDoors: List<ServiceSpec> get() = listOfNotNull(frontDoor) + extraFrontDoors

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
    /**
     * The raw TCP and UDP resources that the node forwards in the kernel to the homelab over the
     * mesh. Pangolin and the Traefik of the node have no part in them.
     */
    val forwardedResources: List<PublicResource> = emptyList(),
    /**
     * The raw TCP resources with PROXY protocol. The agent of the node accepts each connection and
     * sends it to the homelab over the mesh, with the header first.
     */
    val proxiedResources: List<PublicResource> = emptyList(),
    /**
     * The HTTP resources: the names that the node ends TLS for, and the health check. The Traefik
     * of the node sends each to its [PublicResource.meshPort] on the mesh address of the homelab.
     */
    val httpResources: List<PublicResource> = emptyList(),
    /** The SNI passthrough routes of the node's Traefik on :443 (file provider). */
    val sniRoutes: List<SniRoute>,
    /** The hostnames whose plain HTTP (:80) requests the node redirects to HTTPS. */
    val httpsRedirectHosts: List<String>,
    /** The domains whose names one label below also get that redirect. */
    val wildcardRedirectDomains: List<String>,
    /** Each raw TCP/UDP port that the node serves (firewall). See [forwardedPorts] and [proxiedResources]. */
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
    /**
     * The mesh address of the homelab. The node sends the streams of its passthrough routes to a
     * port of this address. With null, the streams go through Pangolin.
     */
    val meshHome: String? = null,
    /** The exit of the node for the devices of the VPN, or null if the node is no exit. */
    val exit: Mesh.NodeExit? = null,
) {
    val id: String get() = node.id

    /** The port of [meshHome] for the stream of [route], or null if the stream goes through Pangolin. */
    fun meshTarget(route: SniRoute): MeshTarget? {
        val home = meshHome ?: return null
        val resource = publicResources.firstOrNull { it.niceId == route.resourceNiceId } ?: return null
        return resource.proxyPort?.let { MeshTarget("$home:$it", resource.proxyProtocol) }
    }

    /** Each resource that the node serves to the public, in each of the four lists. */
    val resources: List<PublicResource>
        get() = buildList {
            addAll(publicResources)
            addAll(httpResources)
            addAll(forwardedResources)
            addAll(proxiedResources)
        }

    /** The URL of [resource] (one of the [httpResources]) on the mesh, or null if it has no port there. */
    fun meshUrl(resource: PublicResource): String? {
        val home = meshHome ?: return null
        return resource.meshPort?.let { "${resource.target.method ?: "http"}://$home:$it" }
    }

    /** The raw ports that the node forwards in the kernel. */
    val forwardedPorts: List<RawPort>
        get() = forwardedResources.mapNotNull { resource ->
            resource.proxyPort?.let { RawPort(it, resource.mode.wire) }
        }
}

/** Where a node sends a stream over the mesh. With [proxyProtocol], it sends a PROXY protocol header first. */
data class MeshTarget(val address: String, val proxyProtocol: Boolean)

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
    /** HTTP only: the port of the mesh address of the homelab that goes to the [target]. */
    val meshPort: Int? = null,
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

    /** The source of an issue about the route of the dashboard, when there is no front door. */
    private const val DASHBOARD = "dashboard"

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
            val result = parseContainerLabels(container.id, container.name, container.labels, config.serviceDomains)
            result.errors.forEach { errors += LabelIssue(container.name, it) }
            result.warnings.forEach { resolver.warnings += LabelIssue(container.name, it) }
            for (spec in result.specs) {
                val resolved = resolver.resolve(spec, container)
                parsed += resolved.copy(paused = resolved.paused || resolved.key in pausedServices)
            }
        }

        val claims = Claims(config.reservedHosts(), config.mesh.port, config.nodes)
        val claimed = claim(parsed, errors, claims)
        val published = withPrivatePages(config, withPrivateDashboard(config, claimed, errors))
        val doors = frontDoors(config, published)
        val services = doors.all.fold(published) { kept, door -> refuseCoveredPassthrough(kept, door, errors) }
        val activeNodes = config.nodes.filter { it.id !in excludedNodes }

        val planned = config.nodes.associate { node -> node.id to planNode(config, node, services, doors) }
        return DesiredState(
            services = services,
            labelErrors = errors,
            labelWarnings = resolver.warnings,
            frontDoor = doors.primary,
            nodes = withMeshPorts(config, sharePassthroughPorts(config, planned)),
            dns = planDns(config, activeNodes, services, doors),
            extraFrontDoors = doors.extras,
        )
    }

    /**
     * The front doors of a plan. A front door is the public service at the root of a domain. It
     * takes each name of that domain that has no service of its own.
     */
    private class FrontDoors(val primary: ServiceSpec?, val extras: List<ServiceSpec>) {
        val all: List<ServiceSpec> = listOfNotNull(primary) + extras
        val hostnames: Set<String> = all.map { it.hostname }.toSet()
    }

    /**
     * A dashboard for Pangolin clients only uses the private path of the front door: its name is
     * one more private name of the front door. Without that path, the dashboard has no route.
     */
    private fun withPrivateDashboard(
        config: AppConfig,
        services: List<ServiceSpec>,
        errors: MutableList<LabelIssue>,
    ): List<ServiceSpec> {
        if (!config.dashboard.publishedPrivately) return services
        val host = config.dashboard.publishedHost(config.domain) ?: return services
        val frontDoor = frontDoors(config, services).primary
        if (frontDoor?.privateHost == null) {
            val reason = "dashboard.access is private, but the front door has no ${Labels.PRIVATE_NETWORK}"
            errors += LabelIssue(frontDoor?.containerName ?: DASHBOARD, "$reason: the dashboard has no route")
            return services
        }
        val withHost = frontDoor.copy(privateHosts = (frontDoor.privateHosts + host).distinct())
        return services.map { if (it == frontDoor) withHost else it }
    }

    /**
     * A Pangolin client gets the pages of HomeGAP from the homelab, not from a node: the shared
     * name of each page is one more private name of the front door. The request then arrives on
     * the private network, and the page knows that the visitor is a Pangolin client. The status
     * page always has this name. The speed test has it with `speedtest.vpn_access`. Without a
     * private path, such a client gets each page as each other visitor does.
     */
    private fun withPrivatePages(config: AppConfig, services: List<ServiceSpec>): List<ServiceSpec> {
        val hosts = listOfNotNull(
            config.statusPage.takeIf { it.enabled }?.autoHost(config.domain),
            config.speedtest.takeIf { it.enabled && it.vpnAccess }?.autoHost(config.domain),
        )
        if (hosts.isEmpty()) return services
        val frontDoor = frontDoors(config, services).primary?.takeIf { it.privateHost != null } ?: return services
        val withHosts = frontDoor.copy(privateHosts = (frontDoor.privateHosts + hosts).distinct())
        return services.map { if (it == frontDoor) withHosts else it }
    }

    private fun frontDoors(config: AppConfig, services: List<ServiceSpec>): FrontDoors {
        fun doorOf(domain: String): ServiceSpec? =
            services.firstOrNull { it.hostname == domain && it.access == Access.PUBLIC && !it.protocol.isRaw }
        return FrontDoors(
            primary = doorOf(config.domain),
            extras = config.extraDomains.mapNotNull { doorOf(it.domain) },
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
    /** What a service cannot claim: the hostnames of HomeGAP, the mesh port, and the SSH port of each node. */
    private class Claims(val hosts: Set<String>, val meshPort: Int?, val nodes: List<NodeConfig>) {
        /** The node that serves [service] and has one of its public TCP ports as its SSH port, or null. */
        fun sshClash(service: ServiceSpec): NodeConfig? {
            val tcp = service.publicPorts().filter { it.protocol == "tcp" }.map { it.port }.toSet()
            return nodes.firstOrNull { it.sshPort in tcp && service.servesNode(it.id) }
        }
    }

    private fun claim(parsed: List<ServiceSpec>, errors: MutableList<LabelIssue>, claims: Claims): List<ServiceSpec> {
        val kept = mutableListOf<ServiceSpec>()
        for (service in parsed) {
            val refusal = refusal(service, kept, claims)
            if (refusal == null) {
                kept += service
            } else {
                errors += LabelIssue(service.containerName, service.issuePrefix + refusal)
            }
        }
        return kept
    }

    /** Returns why [service] cannot keep its claims beside the services in [kept], or null if it can. */
    private fun refusal(service: ServiceSpec, kept: List<ServiceSpec>, claims: Claims): String? {
        val hostname = service.hostname
        if (hostname.lowercase() in claims.hosts) {
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
        if (ports.any { it.port == claims.meshPort && it.protocol == "udp" }) {
            return "public port ${claims.meshPort}/udp is the port of the cluster mesh"
        }
        claims.sshClash(service)?.let { node ->
            val remedy = "move the SSH server with nodes[].ssh_port, or publish the service on a different port"
            return "public port ${node.sshPort}/tcp is the SSH port of node ${node.id}; $remedy"
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
        frontDoor: ServiceSpec,
        errors: MutableList<LabelIssue>,
    ): List<ServiceSpec> {
        if (!frontDoor.wildcard || frontDoor.tls != TlsMode.TERMINATE) {
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
        doors: FrontDoors,
    ): NodeDesired {
        val mine = services.filter { it.servesNode(node.id) }
        val nodeFrontDoor = doors.primary?.takeIf { it.servesNode(node.id) }
        val nodeExtraDoors = doors.extras.filter { it.servesNode(node.id) }
        val dedicated = mine.filter { it.access == Access.PUBLIC && it !in doors.all }

        val plan = NodePlan(config, node, PageHosts(config, node, served = nodeFrontDoor != null))
        if (nodeFrontDoor != null) {
            plan.addFrontDoor(nodeFrontDoor, dedicated)
        }
        nodeExtraDoors.forEach { plan.addExtraFrontDoor(it, dedicated) }
        dedicated.forEach { plan.addDedicated(it) }

        val site = config.siteNiceId(node.id)
        val privateServices = mine.filter { it.access == Access.PRIVATE }.map { privateResource(it, site) }
        val privatePaths = mine.filter { it.access == Access.PUBLIC && it.privateHost != null }
            .flatMap { privatePath(it, site) }
        return plan.build(
            // The health resource lets Bunny's port-80 monitor observe node -> tunnel -> origin.
            healthTarget = nodeFrontDoor ?: (nodeExtraDoors + dedicated).firstOrNull { it.isHttpLike() },
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
        private val forwardedResources = mutableListOf<PublicResource>()
        private val proxiedResources = mutableListOf<PublicResource>()
        private val sniRoutes = mutableListOf<SniRoute>()
        private val redirectHosts = mutableListOf<String>()
        private val rawPorts = mutableSetOf<RawPort>()
        private val passthroughKeys = mutableListOf<String>()
        private val maintenanceHosts = mutableListOf<String>()
        private val wildcardRedirects = mutableListOf<String>()

        /**
         * The passthrough route of a front door. [pageHosts] are the page names of the node that
         * the front door carries, and [excludeHosts] are the names that the node answers itself.
         */
        private class DoorRoute(
            val niceId: String,
            val name: String,
            val pageHosts: List<String>,
            val excludeHosts: List<String>,
        )

        /** The front door of the platform domain. It also carries the pages of the node. */
        fun addFrontDoor(frontDoor: ServiceSpec, dedicated: List<ServiceSpec>) {
            if (frontDoor.isPassthroughDoor()) {
                val nodeHosts = listOfNotNull(managementHost, config.clientHost) + pages.shared
                val route = DoorRoute(
                    niceId = FRONTDOOR_TCP,
                    name = "homegap-frontdoor",
                    pageHosts = pages.perNode,
                    excludeHosts = nodeHosts + terminatedHosts(dedicated),
                )
                addPassthroughFrontDoor(frontDoor, route)
            } else {
                addTerminatedFrontDoor(frontDoor, pageHosts = pages.perNode)
                addPageResources(frontDoor)
            }
        }

        /** The front door of an extra domain. It carries only the names of its own domain. */
        fun addExtraFrontDoor(frontDoor: ServiceSpec, dedicated: List<ServiceSpec>) {
            if (frontDoor.isPassthroughDoor()) {
                val route = DoorRoute(
                    niceId = "$FRONTDOOR_TCP-${frontDoor.key}",
                    name = "homegap-frontdoor-${frontDoor.key}",
                    pageHosts = emptyList(),
                    excludeHosts = terminatedHosts(dedicated).filter { it.endsWith(".${frontDoor.hostname}") },
                )
                addPassthroughFrontDoor(frontDoor, route)
            } else {
                addTerminatedFrontDoor(frontDoor, pageHosts = emptyList())
            }
        }

        private fun ServiceSpec.isPassthroughDoor(): Boolean =
            tls == TlsMode.PASSTHROUGH && protocol == Protocol.HTTPS

        /** The names of the dedicated services for which the node ends TLS. */
        private fun terminatedHosts(dedicated: List<ServiceSpec>): List<String> =
            dedicated.filter { it.isHttpLike() && it.tls == TlsMode.TERMINATE }.map { it.hostname }

        private fun addPassthroughFrontDoor(frontDoor: ServiceSpec, route: DoorRoute) {
            passthroughKeys += route.niceId
            publicResources += passthroughResource(route.niceId, frontDoor)
            sniRoutes += SniRoute(
                name = route.name,
                resourceNiceId = route.niceId,
                exactHosts = listOf(frontDoor.hostname) + if (frontDoor.wildcard) emptyList() else route.pageHosts,
                wildcardOf = if (frontDoor.wildcard) frontDoor.hostname else null,
                excludeHosts = route.excludeHosts.distinct().sorted(),
                priority = FRONTDOOR_SNI_PRIORITY,
            )
            redirectHosts += frontDoor.hostname
            if (frontDoor.wildcard) {
                wildcardRedirects += frontDoor.hostname
            }
        }

        private fun addTerminatedFrontDoor(frontDoor: ServiceSpec, pageHosts: List<String>) {
            val hostname = frontDoor.hostname
            if (frontDoor.maintenancePage) {
                maintenanceHosts += hostname
                maintenanceHosts += if (frontDoor.wildcard) listOf("*.$hostname") else pageHosts
            }
            publicResources += httpResource(NICE_PREFIX + frontDoor.key, frontDoor, hostname)
            if (frontDoor.wildcard) {
                publicResources += httpResource(NICE_PREFIX + "wildcard-" + frontDoor.key, frontDoor, "*.$hostname")
            }
        }

        /** The speed test and the status page of the node, which go to the homelab through [frontDoor]. */
        private fun addPageResources(frontDoor: ServiceSpec) {
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
                    // The kernel cannot add a PROXY protocol header, so the agent serves such a port.
                    val resources = if (service.proxyProtocol && tcp) proxiedResources else forwardedResources
                    resources += PublicResource(
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
            val (http, streams) = (withPorts + listOfNotNull(health)).partition { it.mode == ResourceMode.HTTP }
            return NodeDesired(
                node = node,
                managementHost = managementHost,
                publicResources = streams.sortedBy { it.niceId },
                httpResources = http.sortedBy { it.niceId },
                privateResources = privateResources.sortedBy { it.niceId },
                forwardedResources = forwardedResources.sortedBy { it.niceId },
                proxiedResources = proxiedResources.sortedBy { it.niceId },
                sniRoutes = sniRoutes.sortedBy { it.name },
                httpsRedirectHosts = redirectHosts.distinct().sorted(),
                wildcardRedirectDomains = wildcardRedirects.sorted(),
                rawPorts = rawPorts.sorted(),
                healthResourceNiceId = health?.niceId,
                healthPath = (healthTarget?.healthcheck as? HealthCheck.Http)?.path ?: "/",
                speedtestRedirect = redirect(pages.speedtestShared, pages.speedtestNode),
                statusRedirect = redirect(pages.statusShared, pages.statusNode),
                clientHost = config.clientHost,
                // The node ends TLS for the shared names in each mode, so they always get the page.
                maintenanceHosts = (maintenanceHosts + pages.shared).distinct().sorted(),
                meshHome = Mesh.homelabAddress(config.mesh),
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
        doors: FrontDoors,
    ): List<DesiredDnsRecord> {
        val domain = config.domain
        val plan = DnsPlan(config)
        val frontDoor = doors.primary
        val frontDoorNodes = if (frontDoor == null) emptyList() else nodes.filter { frontDoor.servesNode(it.id) }

        config.caaDomains.forEach { plan.addCaaRecords(it) }
        for (door in doors.all) {
            val doorNodes = nodes.filter { door.servesNode(it.id) }
            plan.addGeoSet(door.hostname, "root", doorNodes)
            if (door.wildcard) {
                plan.addGeoSet("*.${door.hostname}", "wildcard", doorNodes)
            }
        }
        // One record set per hostname, however many services share it (raw ports on one name), on
        // every node that serves any of them. The own name of a front door already has its set.
        services.filter { it !in doors.all && it.access == Access.PUBLIC && it.hostname !in doors.hostnames }
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
        fun addCaaRecords(domain: String) {
            val value = "${config.acme.caaIssuer}; validationmethods=dns-01"
            for (tag in listOf("issue", "issuewild")) {
                records += DesiredDnsRecord(
                    name = relativeName(domain),
                    fqdn = domain,
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

        private fun relativeName(fqdn: String) = config.relativeName(fqdn)
    }

    // ------------------------------------------------------------------ passthrough ports

    /**
     * Each node sends the stream of a passthrough service to the same address, the mesh address of
     * the homelab. So the port of the service must be the same on every node, and two services
     * must not get one port: allocated over all nodes' keys and raw ports.
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
     * Gives each HTTP resource its port on the mesh address of the homelab. The ports are in the
     * window above the passthrough ports, and one allocation covers all the nodes.
     */
    private fun withMeshPorts(config: AppConfig, nodes: Map<String, NodeDesired>): Map<String, NodeDesired> {
        val keys = nodes.values.flatMap { node -> node.httpResources.map { it.niceId } }
        val avoid = nodes.values.flatMap { node -> node.rawPorts.map { it.port } }.toSet()
        val ports = allocatePorts(keys, config.pangolin.passthroughPortBase + PORT_WINDOW, avoid)
        return nodes.mapValues { (_, node) ->
            node.copy(httpResources = node.httpResources.map { it.copy(meshPort = ports[it.niceId]) })
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
