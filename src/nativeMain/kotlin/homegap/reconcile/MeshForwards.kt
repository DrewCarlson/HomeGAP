package homegap.reconcile

import homegap.config.VpnConfig
import homegap.discovery.DockerContainerSummary
import homegap.identity.Resource
import homegap.identity.VpnAccess
import homegap.model.DesiredState
import homegap.model.PublicResource
import homegap.model.RawPort
import homegap.model.ResourceMode
import homegap.node.AgentSpec
import homegap.provision.Mesh

/**
 * What the mesh forwards for the plan.
 *
 * The homelab end has one port of its mesh address for each resource, to the target of the
 * resource. A node sends the traffic of the resource to that port: the Traefik of
 * the node for a resource of Pangolin, and the kernel of the node for a forwarded resource.
 *
 * The target of a forward is an address. The orchestrator knows the address of each container
 * from Docker, and a Docker event starts a pass, so the mesh agent does not look names up.
 */
object MeshForwards {
    /**
     * [homelab] are ready for the mesh agent. [nodePorts] are the public ports that each node
     * forwards in the kernel. [agentPorts] are the public TCP ports that the agent of each node
     * serves. [unknown] are the target hosts that have no address.
     */
    data class Result(
        val homelab: List<Mesh.Forward> = emptyList(),
        val nodePorts: Map<String, List<RawPort>> = emptyMap(),
        val agentPorts: Map<String, List<Int>> = emptyMap(),
        val unknown: List<String> = emptyList(),
        /** The peer of the VPN on the homelab, with the private names that devices reach. */
        val vpn: Mesh.Vpn? = null,
    ) {
        /**
         * Adds the VPN: its peer on the homelab with the [targets] at the addresses of
         * [resources], and its UDP port on each node, which the node forwards to the homelab.
         */
        fun withVpn(
            config: VpnConfig,
            targets: List<PrivateTarget>,
            resources: List<Resource>,
            access: VpnAccess = VpnAccess(),
        ): Result {
            val addresses = resources.associate { it.name to it.address }
            val vpn = Mesh.Vpn(
                subnet = config.clientSubnet,
                address = Mesh.vpnAddress(config.clientSubnet),
                port = config.port,
                exitRoles = access.exitRoles,
                resolvers = access.resolvers,
                resolverRoles = access.resolverRoles,
                targets = targets.mapNotNull { target ->
                    addresses[target.name]?.let {
                        Mesh.VpnTarget(it, target.target, target.tcpPorts, target.udpPorts, target.roles)
                    }
                },
            )
            val port = RawPort(config.port, ResourceMode.UDP.wire)
            return copy(vpn = vpn, nodePorts = nodePorts.mapValues { (_, ports) -> (ports + port).distinct() })
        }
    }

    /** A private name of the plan with the address of its service on a network of the mesh namespace. */
    data class PrivateTarget(
        val name: String,
        val target: String,
        val tcpPorts: List<Int>,
        val udpPorts: List<Int>,
        val roles: List<String>,
    )

    /**
     * The private names of the plan that the VPN can serve, and the hosts of the others, which
     * have no address that the mesh namespace reaches.
     */
    fun privateTargets(
        plan: DesiredState,
        containers: List<DockerContainerSummary>,
    ): Pair<List<PrivateTarget>, List<String>> {
        val addresses = addresses(plan, containers)
        val wanted = plan.nodes.values.flatMap { it.privateResources }
            .filter { it.mode == HOST_MODE && !it.paused && it.alias.isNotBlank() }
            .distinctBy { it.alias }
        val targets = wanted.mapNotNull { resource ->
            val host = resource.destination
            val address = if (isAddress(host)) host else addresses[host]
            val (tcp, udp) = ports(resource.tcpPorts) to ports(resource.udpPorts)
            address?.let { PrivateTarget(resource.alias, it, tcp, udp, resource.roles) }
        }
        val served = targets.map { it.name }.toSet()
        return targets to wanted.filter { it.alias !in served }.map { it.destination }.distinct()
    }

    private fun ports(list: String): List<Int> = list.split(',').mapNotNull { it.trim().toIntOrNull() }

    private const val HOST_MODE = "host"

    fun of(plan: DesiredState, containers: List<DockerContainerSummary>): Result {
        val addresses = addresses(plan, containers)
        val wanted = plan.nodes.values.flatMap { it.resources }
            .filter { portOf(it) != null && !it.paused }
            .distinctBy { protocolOf(it) to portOf(it) }
        val forwards = wanted.mapNotNull { resource ->
            addressOf(resource, addresses)?.let { address ->
                Mesh.Forward(protocolOf(resource), portOf(resource)!!, "$address:${resource.target.port}")
            }
        }
        return Result(
            homelab = forwards,
            nodePorts = plan.nodes.mapValues { (_, node) -> node.forwardedPorts },
            agentPorts = plan.nodes.mapValues { (_, node) -> AgentSpec.of(node).listeners.map { it.port } },
            unknown = wanted.filter { addressOf(it, addresses) == null }.map { it.target.host }.distinct(),
        )
    }

    private fun portOf(resource: PublicResource): Int? =
        if (resource.mode == ResourceMode.HTTP) resource.meshPort else resource.proxyPort

    /** An HTTP resource is a TCP forward. */
    private fun protocolOf(resource: PublicResource): String =
        if (resource.mode == ResourceMode.UDP) ResourceMode.UDP.wire else ResourceMode.TCP.wire

    private fun addressOf(resource: PublicResource, addresses: Map<String, String>): String? {
        val host = resource.target.host
        return if (isAddress(host)) host else addresses[host]
    }

    /**
     * The address of each container that the mesh namespace can reach: its address on a network
     * of a front door, which is the Traefik network. A container with one address has no choice.
     */
    private fun addresses(plan: DesiredState, containers: List<DockerContainerSummary>): Map<String, String> {
        val doors = plan.frontDoors.map { it.containerName }.toSet()
        val doorNetworks = containers.filter { it.name in doors }.flatMap { it.networkSettings.networks.keys }.toSet()
        return containers.mapNotNull { container ->
            val networks = container.networkSettings.networks.filter { it.value.ipAddress.isNotEmpty() }
            val shared = networks.filterKeys { it in doorNetworks }.values.firstOrNull()
            (shared ?: networks.values.singleOrNull())?.let { container.name to it.ipAddress }
        }.toMap()
    }

    private fun isAddress(host: String): Boolean = host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
}
