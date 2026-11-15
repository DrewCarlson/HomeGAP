package homegap.reconcile

import homegap.discovery.DockerContainerSummary
import homegap.model.DesiredState
import homegap.model.PublicResource
import homegap.model.RawPort
import homegap.model.ResourceMode
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
     * forwards in the kernel. [unknown] are the target hosts that have no address.
     */
    data class Result(
        val homelab: List<Mesh.Forward> = emptyList(),
        val nodePorts: Map<String, List<RawPort>> = emptyMap(),
        val unknown: List<String> = emptyList(),
    )

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
