package homegap.web.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.*
import homegap.presentation.data.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise
import kotlin.js.json

/** [ControllerApi] over the browser's fetch, against the page's own origin (bound by Metro). */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class FetchControllerApi(private val tokens: TokenStore) : ControllerApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun status(): StatusSnapshot = get("/status")
    override suspend fun diff(): DiffResponse = get("/diff")
    override suspend fun secrets(): SecretsResponse = get("/secrets")
    override suspend fun config(): ConfigResponse = get("/config")
    override suspend fun speedtest(): SpeedTestOverview = get("/speedtest")

    /** Uses fetch rather than EventSource so the bearer token can be sent. */
    override suspend fun events(afterSeq: Long, onEvent: (ServerEvent) -> Unit) {
        val response = fetch("GET", "/events?after=$afterSeq", accept = "text/event-stream")
        if (!response.ok) throw ApiException(response.status.toInt(), "HTTP ${response.status}")
        val reader = response.asDynamic().body.getReader()
        val decoder = js("new TextDecoder()")
        val streaming = js("({stream: true})")
        val parser = EventStreamParser()
        while (true) {
            val chunk = (reader.read() as Promise<dynamic>).await()
            if (chunk.done == true) return
            parser.feed(decoder.decode(chunk.value, streaming) as String).forEach(onEvent)
        }
    }

    override suspend fun resync(): ActionResponse = post("/resync")
    override suspend fun provision(node: String): ActionResponse = post("/nodes/$node/provision")
    override suspend fun retryUpdate(node: String): ActionResponse = post("/nodes/$node/update/retry")
    override suspend fun skipUpdate(node: String): ActionResponse = post("/nodes/$node/update/skip")

    override suspend fun pause(scope: PauseScope, id: String?, paused: Boolean): ActionResponse =
        post("/pause", PauseRequest(scope.wire, id, paused))

    override suspend fun addNode(node: NodeConfigDto): ActionResponse = post("/nodes", node)
    override suspend fun removeNode(id: String): ActionResponse = send("DELETE", "/nodes/$id")

    override suspend fun decommission(id: String, confirm: String): ActionResponse =
        post("/nodes/$id/decommission?confirm=$confirm")

    override suspend fun speedLink(): SpeedTestLinkDto = post("/speedtest/link")

    override suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto =
        post("/speedtest/nodes/$node?streams=$streams")

    override suspend fun testConnection(login: SshLoginDto): ConnectionTestDto = post("/setup/test", login)
    override suspend fun createKey(request: SshKeyRequestDto): SshKeyDto = post("/setup/keys", request)
    override suspend fun startSetup(request: NodeSetupRequestDto): SetupJobDto = post("/setup/jobs", request)
    override suspend fun setupJob(id: String): SetupJobDto = get("/setup/jobs/$id")

    private suspend inline fun <reified T> get(path: String): T = send("GET", path)

    private suspend inline fun <reified T> post(path: String): T = send("POST", path)

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        json.decodeFromString(serializer<T>(), request("POST", path, json.encodeToString(serializer<B>(), body)))

    private suspend inline fun <reified T> send(method: String, path: String): T =
        json.decodeFromString(serializer<T>(), request(method, path))

    private suspend fun request(method: String, path: String, body: String? = null): String {
        val response = fetch(method, path, body = body)
        val text = response.text().await()
        if (!response.ok) {
            val message = runCatching { json.decodeFromString(ActionResponse.serializer(), text).message }.getOrNull()
            throw ApiException(response.status.toInt(), message ?: "HTTP ${response.status}")
        }
        return text
    }

    private suspend fun fetch(
        method: String,
        path: String,
        body: String? = null,
        accept: String = "application/json",
    ): Response {
        val headers = json("Accept" to accept).apply {
            if (body != null) this["Content-Type"] = "application/json"
            tokens.token?.let { this["Authorization"] = "Bearer $it" }
        }
        return window.fetch("/api$path", RequestInit(method = method, headers = headers, body = body)).await()
    }
}
