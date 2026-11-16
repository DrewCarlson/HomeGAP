package homegap.signin

import homegap.identity.Directory
import homegap.identity.NodeNames
import homegap.identity.Signed
import homegap.platform.Files
import homegap.platform.watchDirectory
import homegap.provision.Mesh
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)

    override val changes: Flow<Unit> = changed.asSharedFlow()

    /** The devices that are connected: device id to the device and its key. */
    private val connected = LinkedHashMap<String, Pair<Device, String>>()

    override val home: Boolean get() = true

    override fun restore(running: List<Device>) = synchronized(lock) {
        running.forEach { device -> device.tunnelKey?.let { connected[device.id] = device to it } }
        write()
    }

    override fun exitNodes(): List<ExitNode> = listOf(ExitNode(SITE_ID, SITE_NAME, host))

    override suspend fun admit(device: Device, publicKey: String, node: String?): Connection {
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
        val data = siteData(device, node) ?: error("the device has no access")
        val resolvers = users?.vpn?.resolversFor(roles).orEmpty()
        val site = Site(
            siteId = SITE_ID,
            name = SITE_NAME,
            endpoint = "$host:${status.port}",
            publicKey = status.publicKey,
            serverIP = status.address,
            serverPort = status.port,
            remoteSubnets = data.remoteSubnets,
            aliases = data.aliases,
        )
        val dns = DnsConfig(resolvers.map { "$it:$DNS_PORT" }, overrideDns = true, tunnelDns = true)
        // A node that has no key yet comes later, through watch().
        return Connection(listOf(site) + exitSites(roles, publicKey, status), dns.takeIf { resolvers.isNotEmpty() })
    }

    override fun exitSites(device: Device, publicKey: String): List<Site> =
        rolesOf(device)?.let { exitSites(it, publicKey, status()) }.orEmpty()

    /** The exit of each node that has the key of the device. The app must have the site to select its exit. */
    private fun exitSites(roles: List<String>, publicKey: String, status: Mesh.VpnStatus?): List<Site> {
        if (directory()?.vpn?.nodeExitFor(roles) != true) return emptyList()
        return status?.exits.orEmpty().filter { publicKey in it.peers }.map { exit ->
            Site(siteIdOf(exit), exit.node, "${exit.host}:${exit.port}", exit.publicKey, exit.address, exit.port)
        }
    }

    override suspend fun watch() {
        var last = status()?.exits
        watchDirectory(dir) { it == Mesh.VPN_STATUS_FILE }.collect {
            val exits = status()?.exits
            if (exits != last) {
                last = exits
                changed.tryEmit(Unit)
            }
        }
    }

    override fun siteData(device: Device, node: String?): SiteData? {
        val roles = rolesOf(device) ?: return null
        val users = directory()
        val reachable = users?.resources.orEmpty().filter { it.allows(roles) }.map { Alias(it.name, it.address) }
        val names = reachable.map { it.alias }.toSet()
        val public = publicAliases(users?.nodeNames.orEmpty(), node).filter { it.alias !in names }
        // The app sends the lookups for these servers to this peer, which sends them on.
        val resolvers = users?.vpn?.resolversFor(roles).orEmpty()
        return SiteData(SITE_ID, resolvers.map { "$it/32" }, reachable + public)
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
    override fun refresh() {
        synchronized(lock) { write() }
        changed.tryEmit(Unit)
    }

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

/**
 * The public names for a device that connects through [node]. Each name of that node answers
 * with that node, whatever public DNS says. A name that only other nodes serve answers with
 * one of them, because a wildcard name of [node] must not take it.
 */
internal fun publicAliases(nodeNames: Map<String, NodeNames>, node: String?): List<Alias> {
    val own = nodeNames[node] ?: return emptyList()
    val others = nodeNames.entries.filter { it.key != node }.sortedBy { it.key }.flatMap { (_, other) ->
        other.names.filter { it !in own.names }.map { Alias(it, other.address) }
    }
    return own.names.map { Alias(it, own.address) } + others.distinctBy { it.alias }
}
