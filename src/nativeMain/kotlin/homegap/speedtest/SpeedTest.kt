package homegap.speedtest

import homegap.api.DeviceResultRequest
import homegap.api.SpeedTestResultDto
import homegap.config.AppConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Short-lived bearer tokens for the public speed test endpoints (in memory only). */
class SpeedTestTokens {
    private val lock = SynchronizedObject()
    private val tokens = mutableMapOf<String, Long>()

    fun issue(ttlMillis: Long): Pair<String, Long> = synchronized(lock) {
        val now = nowMillis()
        tokens.entries.removeAll { it.value < now }
        val t = Crypto.token(32)
        val exp = now + ttlMillis
        tokens[t] = exp
        t to exp
    }

    fun valid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return synchronized(lock) { (tokens[token] ?: 0) > nowMillis() }
    }

    fun revoke(token: String) = synchronized(lock) { tokens.remove(token) }
}

/** Recent results, persisted next to the controller state. */
class SpeedTestStore(private val path: String, private val keep: Int = 300) {
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(SpeedTestResultDto.serializer())
    private var results: List<SpeedTestResultDto> =
        Files.readTextOrNull(path)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun add(r: SpeedTestResultDto) = synchronized(lock) {
        results = (listOf(r) + results).take(keep)
        runCatching { Files.writeAtomic(path, json.encodeToString(serializer, results), "600".toInt(8)) }
    }

    fun recent(): List<SpeedTestResultDto> = synchronized(lock) { results }
}

fun newResultId(): String = Crypto.token(12, "abcdefghijklmnopqrstuvwxyz0123456789")

/**
 * The speed test origin. Reached only through the homelab reverse proxy (which routes the
 * `speedtest*` hostnames here), so every measurement crosses node -> tunnel -> homelab.
 */
