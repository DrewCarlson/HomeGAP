package homegap.model

import homegap.config.AppConfig
import homegap.config.tunnelsDir
import homegap.discovery.DockerContainerSummary
import homegap.discovery.Labels
import homegap.tunnels.TunnelFiles

/**
 * The services of the homelab host itself that the config turns on. The planner gets each one as
 * a container with labels, so it follows the same rules as a service of a real container.
 */
object HomelabServices {
    /** The name that the dashboard shows as the container of a service of the host. */
    const val HOST_NAME = "homelab"

    /** The services of the host, with the address of the host that the tunnel supervisor found. */
    fun containers(config: AppConfig): List<DockerContainerSummary> =
        containers(config, TunnelFiles.readHostGateway(config.tunnelsDir))

    /**
     * [hostAddress] is the address of the host as the `tunnels` container reaches it. The result
     * is empty until the tunnel supervisor has written that address.
     */
    fun containers(config: AppConfig, hostAddress: String?): List<DockerContainerSummary> {
        val ssh = config.homelab.ssh
        if (!ssh.enabled || hostAddress == null) return emptyList()
        val labels = buildMap {
            put(Labels.EXPOSE, ssh.host(config.domain))
            put(Labels.ACCESS, "private")
            put(Labels.PROTOCOL, "tcp")
            put(Labels.PORT, ssh.port.toString())
            put(Labels.HOST, hostAddress)
            if (ssh.roles.isNotEmpty()) put(Labels.ROLES, ssh.roles.joinToString(","))
        }
        return listOf(DockerContainerSummary(id = "$HOST_NAME-ssh", names = listOf("/$HOST_NAME"), labels = labels))
    }
}
