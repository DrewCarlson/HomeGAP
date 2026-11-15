package homegap.reconcile

import homegap.discovery.DockerContainerSummary
import homegap.model.DesiredState
import homegap.model.ResourceMode
import homegap.provision.Mesh

/**
 * The ports of the mesh address of the homelab that the plan needs: one for each raw TCP or UDP
 * resource, to its target. A node sends the traffic of the resource to that port.
 *
 * The target of a forward is an address. The orchestrator knows the address of each container
 * from Docker, and a Docker event starts a pass, so the mesh agent does not look names up.
 */
object MeshForwards {
    /** [forwards] are ready for the agent. [unknown] are the target hosts that have no address. */
    data class Result(val forwards: List<Mesh.Forward>, val unknown: List<String>)

    fun of(plan: DesiredState, containers: List<DockerContainerSummary>): Result {
        val doors = plan.frontDoors.map { it.containerName }.toSet()
        val doorNetworks = containers.filter { it.name in doors }.flatMap { it.networkSettings.networks.keys }.toSet()
        val addresses = containers.mapNotNull { container -> address(container, doorNetworks)?.let { container.name to it } }
            .toMap()
        val wanted = plan.nodes.values.flatMap { it.publicResources }
            .filter { it.mode != ResourceMode.HTTP && it.proxyPort != null && !it.paused }
            .distinctBy { it.mode to it.proxyPort }
        val forwards = wanted.mapNotNull { resource ->
            val host = resource.target.host
            val address = if (isAddress(host)) host else addresses[host]
            address?.let { Mesh.Forward(resource.mode.wire, resource.proxyPort!!, "$it:${resource.target.port}") }
        }
        val unknown = wanted.map { it.target.host }.filter { !isAddress(it) && it !in addresses }.distinct()
        return Result(forwards, unknown)
    }

    /**
     * The address of [container] that the mesh namespace can reach: its address on a network of
     * a front door, which is the Traefik network. A container with one address has no choice.
     */
    private fun address(container: DockerContainerSummary, doorNetworks: Set<String>): String? {
        val networks = container.networkSettings.networks.filter { it.value.ipAddress.isNotEmpty() }
        val shared = networks.filterKeys { it in doorNetworks }.values.firstOrNull()
        return (shared ?: networks.values.singleOrNull())?.ipAddress
    }

    private fun isAddress(host: String): Boolean = host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
}
