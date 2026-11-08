package homegap.web

import homegap.api.*
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.json

object Api {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Optional bearer token (when the controller sets dashboard.auth_token_*), kept in localStorage. */
    var token: String?
        get() = localStorage.getItem("homegap.token")
        set(v) = if (v.isNullOrBlank()) localStorage.removeItem("homegap.token") else localStorage.setItem("homegap.token", v)

    private fun headers(body: Boolean) = json().apply {
        this["Accept"] = "application/json"
        if (body) this["Content-Type"] = "application/json"
        token?.let { this["Authorization"] = "Bearer $it" }
    }

    class ApiError(val status: Int, message: String) : Exception(message)

    private suspend fun request(method: String, path: String, body: String? = null): String {
        val init = RequestInit(method = method, headers = headers(body != null), body = body)
        val resp: Response = window.fetch("/api$path", init).await()
        val text = resp.text().await()
        if (!resp.ok) {
            val msg = runCatching { json.decodeFromString(ActionResponse.serializer(), text).message }.getOrNull() ?: "HTTP ${resp.status}"
            throw ApiError(resp.status.toInt(), msg)
        }
        return text
    }

    /**
     * Reads the controller's event stream (`/api/events`) until it ends, calling [onEvent] with
     * each event name and data. Uses fetch rather than EventSource so the bearer token can be sent.
     */
    suspend fun events(afterSeq: Long, onEvent: (String, String) -> Unit) {
        val init = RequestInit(method = "GET", headers = headers(false).apply { this["Accept"] = "text/event-stream" })
        val resp: Response = window.fetch("/api/events?after=$afterSeq", init).await()
        if (!resp.ok) throw ApiError(resp.status.toInt(), "HTTP ${resp.status}")
        val reader = resp.asDynamic().body.getReader()
        val decoder = js("new TextDecoder()")
        val opts = js("({stream: true})")
        var buf = ""
        while (true) {
            val chunk = (reader.read() as kotlin.js.Promise<dynamic>).await()
            if (chunk.done == true) return
            buf += decoder.decode(chunk.value, opts) as String
            while (true) {
                val end = buf.indexOf("\n\n")
                if (end < 0) break
                val block = buf.substring(0, end)
                buf = buf.substring(end + 2)
                var event = "message"
                val data = StringBuilder()
                for (line in block.split('\n')) {
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
                    }
                }
                if (data.isNotEmpty()) onEvent(event, data.toString())
            }
        }
    }

    suspend fun status(): StatusSnapshot = json.decodeFromString(StatusSnapshot.serializer(), request("GET", "/status"))
    suspend fun log(domain: String?): LogResponse =
        json.decodeFromString(LogResponse.serializer(), request("GET", "/log?limit=500" + (domain?.let { "&domain=$it" } ?: "")))
    suspend fun diff(): DiffResponse = json.decodeFromString(DiffResponse.serializer(), request("GET", "/diff"))
    suspend fun secrets(): SecretsResponse = json.decodeFromString(SecretsResponse.serializer(), request("GET", "/secrets"))
    suspend fun config(): ConfigResponse = json.decodeFromString(ConfigResponse.serializer(), request("GET", "/config"))

    private suspend fun action(method: String, path: String, body: String? = null): ActionResponse =
        json.decodeFromString(ActionResponse.serializer(), request(method, path, body))

    suspend fun speedtest(): SpeedTestOverview = json.decodeFromString(SpeedTestOverview.serializer(), request("GET", "/speedtest"))
    suspend fun speedLink(): SpeedTestLinkDto = json.decodeFromString(SpeedTestLinkDto.serializer(), request("POST", "/speedtest/link"))
    suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto =
        json.decodeFromString(SpeedTestResultDto.serializer(), request("POST", "/speedtest/nodes/$node?streams=$streams"))

    suspend fun resync() = action("POST", "/resync")
    suspend fun provision(node: String) = action("POST", "/nodes/$node/provision")
    suspend fun retryUpdate(node: String) = action("POST", "/nodes/$node/update/retry")
    suspend fun skipUpdate(node: String) = action("POST", "/nodes/$node/update/skip")
    suspend fun pause(scope: String, id: String?, paused: Boolean) =
        action("POST", "/pause", json.encodeToString(PauseRequest.serializer(), PauseRequest(scope, id, paused)))
    suspend fun addNode(n: NodeConfigDto) = action("POST", "/nodes", json.encodeToString(NodeConfigDto.serializer(), n))
    suspend fun removeNode(id: String) = action("DELETE", "/nodes/$id")
    suspend fun decommission(id: String, confirm: String) = action("POST", "/nodes/$id/decommission?confirm=$confirm")
}
