package homegap.speedtest

import homegap.api.DeviceResultRequest
import homegap.api.NodeCpuDto
import homegap.api.SpeedTestResultDto
import homegap.config.AppConfig
import homegap.dashboard.MapFiles
import homegap.dashboard.WebAssets
import homegap.dashboard.respondAsset
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.status.PublicStatus
import homegap.status.StatusPage
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Short-lived bearer tokens for the public speed test endpoints (in memory only). */
class SpeedTestTokens {
    /** The test between the homelab and a node that runs now. Its token is one of these. */
    val live = SpeedTestLive()
    private val lock = SynchronizedObject()
    private val tokens = mutableMapOf<String, Grant>()
    private var limitedStreams = 0

    private class Grant(val expiresAt: Long, val limited: Boolean) {
        var streams = 0
        var bytes = 0L
        var results = 0
    }

    /**
     * A new token. A [limited] token is for a device link, which leaves the homelab. It has a cap
     * on streams, bytes and saved results. The token of a homelab-to-node test has no cap.
     */
    fun issue(ttlMillis: Long, limited: Boolean = true): Pair<String, Long> = synchronized(lock) {
        val now = nowMillis()
        tokens.entries.removeAll { it.value.expiresAt < now && it.value.streams == 0 }
        val token = Crypto.token(TOKEN_LENGTH)
        val expiresAt = now + ttlMillis
        tokens[token] = Grant(expiresAt, limited)
        token to expiresAt
    }

    fun valid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return synchronized(lock) { (tokens[token]?.expiresAt ?: 0) > nowMillis() }
    }

    fun revoke(token: String): Unit = synchronized(lock) { tokens.remove(token) }

    /** Takes a stream for [token]. False if the link or the listener has no stream left. */
    fun acquire(token: String?): Boolean = synchronized(lock) {
        val grant = tokens[token] ?: return@synchronized false
        if (grant.limited) {
            if (grant.streams >= STREAMS_PER_LINK || limitedStreams >= STREAMS_TOTAL) {
                return@synchronized false
            }
            limitedStreams++
        }
        grant.streams++
        true
    }

    fun release(token: String?): Unit = synchronized(lock) {
        val grant = tokens[token] ?: return@synchronized
        if (grant.streams > 0) {
            grant.streams--
            if (grant.limited) {
                limitedStreams--
            }
        }
    }

    /** Counts [n] bytes for [token]. False when the link has used its [budget]. */
    fun spend(token: String?, n: Int, budget: Long): Boolean = synchronized(lock) {
        val grant = tokens[token] ?: return@synchronized false
        grant.bytes += n
        !grant.limited || grant.bytes <= budget
    }

    /** Counts a saved result for [token]. False when the link has saved [RESULTS_PER_LINK]. */
    fun result(token: String?): Boolean = synchronized(lock) {
        val grant = tokens[token] ?: return@synchronized false
        !grant.limited || ++grant.results <= RESULTS_PER_LINK
    }

    companion object {
        const val STREAMS_PER_LINK = 8
        const val STREAMS_TOTAL = 32
        const val RESULTS_PER_LINK = 20
        private const val TOKEN_LENGTH = 32
    }
}

/** The recent results. The file is next to the state of the controller. */
class SpeedTestStore(
    private val path: String,
    private val keep: Int = 300,
) {
    private val lock = SynchronizedObject()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private var results: List<SpeedTestResultDto> =
        Files.readTextOrNull(path)
            ?.let { runCatching { json.decodeFromString<List<SpeedTestResultDto>>(it) }.getOrNull() }
            .orEmpty()

    fun add(result: SpeedTestResultDto) = synchronized(lock) {
        results = (listOf(result) + results).take(keep)
        runCatching {
            Files.writeAtomic(
                path = path,
                content = json.encodeToString(results),
                mode = "600".toInt(8),
            )
        }
    }

    fun recent(): List<SpeedTestResultDto> = synchronized(lock) { results }
}

private const val RESULT_ID_LENGTH = 12
private const val RESULT_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

fun newResultId(): String = Crypto.token(RESULT_ID_LENGTH, RESULT_ID_ALPHABET)

/**
 * The public origin: the speed test and the status page. Only the reverse proxy of the homelab
 * connects to it. The proxy routes the `speedtest*` and `status*` hostnames here, so every
 * measurement crosses node -> tunnel -> homelab.
 */
