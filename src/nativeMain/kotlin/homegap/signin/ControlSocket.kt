package homegap.signin

import homegap.identity.Signed
import homegap.platform.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The control connection of a device: a WebSocket with JSON messages `{type, data}`, in the
 * protocol of the Pangolin apps. The device says when it starts its tunnel and gets the peers to
 * connect to. The server can end the connection with a reason that the app shows.
 */
class ControlSocket(
    private val signIn: SignIn,
    private val devices: Devices,
    private val gateway: TunnelGateway,
    private val utilitySubnet: String,
) {
    private val logger = KotlinLogging.logger("control")

    private val lock = SynchronizedObject()

    /** The removal that waits for each device whose control connection ended: device id to the job. */
    private val departures = mutableMapOf<String, Job>()

    /** Adds the WebSocket to [parent], which is the route `/api/v1`. */
    fun init(parent: Route) {
        if (gateway.home) {
            // A start of this server ends each control connection. The apps keep their tunnels and connect again.
            val running = devices.running()
            gateway.restore(running)
            running.forEach { device -> device.tunnelKey?.let { depart(parent.application, device, it) } }
        }
        parent.webSocket("/ws") { serve() }
    }

    /** Removes the key of [device] after [GRACE], unless its app connects again before that. */
    private fun depart(scope: CoroutineScope, device: Device, key: String) {
        val job = scope.launch {
            delay(GRACE)
            runCatchingCancellable { gateway.remove(device, key) }
        }
        synchronized(lock) { departures.put(device.id, job) }?.cancel()
    }

    private fun arrive(device: Device) {
        synchronized(lock) { departures.remove(device.id) }?.cancel()
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        val token = call.request.queryParameters["token"].orEmpty()
        val device = signIn.tunnelClaims(token)?.let { devices.get(it.device) }
        if (device == null) {
            send(TERMINATE, reason("UNAUTHORIZED", "Sign in again."))
            return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        logger.info { "device '${device.name}' (${device.id}) connected" }
        val node = call.request.headers[NODE_HEADER]?.take(MAX_NAME)
        arrive(device)
        val live = LiveData()
        // An app that runs its tunnel does not start it again after a new connection. Only the
        // server of the homelab can keep that tunnel. A different server ends a connection that
        // starts no tunnel, so the app goes back to the homelab when the homelab answers again.
        val resumed = if (gateway.home) resume(device, node) else null
        val idle = if (gateway.home) null else launch {
            delay(IDLE)
            close(CloseReason(CloseReason.Codes.NORMAL, "no tunnel"))
        }
        var sites: List<Site> = resumed?.second.orEmpty()
        var key: String? = resumed?.first
        if (resumed != null) live.lock.withLock { live.sent = gateway.siteData(device, node) }
        // A change of the directory reaches the app at once: it needs no new connection for a new name.
        val updates = launch { gateway.changes.collect { update(live, device, node) } }
        try {
            for (frame in incoming) {
                val message = (frame as? Frame.Text)?.let { parse(it.readText()) } ?: continue
                val data = message["data"] as? JsonObject ?: JsonObject(emptyMap())
                val chain = data.text("chainId")
                // A block of the user, or the end of the token, ends the connection at the next message.
                if (signIn.tunnelClaims(token) == null) {
                    send(TERMINATE, reason("UNAUTHORIZED", "Your access ended. Sign in again."))
                    return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                }
                when (message.text("type")) {
                    "olm/ping/request" -> send(
                        "olm/ping/exitNodes",
                        buildJsonObject {
                            put("exitNodes", WIRE.encodeToJsonElement(gateway.exitNodes()))
                            chain?.let { put("chainId", it) }
                        },
                    )
                    // The app sends this form first, for old servers. The real one comes after the list of nodes.
                    "olm/wg/register" -> if ((data["backwardsCompatible"] as? JsonPrimitive)?.booleanOrNull != true) {
                        val admitted = register(device, data, chain, node)
                        devices.seen(device.id, agentOf(data), node)
                        if (admitted != null) {
                            sites = admitted.second
                            key = admitted.first
                            idle?.cancel()
                            live.lock.withLock { live.sent = gateway.siteData(device, node) }
                        }
                    }
                    "olm/disconnecting" -> devices.tunnel(device.id, null)
                    "olm/wg/relay" -> relay(sites, data, chain)
                    "olm/ping" -> send("pong", JsonObject(emptyMap()))
                    else -> Unit
                }
            }
        } finally {
            updates.cancel()
            idle?.cancel()
            key?.let { leave(device, it) }
            devices.seen(device.id)
            logger.info { "device '${device.name}' (${device.id}) disconnected" }
        }
    }

    /**
     * The control connection of [device] ended. The app keeps its tunnel and connects again, so
     * the server of the homelab keeps the key for [GRACE]. An app that said that it stops loses
     * the key at once.
     */
    private suspend fun DefaultWebSocketServerSession.leave(device: Device, key: String) {
        if (gateway.home && devices.get(device.id)?.tunnelKey == key) {
            depart(call.application, device, key)
        } else {
            withContext(NonCancellable) { runCatchingCancellable { gateway.remove(device, key) } }
        }
    }

    /** Admits the key of the device and sends it its peers. Returns the key and the peers, or null after a failure. */
    private suspend fun DefaultWebSocketServerSession.register(
        device: Device,
        data: JsonObject,
        chain: String?,
        node: String?,
    ): Pair<String, List<Site>>? {
        val key = data.text("publicKey")?.takeIf { KEY.matches(it) }
        if (key == null) {
            send("olm/error", reason("INVALID_REQUEST", "The device sent no valid key."))
            return null
        }
        val connection = runCatchingCancellable { gateway.admit(device, key, node) }.getOrElse { e ->
            logger.error { "could not admit the device '${device.name}': ${e.message}" }
            send("olm/error", reason("SERVER_ERROR", "HomeGAP could not start the tunnel. Try again."))
            return null
        }
        send(
            "olm/wg/connect",
            buildJsonObject {
                put("sites", WIRE.encodeToJsonElement(connection.sites))
                put("tunnelIP", "${device.address}/${devices.prefix}")
                put("utilitySubnet", utilitySubnet)
                connection.dns?.let { put("dnsConfig", WIRE.encodeToJsonElement(it)) }
                chain?.let { put("chainId", it) }
            },
        )
        devices.tunnel(device.id, key)
        return key to connection.sites
    }

    /**
     * Admits the key of the tunnel that the app of [device] runs, and sends the app the peers that
     * it must have now. The app keeps its tunnel across a new control connection and sends no
     * new start message. Returns the key and the peers, or null if the device runs no tunnel.
     */
    private suspend fun DefaultWebSocketServerSession.resume(device: Device, node: String?): Pair<String, List<Site>>? {
        val key = device.tunnelKey ?: return null
        val connection = runCatchingCancellable { gateway.admit(device, key, node) }.getOrElse { e ->
            logger.error { "could not admit the device '${device.name}' again: ${e.message}" }
            return null
        }
        send(
            "olm/sync",
            buildJsonObject {
                put("sites", WIRE.encodeToJsonElement(connection.sites))
                put("exitNodes", JsonArray(emptyList()))
            },
        )
        logger.info { "device '${device.name}' (${device.id}) keeps its tunnel" }
        return key to connection.sites
    }

    /** The direct path to a peer failed. Each peer has one public endpoint, so the relay is that endpoint. */
    private suspend fun DefaultWebSocketServerSession.relay(sites: List<Site>, data: JsonObject, chain: String?) {
        val wanted = (data["siteId"] as? JsonPrimitive)?.intOrNull
        val site = sites.firstOrNull { it.siteId == wanted } ?: sites.firstOrNull() ?: return
        send(
            "olm/wg/peer/relay",
            buildJsonObject {
                put("siteId", site.siteId)
                put("relayEndpoint", site.endpoint.substringBeforeLast(':'))
                put("relayPort", site.endpoint.substringAfterLast(':').toInt())
                chain?.let { put("chainId", it) }
            },
        )
    }

    /** What the app of a connection has on the peer of the homelab. Null until its tunnel starts. */
    private class LiveData(var sent: SiteData? = null, val lock: Mutex = Mutex())

    /**
     * Sends the app the ranges and names that changed since [live]. The removals go first, so a
     * name with a new address loses the old one before it gets the new one.
     */
    private suspend fun DefaultWebSocketServerSession.update(live: LiveData, device: Device, node: String?) {
        live.lock.withLock {
            val before = live.sent ?: return
            val now = gateway.siteData(device, node) ?: return
            val (removed, added) = now.changeFrom(before)
            if (!removed.isEmpty) send("olm/wg/peer/data/remove", peerData(removed))
            if (!added.isEmpty) send("olm/wg/peer/data/add", peerData(added))
            live.sent = now
            if (!removed.isEmpty || !added.isEmpty) {
                val names = "${added.aliases.size} name(s) added, ${removed.aliases.size} removed"
                logger.info { "device '${device.name}' (${device.id}) updated: $names" }
            }
        }
    }

    private fun peerData(data: SiteData): JsonObject = buildJsonObject {
        put("siteId", data.siteId)
        put("remoteSubnets", WIRE.encodeToJsonElement(data.remoteSubnets))
        put("aliases", WIRE.encodeToJsonElement(data.aliases))
    }

    private suspend fun DefaultWebSocketServerSession.send(type: String, data: JsonObject) {
        val message = buildJsonObject {
            put("type", type)
            put("data", data)
        }
        send(Frame.Text(message.toString()))
    }

    /** The app and its version from the start message of a tunnel, for example `Olm CLI 1.10.1`. */
    private fun agentOf(data: JsonObject): String? {
        val parts = listOfNotNull(data.text("olmAgent"), data.text("agentVersion") ?: data.text("olmVersion"))
        return parts.joinToString(" ").take(MAX_NAME).ifBlank { null }
    }

    private fun reason(code: String, message: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
    }

    private fun parse(text: String): JsonObject? {
        if (text.length > MAX_MESSAGE) return null
        return runCatching { Signed.JSON.decodeFromString<JsonObject>(text) }.getOrNull()
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TERMINATE = "olm/terminate"
        const val MAX_MESSAGE = 64 * 1024
        const val MAX_NAME = 80

        /** How long the server of the homelab keeps the key of a device with no control connection. */
        val GRACE = 2.minutes

        /** How long a server that is not the homelab keeps a control connection that starts no tunnel. */
        val IDLE = 15.seconds

        /** The header that the Traefik of a node sets: the id of the node. */
        const val NODE_HEADER = "X-HomeGAP-Node"

        /** A WireGuard public key: 32 bytes in base64. */
        val KEY = Regex("^[A-Za-z0-9+/]{43}=$")
    }
}
