package homegap.signin

import homegap.identity.Signed
import homegap.platform.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

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

    /** Adds the WebSocket to [parent], which is the route `/api/v1`. */
    fun init(parent: Route) {
        parent.webSocket("/ws") { serve() }
    }

    private suspend fun DefaultWebSocketServerSession.serve() {
        val token = call.request.queryParameters["token"].orEmpty()
        val device = signIn.tunnelClaims(token)?.let { devices.get(it.device) }
        if (device == null) {
            send(TERMINATE, reason("UNAUTHORIZED", "Sign in again."))
            return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        logger.info { "device '${device.name}' (${device.id}) connected" }
        var sites: List<Site> = emptyList()
        try {
            for (frame in incoming) {
                val message = (frame as? Frame.Text)?.let { parse(it.readText()) } ?: continue
                val data = message["data"] as? JsonObject ?: JsonObject(emptyMap())
                val chain = data.text("chainId")
                // A block of the user, or the end of the token, ends the connection at the next message.
                if (signIn.tunnelClaims(token) == null) {
                    runCatchingCancellable { gateway.remove(device) }
                    send(TERMINATE, reason("UNAUTHORIZED", "Your access ended. Sign in again."))
                    return close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                }
                when (message.text("type")) {
                    "olm/ping/request" -> send(
                        "olm/ping/exitNodes",
                        buildJsonObject {
                            put("exitNodes", Signed.JSON.encodeToJsonElement(gateway.exitNodes()))
                            chain?.let { put("chainId", it) }
                        },
                    )
                    // The app sends this form first, for old servers. The real one comes after the list of nodes.
                    "olm/wg/register" -> if ((data["backwardsCompatible"] as? JsonPrimitive)?.booleanOrNull != true) {
                        sites = register(device, data, chain) ?: sites
                    }
                    "olm/wg/relay" -> relay(sites, data, chain)
                    "olm/ping" -> send("pong", JsonObject(emptyMap()))
                    else -> Unit
                }
            }
        } finally {
            logger.info { "device '${device.name}' (${device.id}) disconnected" }
        }
    }

    /** Admits the key of the device and sends it its peers. Returns the peers, or null after a failure. */
    private suspend fun DefaultWebSocketServerSession.register(
        device: Device,
        data: JsonObject,
        chain: String?,
    ): List<Site>? {
        val key = data.text("publicKey")?.takeIf { KEY.matches(it) }
        if (key == null) {
            send("olm/error", reason("INVALID_REQUEST", "The device sent no valid key."))
            return null
        }
        val connection = runCatchingCancellable { gateway.admit(device, key) }.getOrElse { e ->
            logger.error { "could not admit the device '${device.name}': ${e.message}" }
            send("olm/error", reason("SERVER_ERROR", "HomeGAP could not start the tunnel. Try again."))
            return null
        }
        send(
            "olm/wg/connect",
            buildJsonObject {
                put("sites", Signed.JSON.encodeToJsonElement(connection.sites))
                put("tunnelIP", "${device.address}/${devices.prefix}")
                put("utilitySubnet", utilitySubnet)
                connection.dns?.let { put("dnsConfig", Signed.JSON.encodeToJsonElement(it)) }
                chain?.let { put("chainId", it) }
            },
        )
        return connection.sites
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

    private suspend fun DefaultWebSocketServerSession.send(type: String, data: JsonObject) {
        val message = buildJsonObject {
            put("type", type)
            put("data", data)
        }
        send(Frame.Text(message.toString()))
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

        /** A WireGuard public key: 32 bytes in base64. */
        val KEY = Regex("^[A-Za-z0-9+/]{43}=$")
    }
}
