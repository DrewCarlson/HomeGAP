package homegap.tunnels

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.di.TunnelOptions
import homegap.di.TunnelScope
import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.platform.watchDirectory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    /**
     * The endpoint host's addresses, as the orchestrator publishes them in its DNS records. The
     * supervisor pins them in the hosts file, so the connector reaches the right node even while
     * public DNS still has an older answer (a new record not yet on every nameserver, a cache).
     */
    val addresses: List<String> = emptyList(),
) {
    /** The endpoint's host name, which [addresses] are pinned for. */
    val host: String get() = endpoint.substringAfter("://").substringBefore('/').substringBefore(':')

    /** The spec as the running connector sees it: pins live in the hosts file, not in Newt. */
    fun withoutPins(): TunnelSpec = copy(addresses = emptyList())
}

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
    fun readSpecs(dir: String): List<TunnelSpec> =
        Files.list(dir)
        .filter { it.endsWith(".tunnel.json") }
        .mapNotNull { f ->
            runCatching { json.decodeFromString<TunnelSpec>(Files.readText("$dir/$f")) }.getOrNull()
        }
    fun readStatus(dir: String, nodeId: String): TunnelStatus? =
        Files.readTextOrNull(statusPath(dir, nodeId))
            ?.let { runCatching { json.decodeFromString<TunnelStatus>(it) }.getOrNull() }
}

/**
 * `homegap tunnels`: keeps one Newt connector per node running from spec files in a shared
 * directory. It runs as its own container, so tunnels survive orchestrator restarts and the
 * data plane never depends on the controller process.
 *
 * Event-driven: spec changes arrive through [watchDirectory] (inotify on Linux), process exits
 * through a waiter per child, and a slow tick only scrapes metrics and refreshes status files.
 * All state is owned by the single event loop in [run].
 */
@Inject
@SingleIn(TunnelScope::class)
class TunnelSupervisor(options: TunnelOptions, private val http: HttpClient) {
    private val dir = options.dir
    private val newtBinary = options.newtBinary
    private val hostsFile = options.hostsFile
    private val logger = KotlinLogging.logger("homegap.tunnels")

    private class Proc(
        var spec: TunnelSpec,
        val child: Subprocess.Child,
        val startedAt: Long,
        var logOffset: Long,
        /** Logins answered by another server since the last successful connection (see [checkLogs]). */
        var loginRejections: Int = 0,
        val exited: CompletableDeferred<Int> = CompletableDeferred(),
    )

    private sealed interface Event {
        data object Rescan : Event
        data object Tick : Event
        class Exited(val id: String, val proc: Proc, val code: Int) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val procs = mutableMapOf<String, Proc>()
    private val backoffUntil = mutableMapOf<String, Long>()
    private val restartCounts = mutableMapOf<String, Int>()
    private val lastExit = mutableMapOf<String, String>()
    private val metrics = mutableMapOf<String, Metrics?>()
    private val published = mutableMapOf<String, Pair<TunnelStatus, Long>>()
    private var hostsWarned = false

