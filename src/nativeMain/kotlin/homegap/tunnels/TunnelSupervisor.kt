package homegap.tunnels

import homegap.platform.Files
import homegap.platform.Processes
import homegap.platform.nowMillis
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Written by the orchestrator: one per regional node. */
@Serializable
data class TunnelSpec(
    val nodeId: String,
    val endpoint: String,
    val newtId: String,
    val newtSecret: String,
    val adminPort: Int,
    val logLevel: String = "INFO",
    /** WireGuard MTU (null = Newt default). */
    val mtu: Int? = null,
    /** Kernel WireGuard interface name for the main tunnel; null = Newt's userspace stack. */
    val nativeInterface: String? = null,
)

/** Written by the supervisor next to each spec, read by the orchestrator for observability. */
@Serializable
data class TunnelStatus(
    val nodeId: String,
    val running: Boolean,
    val pid: Int? = null,
    val startedAt: Long? = null,
    val restarts: Int = 0,
    val lastExit: String? = null,
    val connected: Boolean? = null,
    val bytesIn: Long? = null,
    val bytesOut: Long? = null,
    val updatedAt: Long,
)

object TunnelFiles {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    fun specPath(dir: String, nodeId: String) = "$dir/$nodeId.tunnel.json"
    fun statusPath(dir: String, nodeId: String) = "$dir/$nodeId.status.json"
    fun readSpecs(dir: String): List<TunnelSpec> = Files.list(dir).filter { it.endsWith(".tunnel.json") }.mapNotNull { f ->
        runCatching { json.decodeFromString(TunnelSpec.serializer(), Files.readText("$dir/$f")) }.getOrNull()
    }
    fun readStatus(dir: String, nodeId: String): TunnelStatus? =
        Files.readTextOrNull(statusPath(dir, nodeId))?.let { runCatching { json.decodeFromString(TunnelStatus.serializer(), it) }.getOrNull() }
}

/**
 * `homegap tunnels`: keeps one Newt connector per node running from spec files in a shared
 * directory. It runs as its own container, so tunnels survive orchestrator restarts and the
 * data plane never depends on the controller process.
 */
class TunnelSupervisor(
    private val dir: String,
    private val newtBinary: String,
    private val http: HttpClient,
) {
    private val logger = KotlinLogging.logger {}

    private class Proc(val spec: TunnelSpec, val pid: Int, val startedAt: Long, var restarts: Int)

    private val procs = mutableMapOf<String, Proc>()
    private val backoffUntil = mutableMapOf<String, Long>()
    private val restartCounts = mutableMapOf<String, Int>()
    private val lastExit = mutableMapOf<String, String>()

    suspend fun run() {
        Files.mkdirs(dir)
        Files.mkdirs("$dir/logs")
        logger.info { "Tunnel supervisor watching $dir (newt: $newtBinary)" }
        while (true) {
            try {
                tick()
            } catch (e: Exception) {
                logger.error(e) { "supervisor tick failed" }
            }
            delay(3000)
        }
    }

    private suspend fun tick() {
        val specs = TunnelFiles.readSpecs(dir).associateBy { it.nodeId }

        // reap exited processes
        for ((id, p) in procs.toMap()) {
            val exit = Processes.reap(p.pid) ?: continue
            logger.warn { "newt for $id exited ($exit)" }
            lastExit[id] = exit
            procs.remove(id)
            val n = (restartCounts[id] ?: 0) + 1
            restartCounts[id] = n
            backoffUntil[id] = nowMillis() + minOf(60_000L, 2_000L * n)
        }

        // stop removed / changed
        for ((id, p) in procs.toMap()) {
            val spec = specs[id]
            if (spec == null || spec != p.spec) {
                logger.info { if (spec == null) "stopping tunnel $id (spec removed)" else "restarting tunnel $id (spec changed)" }
                stop(p)
                procs.remove(id)
                if (spec == null) {
                    Files.delete(TunnelFiles.statusPath(dir, id))
                    restartCounts.remove(id)
                } else {
                    backoffUntil.remove(id)
                }
            }
        }

        // start missing
        for ((id, spec) in specs) {
            if (id in procs) continue
            if ((backoffUntil[id] ?: 0) > nowMillis()) continue
            val env = mapOf(
                "PANGOLIN_ENDPOINT" to spec.endpoint,
                "NEWT_ID" to spec.newtId,
                "NEWT_SECRET" to spec.newtSecret,
                "LOG_LEVEL" to spec.logLevel,
                "NEWT_METRICS_PROMETHEUS_ENABLED" to "true",
                "NEWT_ADMIN_ADDR" to "127.0.0.1:${spec.adminPort}",
                "NEWT_NAME" to "homegap-${spec.nodeId}",
                "DOCKER_SOCKET" to "",
            )
            val argv = buildList {
                add(newtBinary)
                spec.mtu?.let { add("--mtu"); add(it.toString()) }
                spec.nativeInterface?.let { add("--native-main"); add("--interface-main"); add(it) }
            }
            val pid = Processes.spawnDetached(argv, env, "$dir/logs/$id.log", inherit = true)
            logger.info { "started newt for $id -> ${spec.endpoint} (pid $pid${spec.mtu?.let { ", mtu $it" } ?: ""}${spec.nativeInterface?.let { ", kernel wireguard $it" } ?: ""})" }
            procs[id] = Proc(spec, pid, nowMillis(), restartCounts[id] ?: 0)
        }

        // publish status
        for ((id, spec) in specs) {
            val p = procs[id]
            val metrics = p?.let { scrape(spec.adminPort) }
            val status = TunnelStatus(
                nodeId = id,
                running = p != null,
                pid = p?.pid,
                startedAt = p?.startedAt,
                restarts = restartCounts[id] ?: 0,
                lastExit = lastExit[id],
                connected = metrics?.connected,
                bytesIn = metrics?.bytesIn,
                bytesOut = metrics?.bytesOut,
                updatedAt = nowMillis(),
            )
            Files.writeAtomic(TunnelFiles.statusPath(dir, id), TunnelFiles.json.encodeToString(TunnelStatus.serializer(), status), "644".toInt(8))
        }
    }

    private suspend fun stop(p: Proc) {
        Processes.terminate(p.pid)
        repeat(50) {
            if (Processes.reap(p.pid) != null) return
            delay(100)
        }
        Processes.forceKill(p.pid)
        Processes.reap(p.pid)
    }

    data class Metrics(val connected: Boolean?, val bytesIn: Long?, val bytesOut: Long?)

    private suspend fun scrape(port: Int): Metrics? = runCatching {
        parseMetrics(http.get("http://127.0.0.1:$port/metrics").bodyAsText())
    }.getOrNull()

    companion object {
        fun parseMetrics(text: String): Metrics {
            var inBytes = 0.0
            var outBytes = 0.0
            var sawBytes = false
            var connected: Boolean? = null
            for (line in text.lineSequence()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val value = line.substringAfterLast(' ').toDoubleOrNull() ?: continue
                when {
                    line.startsWith("newt_tunnel_bytes") -> {
                        sawBytes = true
                        if (line.contains("direction=\"ingress\"") || line.contains("direction=\"in\"")) inBytes += value
                        else if (line.contains("direction=\"egress\"") || line.contains("direction=\"out\"")) outBytes += value
                    }
                    line.startsWith("newt_websocket_connected") -> connected = (connected ?: false) || value >= 1.0
                }
            }
            return Metrics(connected, if (sawBytes) inBytes.toLong() else null, if (sawBytes) outBytes.toLong() else null)
        }
    }
}
