package homegap.model

import homegap.config.AppConfig
import homegap.config.meshDir
import homegap.discovery.DockerContainerSummary
import homegap.discovery.Labels
import homegap.platform.Files
import homegap.provision.Mesh

/**
 * The services of HomeGAP itself and of the homelab host that the config turns on. The planner
 * gets each one as a container with labels, so it follows the same rules as a service of a real
 * container.
 */
object HomelabServices {
    /** The name that the dashboard shows as the container of a service of the host. */
    const val HOST_NAME = "homelab"

    /** The container of the sign-in in the stack of the homelab, and its port. */
    const val SIGN_IN_CONTAINER = "homegap-sign-in"
    const val SIGN_IN_PORT = 8090

    /** The container that holds the network namespace of the mesh in the stack of the homelab. */
    const val MESH_CONTAINER = "homegap-mesh"

    /** The label of Docker Compose that holds the name of the service of a container. */
    const val COMPOSE_SERVICE = "com.docker.compose.service"

    /** True if [container] is the sign-in of the stack of the homelab. */
    fun isSignIn(container: DockerContainerSummary): Boolean =
        container.name == SIGN_IN_CONTAINER || container.labels[COMPOSE_SERVICE] == SIGN_IN_CONTAINER

    /** True if [container] holds the network namespace of the mesh. */
    fun isMesh(container: DockerContainerSummary): Boolean =
        container.name == MESH_CONTAINER || container.labels[COMPOSE_SERVICE] == MESH_CONTAINER

    /** True if [container] is a container of the stack that the pass reads and that has no label. */
    fun isOwn(container: DockerContainerSummary): Boolean = isSignIn(container) || isMesh(container)

    /** The services, with the address of the host that the mesh agent found. */
    fun containers(config: AppConfig): List<DockerContainerSummary> {
        val host = Files.readTextOrNull("${config.meshDir}/${Mesh.HOST_GATEWAY_FILE}")?.trim()?.ifEmpty { null }
        return containers(config, host)
    }

    /**
     * [hostAddress] is the address of the host as the mesh namespace reaches it. The SSH service
     * of the host is not in the result until the mesh agent has written that address.
     */
    fun containers(config: AppConfig, hostAddress: String?): List<DockerContainerSummary> =
        listOfNotNull(signIn(config), ssh(config, hostAddress))

    /** The name of the sign-in, which the apps connect to. Each node sends it to the sign-in container. */
    private fun signIn(config: AppConfig): DockerContainerSummary? {
        val name = config.signInHost ?: return null
        val labels = mapOf(
            Labels.EXPOSE to name,
            Labels.PORT to SIGN_IN_PORT.toString(),
            Labels.HOST to SIGN_IN_CONTAINER,
        )
        return DockerContainerSummary(id = "$HOST_NAME-sign-in", names = listOf("/$SIGN_IN_CONTAINER"), labels = labels)
    }

    private fun ssh(config: AppConfig, hostAddress: String?): DockerContainerSummary? {
        val ssh = config.homelab.ssh
        if (!ssh.enabled || hostAddress == null) return null
        val labels = buildMap {
            put(Labels.EXPOSE, ssh.host(config.domain))
            put(Labels.ACCESS, "private")
            put(Labels.PROTOCOL, "tcp")
            put(Labels.PORT, ssh.port.toString())
            put(Labels.HOST, hostAddress)
            if (ssh.roles.isNotEmpty()) put(Labels.ROLES, ssh.roles.joinToString(","))
        }
        return DockerContainerSummary(id = "$HOST_NAME-ssh", names = listOf("/$HOST_NAME"), labels = labels)
    }
}
