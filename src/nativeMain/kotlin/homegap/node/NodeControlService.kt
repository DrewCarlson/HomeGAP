package homegap.node

import homegap.config.VpnConfig
import homegap.identity.Directory
import homegap.identity.IdentityStore
import homegap.identity.Signed
import homegap.platform.Files
import homegap.platform.runCatchingCancellable
import homegap.provision.Mesh
import homegap.signin.Connection
import homegap.signin.Device
import homegap.signin.Devices
import homegap.signin.ExitNode
import homegap.signin.ExitResource
import homegap.signin.SignInServer
import homegap.signin.Site
import homegap.signin.TunnelGateway
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The control side of a node: the same server as the sign-in of the homelab, for the time that
 * the homelab does not answer. The Traefik of the node sends the apps here only then.
 *
 * The node has the directory of users with the signature of the homelab, which the homelab sends
 * over the mesh, and it accepts no other. It signs its sessions with its own key. The directory
 * does not name that key, so a session of a node gives the exit of this node and no access to the
 * homelab. A node cannot make a user, a role or a session that the homelab accepts.
 */
class NodeControlService(
    private val stateDir: String,
    private val scope: CoroutineScope,
    private val exit: NodeExitService,
) {
    private val logger = KotlinLogging.logger("control")
    private val identityDir = "$stateDir/identity"
    private val directoryFile = "$identityDir/${IdentityStore.DIRECTORY_FILE}"
    private val devicesFile = "$identityDir/${Mesh.VPN_DEVICES_FILE}"
    private var control: Mesh.NodeControl? = null
    private var started = false
    private var knownText: String? = null
    private var known: List<Device> = emptyList()

    /** Starts the server for [spec], once. A node with no control side in its spec serves nothing. */
    fun apply(spec: Mesh.NodeExit?) {
        val wanted = spec?.control ?: return
        control = wanted
        Files.mkdirs(identityDir)
        Files.writeAtomic("$identityDir/${IdentityStore.PUBLIC_KEY_FILE}", wanted.authorityKey + "\n")
        if (started) return
        started = true
        val siteId = spec.address.substringAfterLast('.').toInt()
        val server = SignInServer(
            dir = "$stateDir/control",
            identityDir = identityDir,
            setup = SignInServer.Setup(vpn = VpnConfig(spec.subnet, wanted.utilitySubnet)),
            role = SignInServer.Role(
                issuer = "node:${wanted.node}",
                ownSessions = true,
                pool = Devices.Pool(node = siteId, known = ::knownDevices),
                gateway = { directory -> Gateway(wanted, siteId, directory) },
            ),
        )
        scope.launch {
            val host = wanted.listen.substringBeforeLast(':')
            val failure = runCatchingCancellable { server.run(host, wanted.listen.substringAfterLast(':').toInt()) }
            logger.error { "the control side of the node stopped: ${failure.exceptionOrNull()?.message}" }
            started = false
        }
    }

    /**
     * Keeps what the homelab sent for an outage: the directory, if the homelab signed it and it is
     * not older than the one that the node has, and the devices of the homelab.
     */
    fun onState(state: Mesh.NodeState) {
        val key = control?.authorityKey ?: return
        val sent = Signed.directory(state.directory, key)
        if (state.directory.isNotEmpty() && sent == null) {
            logger.error { "the directory from the mesh has no valid signature of the homelab: the node keeps its own" }
        }
        val kept = Files.readTextOrNull(directoryFile)?.let { Signed.directory(it, key) }
        val newer = sent != null && sent.version >= (kept?.version ?: 0)
        if (newer && Files.readTextOrNull(directoryFile) != state.directory) {
            Files.writeAtomic(directoryFile, state.directory)
        }
        val readable = state.devices.isNotEmpty() && parse(state.devices) != null
        if (readable && Files.readTextOrNull(devicesFile) != state.devices) {
            Files.writeAtomic(devicesFile, state.devices, OWNER_ONLY)
        }
    }

    /** The devices that the homelab created. The node accepts them with their own secrets and addresses. */
    private fun knownDevices(): List<Device> {
        val text = Files.readTextOrNull(devicesFile) ?: return emptyList()
        if (text != knownText) {
            known = parse(text).orEmpty()
            knownText = text
        }
        return known
    }

    private fun parse(text: String): List<Device>? =
        runCatching { Signed.JSON.decodeFromString<List<Device>>(text) }.getOrNull()

    /** The tunnel that a node gives: its own exit, for a device with a role of the exits. */
    private inner class Gateway(
        private val spec: Mesh.NodeControl,
        private val siteId: Int,
        private val directory: () -> Directory?,
    ) : TunnelGateway {
        override fun exitNodes(): List<ExitNode> = listOf(ExitNode(siteId, spec.node, spec.host))

        override fun exits(roles: List<String>): List<ExitResource> {
            if (directory()?.vpn?.nodeExitFor(roles) != true) return emptyList()
            val name = spec.node
            return listOf(ExitResource(siteId, "exit-$name", "Exit via $name", listOf(siteId), listOf(name)))
        }

        override suspend fun admit(device: Device, publicKey: String): Connection {
            val users = directory() ?: error("the node has no directory")
            val roles = when {
                device.userId.isEmpty() -> users.machine(device.id)?.takeIf { !it.blocked }?.roles
                else -> users.user(device.userId)?.takeIf { !it.blocked }?.roles
            } ?: error("the device has no access")
            check(users.vpn.nodeExitFor(roles)) { "the device has no role of the exits" }
            val (exitSpec, key) = exit.admitLocal(publicKey, device.address)
            val site = Site(siteId, spec.node, "${spec.host}:${exitSpec.port}", key, exitSpec.address, exitSpec.port)
            return Connection(listOf(site))
        }

        override suspend fun remove(device: Device, publicKey: String) = exit.removeLocal(publicKey)
    }

    private companion object {
        val OWNER_ONLY = "600".toInt(8)
    }
}