class SpeedTestServer(
    private val config: () -> AppConfig,
    private val tokens: SpeedTestTokens,
    private val store: SpeedTestStore,
) {
    private val payload = Crypto.randomBytes(256 * 1024)

    fun start(host: String, port: Int) {
        embeddedServer(CIO, port = port, host = host) { module() }.start(wait = false)
    }

    /** Node id for a per-node speed test hostname, or null for the auto hostname. */
    fun nodeFor(host: String): String? {
        val cfg = config()
        return cfg.nodes.firstOrNull { cfg.speedtest.nodeHost(cfg.domain, it.id).equals(host, ignoreCase = true) }?.id
    }

    fun Application.module() {
        install(ContentNegotiation) { json(Json { explicitNulls = false; ignoreUnknownKeys = true }) }
        routing {
            get("/") { page() }
            get("/index.html") { page() }
            route("/speedtest") {
                intercept(ApplicationCallPipeline.Plugins) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (!tokens.valid(call.request.header(TOKEN_HEADER))) {
                        call.respond(HttpStatusCode.Unauthorized, "speed test link expired or invalid")
                        finish()
                    }
                }
                get("/ping") { call.respond(HttpStatusCode.NoContent) }
                get("/info") {
                    val cfg = config()
                    call.respond(buildJsonObject {
                        put("node", nodeFor(call.request.host()))
                        put("phaseSeconds", cfg.speedtest.phaseSeconds)
                        putJsonObject("nodes") {
                            cfg.nodes.forEach { put(it.id, "https://" + cfg.speedtest.nodeHost(cfg.domain, it.id) + "/") }
                        }
                    })
                }
                get("/down") {
                    val seconds = (call.request.queryParameters["seconds"]?.toIntOrNull() ?: config().speedtest.phaseSeconds).coerceIn(1, 30)
                    val end = nowMillis() + seconds * 1000L
                    call.respondBytesWriter(ContentType.Application.OctetStream) {
                        while (nowMillis() < end) {
                            writeFully(payload, 0, payload.size)
                            flush()
                        }
                    }
                }
                post("/up") { receiveUpload() }
                put("/up") { receiveUpload() }
                post("/result") {
                    val req = call.receive<DeviceResultRequest>()
                    val r = SpeedTestResultDto(
                        id = newResultId(), at = nowMillis(), kind = "device", nodeId = nodeFor(call.request.host()),
                        auto = req.auto, pingMs = req.pingMs, jitterMs = req.jitterMs, downMbps = req.downMbps, upMbps = req.upMbps,
                        pingSamples = req.pingSamples, error = req.error?.take(300),
                        client = call.request.header(HttpHeaders.UserAgent)?.take(160),
                    )
                    store.add(r)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private suspend fun RoutingContext.page() {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondText(SpeedTestPage.HTML, ContentType.Text.Html)
    }

    private suspend fun RoutingContext.receiveUpload() {
        val ch = call.receiveChannel()
        val buf = ByteArray(256 * 1024)
        val start = nowMillis()
        var total = 0L
        while (true) {
            val n = ch.readAvailable(buf, 0, buf.size)
            if (n < 0) break
            total += n
            if (nowMillis() - start > 120_000) break
        }
        val ms = (nowMillis() - start).coerceAtLeast(1)
        call.respond(buildJsonObject { put("bytes", total); put("ms", ms) })
    }

    companion object {
        const val TOKEN_HEADER = "X-HomeGAP-Token"
    }
}

/**
 * Homelab <-> node measurement, executed on the node over SSH: curl talks to the node's own
 * port 443 with the per-node speed test hostname, so traffic takes the exact production path
 * (node Traefik -> WireGuard tunnel -> homelab proxy -> speed test origin).
 */
object TunnelSpeedTest {
    const val PINGS = 20
    const val MAX_STREAMS = 16

    /**
     * Ping, then [streams] parallel downloads, then [streams] parallel uploads. Each stream is
     * its own TCP connection (and so its own congestion window) sharing the one tunnel, like
     * real traffic from several viewers or browser connections. Rates are summed.
     */
    fun script(host: String, token: String, seconds: Int, streams: Int = 1): String = """
        |H=${sh(host)}
        |C=(curl -sk --resolve "${'$'}H:443:127.0.0.1" -H ${sh("${SpeedTestServer.TOKEN_HEADER}: $token")} -H 'Expect:' --max-time 90)
        |T=${'$'}(mktemp -d); trap 'rm -rf "${'$'}T"' EXIT
        |URLS=()
        |for i in $(seq 0 $PINGS); do URLS+=("https://${'$'}H/speedtest/ping?i=${'$'}i"); done
        |"${'$'}{C[@]}" -w 'P %{time_pretransfer} %{time_starttransfer} %{http_code}\n' "${'$'}{URLS[@]}" || true
        |for s in $(seq 1 $streams); do
        |  "${'$'}{C[@]}" -o /dev/null -w 'D %{size_download} %{time_starttransfer} %{time_total} %{http_code}\n' "https://${'$'}H/speedtest/down?seconds=$seconds" > "${'$'}T/d${'$'}s" 2>/dev/null &
        |done
        |wait; for s in $(seq 1 $streams); do cat "${'$'}T/d${'$'}s" 2>/dev/null || echo 'D 0 0 0 000'; done
        |for s in $(seq 1 $streams); do
        |  { { timeout $seconds cat /dev/zero || true; } | "${'$'}{C[@]}" -T - -w '\nU %{http_code}\n' "https://${'$'}H/speedtest/up" > "${'$'}T/u${'$'}s" 2>/dev/null; } &
        |done
        |wait; for s in $(seq 1 $streams); do cat "${'$'}T/u${'$'}s" 2>/dev/null; echo; done
    """.trimMargin()

    private fun sh(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"

    data class Parsed(
        val pingMs: Double?, val jitterMs: Double?, val samples: Int, val downMbps: Double?, val upMbps: Double?, val error: String?,
        val downStreams: List<Double> = emptyList(), val upStreams: List<Double> = emptyList(),
    )

    fun parse(out: String, host: String, streams: Int = 1): Parsed {
        val errors = mutableListOf<String>()
        fun codeError(phase: String, code: String) {
            errors += when (code) {
                "000" -> "$phase: no connection through the node to $host"
                "401" -> "$phase: speed test origin rejected the token"
                "404" -> "$phase: the homelab proxy has no route for $host to the speed test port"
                "502", "503", "504" -> "$phase: node could not reach the homelab (tunnel down?) [$code]"
                else -> "$phase: HTTP $code"
            }
        }
        val pings = mutableListOf<Double>()
        var pingCode: String? = null
        val downs = mutableListOf<Double>()
        val uploadJson = mutableListOf<String>()
        for (l in out.lines()) {
            val p = l.trim().split(' ')
            when (p.firstOrNull()) {
                "P" -> if (p.size == 4) {
                    if (p[3] !in setOf("200", "204")) pingCode = p[3]
                    else pings += (p[2].toDouble() - p[1].toDouble()) * 1000.0
                }
                "D" -> if (p.size == 5) {
                    if (p[4] != "200") codeError("download", p[4])
                    else {
                        val secs = p[3].toDouble() - p[2].toDouble()
                        if (secs > 0) downs += p[1].toDouble() * 8 / secs / 1e6
                    }
                }
                "U" -> if (p.size == 2 && p[1] != "200") codeError("upload", p[1])
                else -> if (l.trim().startsWith("{")) uploadJson += l.trim()
            }
        }
        pingCode?.let { codeError("ping", it) }
        val ups = uploadJson.mapNotNull { j ->
            runCatching {
                val o = Json.parseToJsonElement(j).jsonObject
                val bytes = o["bytes"]!!.jsonPrimitive.long
                val ms = o["ms"]!!.jsonPrimitive.long
                if (ms > 0) bytes * 8.0 / (ms / 1000.0) / 1e6 else null
            }.getOrNull()
        }
        if (streams > 1) {
            if (downs.isNotEmpty() && downs.size < streams) errors += "download: only ${downs.size} of $streams streams completed"
            if (ups.isNotEmpty() && ups.size < streams) errors += "upload: only ${ups.size} of $streams streams completed"
        }
        val samples = pings.drop(1)
        val (avg, jitter) = stats(samples)
        return Parsed(
            avg, jitter, samples.size, downs.takeIf { it.isNotEmpty() }?.sum(), ups.takeIf { it.isNotEmpty() }?.sum(),
            errors.distinct().joinToString("; ").ifEmpty { null }, downs, ups,
        )
    }

    /** Mean round trip and jitter (mean absolute difference of consecutive samples). */
    fun stats(samples: List<Double>): Pair<Double?, Double?> {
        if (samples.isEmpty()) return null to null
        val avg = samples.average()
        val jitter = if (samples.size < 2) 0.0 else samples.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
        return avg to jitter
    }
}
