package homegap.signin

import kotlinx.serialization.Serializable

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

/**
 * The WireGuard side of the control server. The control connection of a device calls it when the
 * device starts its tunnel, and the gateway makes the peers ready for the key of the device.
 */
interface TunnelGateway {
    /** The nodes for the latency test of the app. The app needs one entry or more. */
    fun exitNodes(): List<ExitNode>

    /** Admits [publicKey] as the key of [device] and returns the peers for it. */
    suspend fun admit(device: Device, publicKey: String): Connection

    /** Removes the key [publicKey] of [device]: its control connection ended, or it lost its access. */
    suspend fun remove(device: Device, publicKey: String) {}

    /** The directory of the homelab changed. */
    fun refresh() {}
}

/** A gateway with no peer yet. The app connects to the control server and gets an empty tunnel. */
class NoTunnels(private val host: String) : TunnelGateway {
    override fun exitNodes(): List<ExitNode> = listOf(ExitNode(1, "homelab", host))

    override suspend fun admit(device: Device, publicKey: String): Connection = Connection(emptyList())
}