class SpeedTestServer(
    private val config: () -> AppConfig,
    private val tokens: SpeedTestTokens,
    private val store: SpeedTestStore,
    private val status: PublicStatus,
) {
    private val payload = Crypto.randomBytes(CHUNK_BYTES)

    fun start(host: String, port: Int) {
        // Reuse the address: after a restart, the connections of the old process stay in TIME_WAIT.
        embeddedServer(
            CIO,
            configure = {
                reuseAddress = true
                connector {
                    this.host = host
                    this.port = port
                }
            },
        ) {
            module()
        }.start(wait = false)
    }

    /** The id of the node that has the speed test hostname [host], or null for the auto hostname. */
    fun nodeFor(host: String): String? {
        val cfg = config()
        return cfg.nodes
            .firstOrNull {
                cfg.speedtest
                    .nodeHost(cfg.domain, it.id)
                    .equals(host, ignoreCase = true)
            }?.id
    }

    /**
     * Whether [origin] is a page of the speed test: the shared name or the name of a node. Such a
     * page can call the endpoints of every node, because "Test all nodes" measures each node from
     * one page.
     */
    fun speedTestOrigin(origin: String): Boolean {
        val cfg = config()
        val nodeHosts = cfg.nodes.map { cfg.speedtest.nodeHost(cfg.domain, it.id) }
        val hosts = listOf(cfg.speedtest.autoHost(cfg.domain)) + nodeHosts
        return hosts.any { origin.equals("https://$it", ignoreCase = true) }
    }

    /** Whether [host] is a name of the speed test page: the shared name or the name of a node. */
    fun serves(host: String): Boolean {
        val cfg = config()
        return cfg.speedtest.autoHost(cfg.domain).equals(host, ignoreCase = true) || nodeFor(host) != null
    }

    private val publicPages = RateLimiter(PUBLIC_REQUESTS_PER_MINUTE, MINUTE_MILLIS)

    fun Application.module() {
        // The status page, the map and the fonts need no token. A speed test has its own limits.
        install(createApplicationPlugin("PublicPageLimit") {
            onCall { call ->
                if (call.request.path().startsWith("/speedtest/")) return@onCall
                // The reverse proxy of the homelab puts the address of the client here.
                val client = call.request.header(HttpHeaders.XForwardedFor)
                    ?.substringBefore(',')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: call.request.local.remoteHost
                if (!publicPages.allow(client)) {
                    call.respond(HttpStatusCode.TooManyRequests, "too many requests")
                }
            }
        })
        install(createApplicationPlugin("SpeedTestCors") {
            onCall { call ->
                val origin = call.request.header(HttpHeaders.Origin) ?: return@onCall
                if (!call.request.path().startsWith("/speedtest/") || !speedTestOrigin(origin)) {
                    return@onCall
                }
                call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
                call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
            }
        })
        install(ContentNegotiation) {
            json(Json {
                explicitNulls = false
                ignoreUnknownKeys = true
            })
        }
        install(Authentication) {
            // The test page sends its link token, which lives a short time, in a header.
            provider(LINK_AUTH) {
                authenticate { context ->
                    if (tokens.valid(context.call.request.header(TOKEN_HEADER))) {
                        context.principal(UserIdPrincipal("speedtest-link"))
                    } else {
                        context.challenge(
                            key = LINK_AUTH,
                            cause = AuthenticationFailedCause.InvalidCredentials,
                        ) { challenge, call ->
                            call.respond(
                                status = HttpStatusCode.Unauthorized,
                                message = "speed test link expired or invalid",
                            )
                            challenge.complete()
                        }
                    }
                }
            }
        }
        routing {
            get("/") { home() }
            get("/index.html") { home() }
            // The map of the status page: only the tiles and the scripts, fonts and icons of the map.
            // Without the status page, nothing public uses the map.
            get(MapFiles.TILES_PATH) {
                if (!config().statusPage.enabled) return@get call.respond(HttpStatusCode.NotFound)
                with(MapFiles) { serveTiles(config().map.tiles) }
            }
            get("/map/{path...}") {
                if (!config().statusPage.enabled) return@get call.respond(HttpStatusCode.NotFound)
                with(MapFiles) { serveMapAsset() }
            }
            // The fonts of the pages. The dashboard has the same files.
            get("/fonts/{file}") {
                val asset = WebAssets.files["/fonts/${call.parameters["file"]}"]
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(HttpHeaders.CacheControl, "public, max-age=604800")
                respondAsset(asset)
            }
            route("/status") {
                install(NoStore)
                get("/nodes") { statusNodes() }
                get("/ping") { statusPing() }
            }
            route("/speedtest") {
                install(NoStore)
                // The CORS preflight of the browser for the endpoints of a different node. It has no token.
                options("{path...}") {
                    if (!speedTestOrigin(call.request.header(HttpHeaders.Origin).orEmpty())) {
                        return@options call.respond(HttpStatusCode.Forbidden)
                    }
                    call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT")
                    call.response.header(HttpHeaders.AccessControlAllowHeaders, "$TOKEN_HEADER, Content-Type")
                    call.response.header(HttpHeaders.AccessControlMaxAge, "600")
                    call.respond(HttpStatusCode.NoContent)
                }
                authenticate(LINK_AUTH) { measurementRoutes() }
            }
        }
    }

    private fun Route.measurementRoutes() {
        get("/ping") {
            tokens.live.ping(call.request.header(TOKEN_HEADER))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/info") { info() }
        get("/down") { download() }
        post("/up") { receiveUpload() }
        put("/up") { receiveUpload() }
        post("/result") { saveResult() }
    }

    /** The node that serves this page, the length of a phase and the test URL of each node. */
    private suspend fun RoutingContext.info() {
        val cfg = config()
        val info = buildJsonObject {
            put("node", nodeFor(call.request.host()))
            put("phaseSeconds", cfg.speedtest.phaseSeconds)
            putJsonObject("nodes") {
                cfg.nodes.forEach {
                    put(it.id, "https://${cfg.speedtest.nodeHost(cfg.domain, it.id)}/")
                }
            }
        }
        call.respond(info)
    }

    /** Streams random bytes for `?seconds=` (default `speedtest.phase_seconds`, at most 30). */
    private suspend fun RoutingContext.download() {
        val requested = call.request.queryParameters["seconds"]?.toIntOrNull()
        val seconds = (requested ?: config().speedtest.phaseSeconds).coerceIn(1, MAX_DOWNLOAD_SECONDS)
        val end = nowMillis() + seconds * 1000L
        val token = call.request.header(TOKEN_HEADER)
        val budget = config().speedtest.linkBudgetBytes
        if (!tokens.acquire(token)) return call.respond(HttpStatusCode.TooManyRequests, "too many streams")
        try {
            call.respondBytesWriter(ContentType.Application.OctetStream) {
                while (nowMillis() < end && tokens.spend(token, payload.size, budget)) {
                    writeFully(payload, 0, payload.size)
                    flush()
                    tokens.live.bytes(token, upload = false, n = payload.size)
                }
            }
        } finally {
            tokens.release(token)
        }
    }

    /** Saves the result of a device test for the node that has the hostname of the request. */
    private suspend fun RoutingContext.saveResult() {
        val length = call.request.contentLength()
        if (length == null || length > MAX_RESULT_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        if (!tokens.result(call.request.header(TOKEN_HEADER))) {
            call.respond(HttpStatusCode.TooManyRequests, "too many results for this link")
            return
        }
        val request = call.receive<DeviceResultRequest>()
        store.add(
            SpeedTestResultDto(
                id = newResultId(),
                at = nowMillis(),
                kind = "device",
                nodeId = nodeFor(call.request.host()),
                auto = request.auto,
                pingMs = request.pingMs,
                jitterMs = request.jitterMs,
                downMbps = request.downMbps,
                upMbps = request.upMbps,
                pingSamples = request.pingSamples,
                error = request.error?.take(MAX_ERROR_LENGTH),
                client = call.request.header(HttpHeaders.UserAgent)?.take(MAX_CLIENT_LENGTH),
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }

    /** The page of the name in the request. A name of a node that left gets no page. */
    private suspend fun RoutingContext.home() {
        val host = call.request.host()
        when {
            status.serves(host) -> statusPage()
            serves(host) -> page()
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun RoutingContext.page() {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", SPEED_TEST_PAGE_POLICY)
        call.respondText(SpeedTestPage.HTML, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.statusPage() {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Content-Security-Policy", STATUS_PAGE_POLICY)
        call.respondText(StatusPage.HTML, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.statusNodes() {
        val host = call.request.host()
        if (!status.serves(host)) {
            return call.respond(HttpStatusCode.NotFound)
        }
        call.respond(status.build(host))
    }

    /** One round trip. The browser of the visitor times it from the status page of a different node (CORS). */
    private suspend fun RoutingContext.statusPing() {
        if (!status.serves(call.request.host())) {
            return call.respond(HttpStatusCode.NotFound)
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.response.header("Timing-Allow-Origin", "*")
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun RoutingContext.receiveUpload() {
        val channel = call.receiveChannel()
        val buffer = ByteArray(CHUNK_BYTES)
        val start = nowMillis()
        var total = 0L
        val token = call.request.header(TOKEN_HEADER)
        val budget = config().speedtest.linkBudgetBytes
        if (!tokens.acquire(token)) return call.respond(HttpStatusCode.TooManyRequests, "too many streams")
        try {
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                total += count
                tokens.live.bytes(token, upload = true, n = count)
                if (!tokens.spend(token, count, budget) || nowMillis() - start > MAX_UPLOAD_MILLIS) break
            }
        } finally {
            tokens.release(token)
        }
        val elapsedMillis = (nowMillis() - start).coerceAtLeast(1)
        val summary = buildJsonObject {
            put("bytes", total)
            put("ms", elapsedMillis)
        }
        call.respond(summary)
    }

    companion object {
        const val TOKEN_HEADER = "X-HomeGAP-Token"
        private const val MAX_RESULT_BYTES = 16 * 1024L
        private const val LINK_AUTH = "speedtest-link"
        private const val CHUNK_BYTES = 256 * 1024
        private const val MINUTE_MILLIS = 60_000L
        private const val MAX_DOWNLOAD_SECONDS = 30
        private const val MAX_UPLOAD_MILLIS = 120_000
        private const val MAX_ERROR_LENGTH = 300
        private const val MAX_CLIENT_LENGTH = 160

        /** One address gets this many requests in a minute for the pages without a token. */
        const val PUBLIC_REQUESTS_PER_MINUTE = 1200

        /** The page measures against the name of each node, so it connects to other hosts. */
        private val SPEED_TEST_PAGE_POLICY = listOf(
            "default-src 'none'",
            "script-src 'unsafe-inline'",
            "style-src 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self' http: https:",
            "base-uri 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")

        /** The page loads only its own files. The scripts, worker, fonts and tiles of the map come from /map/. */
        private val STATUS_PAGE_POLICY = listOf(
            "default-src 'none'",
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self'",
            "worker-src 'self' blob:",
            "child-src blob:",
            "connect-src 'self' https:",
            "base-uri 'none'",
            "frame-ancestors 'none'",
        ).joinToString("; ")

        /** A cache must never keep a measurement. */
        private val NoStore = createRouteScopedPlugin("NoStore") {
            onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
        }
    }
}

/**
 * The measurement between the homelab and a node. The script runs on the node over SSH. curl
 * connects to port 443 of the node with the speed test hostname of the node. Thus the traffic takes
 * the production path (node Traefik -> WireGuard tunnel -> homelab proxy -> speed test origin).
 */
object TunnelSpeedTest {
    const val PINGS = 20
    const val MAX_STREAMS = 16

    /**
     * Ping, then [streams] parallel downloads, then [streams] parallel uploads. Each stream is
     * its own TCP connection with its own congestion window, and all streams share the one tunnel.
     * Real traffic from several viewers or browser connections does the same. The result is the
     * sum of the rates.
     *
     * The script reads the CPU counters of the node before and after each phase, so the result
     * shows if the CPU of the node is the limit. curl on the node is a part of that load.
     */
    fun script(host: String, token: String, seconds: Int, streams: Int = 1): String = $$"""
        |H=$${sh(host)}
        |C=(curl -sk --resolve "$H:443:127.0.0.1" -H $${sh("${SpeedTestServer.TOKEN_HEADER}: $token")} -H 'Expect:' --max-time 90)
        |T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
        |cpu() { grep '^cpu' /proc/stat | sed "s/^/S $1 /"; }
        |URLS=()
        |for i in $(seq 0 $$PINGS); do URLS+=("https://$H/speedtest/ping?i=$i"); done
        |"${C[@]}" -w 'P %{time_pretransfer} %{time_starttransfer} %{http_code}\n' "${URLS[@]}" || true
        |cpu d0
        |for s in $(seq 1 $$streams); do
        |  "${C[@]}" -o /dev/null -w 'D %{size_download} %{time_starttransfer} %{time_total} %{http_code}\n' "https://$H/speedtest/down?seconds=$$seconds" > "$T/d$s" 2>/dev/null &
        |done
        |wait; cpu d1; for s in $(seq 1 $$streams); do cat "$T/d$s" 2>/dev/null || echo 'D 0 0 0 000'; done
        |cpu u0
        |for s in $(seq 1 $$streams); do
        |  { { timeout $$seconds cat /dev/zero || true; } | "${C[@]}" -T - -w '\nU %{http_code}\n' "https://$H/speedtest/up" > "$T/u$s" 2>/dev/null; } &
        |done
        |wait; cpu u1; for s in $(seq 1 $$streams); do cat "$T/u$s" 2>/dev/null; echo; done
    """.trimMargin()

    private fun sh(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    data class Parsed(
        val pingMs: Double?,
        val jitterMs: Double?,
        val samples: Int,
        val downMbps: Double?,
        val upMbps: Double?,
        val error: String?,
        val downStreams: List<Double> = emptyList(),
        val upStreams: List<Double> = emptyList(),
        val downCpu: NodeCpuDto? = null,
        val upCpu: NodeCpuDto? = null,
    )

    fun parse(output: String, host: String, streams: Int = 1): Parsed {
        val errors = mutableListOf<String>()
        val pings = mutableListOf<Double>()
        var pingCode: String? = null
        val downs = mutableListOf<Double>()
        val uploadJson = mutableListOf<String>()
        val stat = mutableMapOf<String, StringBuilder>()
        for (line in output.lines()) {
            val fields = line.trim().split(' ')
            when (fields.firstOrNull()) {
                "P" -> if (fields.size == 4) {
                    if (fields[3] !in setOf("200", "204")) {
                        pingCode = fields[3]
                    } else {
                        pings += (fields[2].toDouble() - fields[1].toDouble()) * 1000.0
                    }
                }

                "D" -> if (fields.size == 5) {
                    if (fields[4] != "200") {
                        errors += codeError("download", fields[4], host)
                    } else {
                        val seconds = fields[3].toDouble() - fields[2].toDouble()
                        if (seconds > 0) {
                            downs += fields[1].toDouble() * 8 / seconds / 1e6
                        }
                    }
                }

                // A line of /proc/stat, with the point of the test that it is from.
                "S" -> if (fields.size > 2) {
                    stat.getOrPut(fields[1]) { StringBuilder() }.appendLine(fields.drop(2).joinToString(" "))
                }

                "U" -> {
                    if (fields.size == 2 && fields[1] != "200") {
                        errors += codeError("upload", fields[1], host)
                    }
                }

                else -> {
                    if (line.trim().startsWith("{")) {
                        uploadJson += line.trim()
                    }
                }
            }
        }
        pingCode?.let { errors += codeError("ping", it, host) }
        val ups = uploadJson.mapNotNull { text ->
            runCatching {
                val summary = Json.parseToJsonElement(text).jsonObject
                val bytes = summary["bytes"]!!.jsonPrimitive.long
                val millis = summary["ms"]!!.jsonPrimitive.long
                if (millis > 0) bytes * 8.0 / (millis / 1000.0) / 1e6 else null
            }.getOrNull()
        }
        if (streams > 1) {
            if (downs.isNotEmpty() && downs.size < streams) {
                errors += "download: only ${downs.size} of $streams streams completed"
            }
            if (ups.isNotEmpty() && ups.size < streams) {
                errors += "upload: only ${ups.size} of $streams streams completed"
            }
        }
        val samples = pings.drop(1)
        val (avg, jitter) = stats(samples)
        return Parsed(
            pingMs = avg,
            jitterMs = jitter,
            samples = samples.size,
            downMbps = downs.takeIf { it.isNotEmpty() }?.sum(),
            upMbps = ups.takeIf { it.isNotEmpty() }?.sum(),
            error = errors.distinct().joinToString("; ").ifEmpty { null },
            downStreams = downs,
            upStreams = ups,
            downCpu = cpuBetween(stat["d0"], stat["d1"]),
            upCpu = cpuBetween(stat["u0"], stat["u1"]),
        )
    }

    private fun codeError(phase: String, code: String, host: String): String = when (code) {
        "000" -> "$phase: no connection through the node to $host"
        "401" -> "$phase: speed test origin rejected the token"
        "404" -> "$phase: the homelab proxy has no route for $host to the speed test port"
        "502", "503", "504" -> "$phase: node could not reach the homelab (tunnel down?) [$code]"
        else -> "$phase: HTTP $code"
    }

    private fun cpuBetween(before: CharSequence?, after: CharSequence?): NodeCpuDto? {
        val then = before?.let { CpuCounters.parse(it.toString()) } ?: return null
        return after?.let { CpuCounters.parse(it.toString()) }?.since(then)
    }

    /** The mean round trip and the jitter (the mean absolute difference of two samples in sequence). */
    fun stats(samples: List<Double>): Pair<Double?, Double?> {
        if (samples.isEmpty()) return null to null
        val avg = samples.average()
        val jitter = if (samples.size < 2) {
            0.0
        } else {
            samples
                .zipWithNext { previous, next -> abs(next - previous) }
                .average()
        }
        return avg to jitter
    }
}
