package homegap.signin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON of the messages to the apps: each field is there, also one with its default value. */
val WIRE = Json { encodeDefaults = true }

/** A WireGuard peer that the app connects to, in the form that the app expects. */
@Serializable
data class Site(
    val siteId: Int,
    val name: String,
    /** `address:port` of the peer, as the device reaches it. */
    val endpoint: String,
    val publicKey: String,
    /** The address of the peer in the tunnel. The app sends its liveness probe to it. */
    val serverIP: String,
    val serverPort: Int,
    /** The ranges that the app sends to this peer. */
    val remoteSubnets: List<String> = emptyList(),
    val aliases: List<Alias> = emptyList(),
)

/** A private name and the address that the app gives for it. */
@Serializable
data class Alias(val alias: String, val aliasAddress: String)

/** The DNS settings that the server gives the app. They replace the settings of the app. */
@Serializable
data class DnsConfig(
    val upstreamDns: List<String> = emptyList(),
    val overrideDns: Boolean = false,
    val tunnelDns: Boolean = false,
)

/** The ranges and the names that a device has on the peer [siteId]. */
data class SiteData(val siteId: Int, val remoteSubnets: List<String>, val aliases: List<Alias>) {
    /** What a device that has [before] must remove and add to have this. A name with a new address is in both. */
    fun changeFrom(before: SiteData): Pair<SiteData, SiteData> {
        val removed = SiteData(siteId, before.remoteSubnets - remoteSubnets.toSet(), before.aliases - aliases.toSet())
        val added = SiteData(siteId, remoteSubnets - before.remoteSubnets.toSet(), aliases - before.aliases.toSet())
        return removed to added
    }

    val isEmpty: Boolean get() = remoteSubnets.isEmpty() && aliases.isEmpty()
}

/** What the device needs to start its tunnel. */
class Connection(val sites: List<Site>, val dns: DnsConfig? = null)

/** A node that the app measures before it connects. */
@Serializable
data class ExitNode(
    val exitNodeId: Int,
    val exitNodeName: String,
    val endpoint: String,
    val weight: Int = 1,
    val wasPreviouslyConnected: Boolean = false,
)

/** An exit that the app shows in its picker. The device sends all its traffic to one of the [siteIds]. */
@Serializable
data class ExitResource(
    val siteResourceId: Int,
    val niceId: String,
    val name: String,
    val siteIds: List<Int>,
    val siteNames: List<String>,
    val mode: String = "gateway",
    val enabled: Boolean = true,
)

/**
 * The WireGuard side of the control server. The control connection of a device calls it when the
 * device starts its tunnel, and the gateway makes the peers ready for the key of the device.
 */
interface TunnelGateway {
    /** The nodes for the latency test of the app. The app needs one entry or more. */
    fun exitNodes(): List<ExitNode>

    /** The exits for a device with [roles]. */
    fun exits(roles: List<String>): List<ExitResource> = emptyList()

    /**
     * Admits [publicKey] as the key of [device] and returns the peers for it. [node] is the id of
     * the node that the control connection came through, or null.
     */
    suspend fun admit(device: Device, publicKey: String, node: String? = null): Connection

    /** Removes the key [publicKey] of [device]: its control connection ended, or it lost its access. */
    suspend fun remove(device: Device, publicKey: String) {}

    /** The directory of the homelab changed. */
    fun refresh() {}

    /**
     * True if this gateway has the peer of the homelab. Its server keeps the tunnel of a device
     * across a new control connection. A different server gives the connection back.
     */
    val home: Boolean get() = false

    /** Takes the tunnels that [running] had before this server started. Each has its key. */
    fun restore(running: List<Device>) {}

    /** Emits after each change of what a connected device can have on a peer. */
    val changes: Flow<Unit> get() = emptyFlow()

    /**
     * The ranges and the names that [device] has now on the peer of the homelab, or null if this
     * gateway gives none. [node] is the id of the node of the control connection.
     */
    fun siteData(device: Device, node: String?): SiteData? = null
}

/** A gateway with no peer yet. The app connects to the control server and gets an empty tunnel. */
class NoTunnels(private val host: String) : TunnelGateway {
    override fun exitNodes(): List<ExitNode> = listOf(ExitNode(1, "homelab", host))

    override suspend fun admit(device: Device, publicKey: String, node: String?): Connection =
        Connection(emptyList())
}
