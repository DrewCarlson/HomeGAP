package homegap.signin

import homegap.identity.Directory
import homegap.identity.Signed
import homegap.platform.Files
import homegap.platform.watchDirectory
import homegap.provision.Mesh
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway to the VPN peer of the homelab. The mesh agent owns the WireGuard interface. This
 * class tells it which devices are connected, through the file [Mesh.VPN_PEERS_FILE] in [dir],
 * and reads what the agent applied from [Mesh.VPN_STATUS_FILE].
 *
 * A device reaches the peer at [host], a name that goes to a node. The node forwards the packets
 * to the homelab and cannot read them. The roles of the device come from the signed [directory],
 * and they decide which private names the device gets and which the firewall of the peer permits.
 */
class HomeTunnel(
    private val dir: String,
    private val host: String,
    private val directory: () -> Directory?,
) : TunnelGateway {
    private val lock = SynchronizedObject()

    /** The devices that are connected: device id to the device and its key. */
    private val connected = LinkedHashMap<String, Pair<Device, String>>()

    init {
        // A start of this server ends each control connection, so no device is connected.
        write()
    }

    override fun exitNodes(): List<ExitNode> = listOf(ExitNode(SITE_ID, SITE_NAME, host))

    override suspend fun admit(device: Device, publicKey: String): Connection {
        val roles = rolesOf(device) ?: error("the device has no access")
        synchronized(lock) {
            connected[device.id] = device to publicKey
            write()
        }
        // The app must not start before the peer has its key.
        val status = withTimeout(ADMIT_TIMEOUT) {
            watchDirectory(dir) { it == Mesh.VPN_STATUS_FILE }
                .map { status() }
                .first { it != null && publicKey in it.peers }
        } ?: error("the VPN peer gave no answer")
        val users = directory()
        val aliases = users?.resources.orEmpty()
            .filter { it.allows(roles) }
            .map { Alias(it.name, it.address) }
        // The app sends the lookups for these servers to this peer, which sends them on.
        val resolvers = users?.vpn?.resolversFor(roles).orEmpty()
        val site = Site(
            siteId = SITE_ID,
            name = SITE_NAME,
            endpoint = "$host:${status.port}",
            publicKey = status.publicKey,
            serverIP = status.address,
            serverPort = status.port,
            remoteSubnets = resolvers.map { "$it/32" },
            aliases = aliases,
        )
        val dns = DnsConfig(resolvers.map { "$it:$DNS_PORT" }, overrideDns = true, tunnelDns = true)
        // The exit of each node that has the key of the device. The app must have the site to select its exit.
        val permitted = users?.vpn?.nodeExitFor(roles) == true
        val exits = status.exits.filter { permitted && publicKey in it.peers }.map { exit ->
            Site(siteIdOf(exit), exit.node, "${exit.host}:${exit.port}", exit.publicKey, exit.address, exit.port)
        }
        return Connection(listOf(site) + exits, dns.takeIf { resolvers.isNotEmpty() })
    }

    override fun exits(roles: List<String>): List<ExitResource> {
        val access = directory()?.vpn ?: return emptyList()
        val nodes = status()?.exits.orEmpty().filter { access.nodeExitFor(roles) }.map { exit ->
            val id = siteIdOf(exit)
            ExitResource(id, "exit-${exit.node}", "Exit via ${exit.node}", listOf(id), listOf(exit.node))
        }
        val home = ExitResource(SITE_ID, "exit-$SITE_NAME", "Exit via $SITE_NAME", listOf(SITE_ID), listOf(SITE_NAME))
        return nodes + listOfNotNull(home.takeIf { access.exitFor(roles) })
    }

    /** The site of the exit of a node: the last part of its address, which is 10 or more. The homelab is site 1. */
    private fun siteIdOf(exit: Mesh.ExitStatus): Int = exit.address.substringAfterLast('.').toInt()

    override suspend fun remove(device: Device, publicKey: String) = synchronized(lock) {
        // A device that came back with a new key keeps that key.
        if (connected[device.id]?.second == publicKey) {
            connected.remove(device.id)
            write()
        }
    }

    /** Applies a change of the directory to the devices that are connected: new roles, a block, a removal. */
    override fun refresh() = synchronized(lock) { write() }

    private fun rolesOf(device: Device): List<String>? = directory()?.rolesOf(device.id, device.userId)

    private fun status(): Mesh.VpnStatus? = Files.readTextOrNull("$dir/${Mesh.VPN_STATUS_FILE}")
        ?.let { runCatching { Signed.JSON.decodeFromString<Mesh.VpnStatus>(it) }.getOrNull() }

    private fun write() {
        val peers = connected.values.mapNotNull { (device, key) ->
            rolesOf(device)?.let { Mesh.VpnPeer(key, device.address, it) }
        }
        val text = Signed.JSON.encodeToString(peers) + "\n"
        if (Files.readTextOrNull("$dir/${Mesh.VPN_PEERS_FILE}") == text) return
        Files.mkdirs(dir)
        Files.writeAtomic("$dir/${Mesh.VPN_PEERS_FILE}", text)
    }

    private companion object {
        const val SITE_ID = 1
        const val SITE_NAME = "homelab"
        const val DNS_PORT = 53
        val ADMIT_TIMEOUT = 10.seconds
    }
}