    suspend fun run() = coroutineScope {
        Files.mkdirs(dir)
        Files.mkdirs("$dir/logs")
        logger.info { "Tunnel supervisor watching $dir (newt: $newtBinary)" }
        // Only spec files: the status files this loop writes live in the same directory.
        launch { watchDirectory(dir) { it.endsWith(".tunnel.json") }.collect { events.send(Event.Rescan) } }
        launch { while (true) { delay(TICK); events.send(Event.Tick) } }
        // Safety net for filesystems that do not deliver inotify events (some network mounts).
        launch { while (true) { delay(RESCAN); events.send(Event.Rescan) } }
        events.send(Event.Rescan)
        for (e in events) {
            try {
                when (e) {
                    Event.Rescan -> reconcileSpecs(this)
                    Event.Tick -> { checkLogs(); scrapeAll(); publish(force = false) }
                    is Event.Exited -> onExit(this, e)
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                logger.error(ex) { "supervisor event $e failed" }
            }
        }
    }

    private fun specs(): Map<String, TunnelSpec> = TunnelFiles.readSpecs(dir).associateBy { it.nodeId }

    private suspend fun reconcileSpecs(scope: CoroutineScope) {
        val specs = specs()
        // Pins first, so a connector started below already resolves its endpoint to them.
        syncHosts(specs.values)
        // Stop removed / changed first, so their interfaces and ports are free before starts.
        for ((id, p) in procs.toMap()) {
            val spec = specs[id]
            // New pins need no restart: the hosts file changes under the running connector.
            if (spec != null && spec != p.spec && spec.withoutPins() == p.spec.withoutPins()) p.spec = spec
            if (spec == null || spec != p.spec) {
                logger.info { if (spec == null) "stopping tunnel $id (spec removed)" else "restarting tunnel $id (spec changed)" }
                stop(id, p)
                if (spec == null) {
                    Files.delete(TunnelFiles.statusPath(dir, id))
                    restartCounts.remove(id); published.remove(id); metrics.remove(id)
                } else {
                    backoffUntil.remove(id)
                }
            }
        }
        for ((id, spec) in specs) {
            if (id in procs) continue
            val wait = (backoffUntil[id] ?: 0) - nowMillis()
            if (wait > 0) continue
            // Never start while another connector still holds this interface or admin port.
            val clash = procs.values.firstOrNull { o ->
                o.spec.nodeId != id && (o.spec.adminPort == spec.adminPort || (spec.nativeInterface != null && o.spec.nativeInterface == spec.nativeInterface))
            }
            if (clash != null) {
                logger.warn { "not starting $id yet: ${clash.spec.nodeId} still uses ${if (clash.spec.adminPort == spec.adminPort) "admin port ${spec.adminPort}" else "interface ${spec.nativeInterface}"}" }
                continue
            }
            start(scope, id, spec)
        }
        publish(force = true)
    }

    private fun start(scope: CoroutineScope, id: String, spec: TunnelSpec) {
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
        val log = "$dir/logs/$id.log"
        val logOffset = Files.size(log)
        val child = try {
            Subprocess.start(argv, log, env)
        } catch (e: Exception) {
            logger.error { "could not start newt for $id: ${e.message}" }
            lastExit[id] = "start failed: ${e.message}"
            scheduleRetry(scope, id)
            return
        }
        val proc = Proc(spec, child, nowMillis(), logOffset)
        procs[id] = proc
        scope.launch {
            val code = child.awaitExit()
            proc.exited.complete(code)
            events.send(Event.Exited(id, proc, code))
        }
        logger.info { "started newt for $id -> ${spec.endpoint}${spec.mtu?.let { " (mtu $it" } ?: " ("}${spec.nativeInterface?.let { ", kernel wireguard $it)" } ?: ")"}" }
    }

    private fun onExit(scope: CoroutineScope, e: Event.Exited) {
        // Exits of processes we stopped on purpose are already accounted for.
        if (procs[e.id] !== e.proc) return
        logger.warn { "newt for ${e.id} exited (exit ${e.code})" }
        procs.remove(e.id)
        lastExit[e.id] = "exit ${e.code}"
        scheduleRetry(scope, e.id)
    }

    private fun scheduleRetry(scope: CoroutineScope, id: String) {
        val n = (restartCounts[id] ?: 0) + 1
        restartCounts[id] = n
        val wait = backoff(n)
        backoffUntil[id] = nowMillis() + wait.inWholeMilliseconds
        scope.launch { delay(wait); events.send(Event.Rescan) }
        publish(force = true)
    }

    private suspend fun stop(id: String, p: Proc) {
        procs.remove(id)
        p.child.terminate()
        if (withTimeoutOrNull(STOP_GRACE) { p.exited.await() } == null) {
            p.child.kill()
            p.exited.await()
        }
    }

    /**
     * Restarts connectors that stay up without working, as seen in their logs:
     * - the kernel interface could not be created (Newt then runs without a tunnel);
     * - the login keeps reaching another server (a 404, or a reply that is not Pangolin's). Newt
     *   retries on the same open connection, so one made before the endpoint's address was right
     *   never moves. A new process looks the name up again (through the pins, see [syncHosts]).
     */
    private suspend fun checkLogs() {
        for ((id, p) in procs.toMap()) {
            val log = "$dir/logs/$id.log"
            val size = Files.size(log)
            if (size < p.logOffset) p.logOffset = 0
            if (size == p.logOffset) continue
            val fresh = Files.readFrom(log, p.logOffset, 262_144)
            p.logOffset = size
            val reason = when {
                p.spec.nativeInterface != null && NATIVE_FAILURES.any { it in fresh } ->
                    "kernel interface ${p.spec.nativeInterface} could not be created"
                else -> {
                    p.loginRejections = loginRejections(fresh, p.loginRejections)
                    if (p.loginRejections >= LOGIN_REJECTIONS_BEFORE_RESTART) {
                        "${p.loginRejections} logins to ${p.spec.endpoint} were answered by another server, not its Pangolin"
                    } else {
                        null
                    }
                }
            } ?: continue
            logger.warn { "newt for $id: $reason; restarting" }
            stop(id, p)
            lastExit[id] = reason
            val n = (restartCounts[id] ?: 0) + 1
            restartCounts[id] = n
            backoffUntil[id] = nowMillis() + backoff(n).inWholeMilliseconds
            events.send(Event.Rescan)
        }
    }

    /** Writes the endpoint pins of [specs] into the hosts file when they changed. */
    private fun syncHosts(specs: Collection<TunnelSpec>) {
        val current = Files.readTextOrNull(hostsFile) ?: ""
        val next = HostsPins.render(current, HostsPins.entries(specs))
        if (next == current) return
        try {
            Files.writeAtomic(hostsFile, next, "644".toInt(8))
            hostsWarned = false
            logger.info { "pinned ${HostsPins.entries(specs).size} endpoint address(es) in $hostsFile" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Connectors still work through public DNS; say so once rather than every rescan.
            if (!hostsWarned) logger.warn { "could not write endpoint pins to $hostsFile (${e.message}); connectors use public DNS" }
            hostsWarned = true
        }
    }

    private suspend fun scrapeAll() {
        for ((id, p) in procs) metrics[id] = scrape(p.spec.adminPort)
    }

    /** Writes status files only when something changed, plus a heartbeat for staleness checks. */
    private fun publish(force: Boolean) {
        val now = nowMillis()
        for (spec in specs().values) {
            val id = spec.nodeId
            val p = procs[id]
            val m = if (p != null) metrics[id] else null
            val status = TunnelStatus(
                nodeId = id,
                running = p != null,
                pid = null,
                startedAt = p?.startedAt,
                restarts = restartCounts[id] ?: 0,
                lastExit = lastExit[id],
                connected = m?.connected,
                bytesIn = m?.bytesIn,
                bytesOut = m?.bytesOut,
                updatedAt = 0,
            )
            val prev = published[id]
            if (!force && prev != null && prev.first == status && now - prev.second < HEARTBEAT.inWholeMilliseconds) continue
            published[id] = status to now
            Files.writeAtomic(TunnelFiles.statusPath(dir, id), TunnelFiles.json.encodeToString(status.copy(updatedAt = now)), "644".toInt(8))
        }
    }

    data class Metrics(val connected: Boolean?, val bytesIn: Long?, val bytesOut: Long?)

    private suspend fun scrape(port: Int): Metrics? = runCatching {
        parseMetrics(http.get("http://127.0.0.1:$port/metrics").bodyAsText())
    }.onFailure {
        if (it is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }.getOrNull()

    companion object {
        val TICK = 10.seconds
        val RESCAN = 60.seconds

        /** Status files are rewritten at least this often even when unchanged (staleness check). */
        val HEARTBEAT = 20.seconds

        /** How long a stopping Newt gets to exit on SIGTERM before SIGKILL. */
        val STOP_GRACE = 5.seconds

        /** Restart delay after the [restarts]th failure: 2 s, 4 s, … up to a minute. */
        fun backoff(restarts: Int): Duration = minOf(2.seconds * restarts, 60.seconds)

        /** Newt log lines meaning the kernel WireGuard tunnel was not set up. */
        val NATIVE_FAILURES = listOf("Failed to create native main TUN device")

        /**
         * Newt's lines for a login answered by something other than this node's Pangolin: a 404
         * (a Traefik with no route for the name) or a reply that is not Pangolin's JSON (another
         * service behind that name there). A refused secret (401) is not one of them.
         */
        val WRONG_SERVER = listOf(
            "failed to get token with status code: 404",
            "failed to get token: failed to decode token response",
        )

        /** Newt's line once its control connection is up. */
        const val CONNECTED = "Websocket connected"

        /** About 15 s of Newt's 3 s retries; a few while a node starts up are not stuck. */
        const val LOGIN_REJECTIONS_BEFORE_RESTART = 5

        /** Login rejections after reading [fresh] log text, starting from [before]; a connection resets the count. */
        fun loginRejections(fresh: String, before: Int): Int {
            var n = before
            for (line in fresh.lineSequence()) {
                if (WRONG_SERVER.any { it in line }) n++ else if (CONNECTED in line) n = 0
            }
            return n
        }

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
