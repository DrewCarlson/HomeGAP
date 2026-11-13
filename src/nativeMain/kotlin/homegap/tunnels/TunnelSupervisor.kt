package homegap.tunnels

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.di.TunnelOptions
import homegap.di.TunnelScope
import homegap.platform.Files
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The orchestrator writes one for each regional node. */
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
    /** Kernel WireGuard interface name for the main tunnel. Null means Newt's userspace stack. */
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

/** The supervisor writes one next to each spec. The orchestrator reads it for observability. */
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
    /** Why the connector stopped, newest first (the last ten). */
    val exits: List<TunnelExit> = emptyList(),
)

/** One stop of a connector: when, and why (`exit 1`, a restart from the dashboard, ...). */
@Serializable
data class TunnelExit(val at: Long, val reason: String)

object TunnelFiles {
    const val SPEC_SUFFIX = ".tunnel.json"

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun specPath(dir: String, nodeId: String) = "$dir/$nodeId$SPEC_SUFFIX"

    fun statusPath(dir: String, nodeId: String) = "$dir/$nodeId.status.json"

    /**
     * The orchestrator writes this file to ask the supervisor for a restart of a connector. The
     * supervisor removes the file when it does the restart.
     */
    fun restartPath(dir: String, nodeId: String) = "$dir/$nodeId.restart"

    fun readSpecs(dir: String): List<TunnelSpec> =
        Files.list(dir)
            .filter { it.endsWith(SPEC_SUFFIX) }
            .mapNotNull { file -> readSpec("$dir/$file").getOrNull() }

    /** The node ids whose spec file exists but does not parse. */
    fun unreadableSpecs(dir: String): Set<String> =
        Files.list(dir)
            .filter { it.endsWith(SPEC_SUFFIX) }
            .filter { file -> readSpec("$dir/$file").isFailure }
            .mapTo(mutableSetOf()) { it.removeSuffix(SPEC_SUFFIX) }

    private fun readSpec(path: String): Result<TunnelSpec> =
        runCatching { json.decodeFromString<TunnelSpec>(Files.readText(path)) }

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
 * The single event loop in [run] owns all state.
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
    private val failures = mutableMapOf<String, Int>()
    private val unreadableWarned = mutableSetOf<String>()
    private val lastExit = mutableMapOf<String, String>()
    private val exits = mutableMapOf<String, List<TunnelExit>>()
    private val metrics = mutableMapOf<String, Metrics?>()
    private val published = mutableMapOf<String, Pair<TunnelStatus, Long>>()
    private var hostsWarned = false

    /** Remembers why [id] stopped, for the dashboard's restart history. */
    private fun recordExit(id: String, reason: String) {
        lastExit[id] = reason
        exits[id] = (listOf(TunnelExit(nowMillis(), reason)) + exits[id].orEmpty()).take(EXIT_HISTORY_SIZE)
    }

    suspend fun run() = coroutineScope {
        Files.mkdirs(dir)
        Files.mkdirs("$dir/logs")
        logger.info { "Tunnel supervisor watching $dir (newt: $newtBinary)" }
        // Only spec files: the status files this loop writes live in the same directory.
        launch {
            watchDirectory(dir) { it.endsWith(TunnelFiles.SPEC_SUFFIX) }.collect { events.send(Event.Rescan) }
        }
        launch {
            while (true) {
                delay(TICK)
                events.send(Event.Tick)
            }
        }
        // Safety net for filesystems that do not deliver inotify events (some network mounts).
        launch {
            while (true) {
                delay(RESCAN)
                events.send(Event.Rescan)
            }
        }
        events.send(Event.Rescan)
        for (event in events) {
            runCatchingCancellable { handle(this, event) }
                .onFailure { logger.error(it) { "supervisor event $event failed" } }
        }
    }

    private suspend fun handle(scope: CoroutineScope, event: Event) {
        when (event) {
            Event.Rescan -> reconcileSpecs(scope)
            Event.Tick -> {
                checkLogs()
                restartRequested()
                scrapeAll()
                publish(force = false)
            }
            is Event.Exited -> onExit(scope, event)
        }
    }

    private fun specs(): Map<String, TunnelSpec> = TunnelFiles.readSpecs(dir).associateBy { it.nodeId }

    private suspend fun reconcileSpecs(scope: CoroutineScope) {
        val specs = specs()
        // Pins first, so a connector started below already resolves its endpoint to them.
        syncHosts(specs.values)
        // Stop removed / changed first, so their interfaces and ports are free before starts.
        stopRemovedAndChanged(specs)
        startMissing(scope, specs)
        publish(force = true)
    }

    private suspend fun stopRemovedAndChanged(specs: Map<String, TunnelSpec>) {
        val unreadable = TunnelFiles.unreadableSpecs(dir)
        unreadableWarned.retainAll(unreadable)
        for ((id, proc) in procs.toMap()) {
            val spec = specs[id]
            if (spec == null && id in unreadable) {
                if (unreadableWarned.add(id)) {
                    logger.warn { "the spec of tunnel $id is not readable; the tunnel keeps its last spec" }
                }
                continue
            }
            // New pins need no restart: the hosts file changes under the running connector.
            if (spec != null && spec != proc.spec && spec.withoutPins() == proc.spec.withoutPins()) {
                proc.spec = spec
            }
            if (spec != null && spec == proc.spec) continue
            if (spec == null) {
                logger.info { "stopping tunnel $id (spec removed)" }
            } else {
                logger.info { "restarting tunnel $id (spec changed)" }
            }
            stop(id, proc)
            if (spec == null) {
                Files.delete(TunnelFiles.statusPath(dir, id))
                restartCounts.remove(id)
                failures.remove(id)
                published.remove(id)
                metrics.remove(id)
            } else {
                backoffUntil.remove(id)
            }
        }
    }

    private fun startMissing(scope: CoroutineScope, specs: Map<String, TunnelSpec>) {
        for ((id, spec) in specs) {
            if (id in procs) continue
            val wait = (backoffUntil[id] ?: 0) - nowMillis()
            if (wait > 0) continue
            // Never start while another connector still holds this interface or admin port.
            val clash = procs.values.firstOrNull { sharesResource(it.spec, spec) }
            if (clash != null) {
                logger.warn {
                    val samePort = clash.spec.adminPort == spec.adminPort
                    val resource = if (samePort) "admin port ${spec.adminPort}" else "interface ${spec.nativeInterface}"
                    "not starting $id yet: ${clash.spec.nodeId} still uses $resource"
                }
                continue
            }
            start(scope, id, spec)
        }
    }

    /** True if the connector with spec [running] holds the admin port or the kernel interface of [spec]. */
    private fun sharesResource(running: TunnelSpec, spec: TunnelSpec): Boolean {
        if (running.nodeId == spec.nodeId) return false
        val samePort = running.adminPort == spec.adminPort
        val sameInterface = spec.nativeInterface != null && running.nativeInterface == spec.nativeInterface
        return samePort || sameInterface
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
            spec.mtu?.let {
                add("--mtu")
                add(it.toString())
            }
            spec.nativeInterface?.let {
                add("--native-main")
                add("--interface-main")
                add(it)
            }
        }
        val log = logPath(id)
        val logOffset = Files.size(log)
        val child = try {
            Subprocess.start(argv, log, env)
        } catch (e: Exception) {
            logger.error { "could not start newt for $id: ${e.message}" }
            lastExit[id] = "start failed: ${e.message}"
            scheduleRetry(scope, id, startedAt = null)
            return
        }
        val proc = Proc(spec, child, nowMillis(), logOffset)
        procs[id] = proc
        scope.launch {
            val code = child.awaitExit()
            proc.exited.complete(code)
            events.send(Event.Exited(id, proc, code))
        }
        logger.info {
            val mtu = spec.mtu?.let { " (mtu $it" } ?: " ("
            val kernel = spec.nativeInterface?.let { ", kernel wireguard $it)" } ?: ")"
            "started newt for $id -> ${spec.endpoint}$mtu$kernel"
        }
    }

    private fun logPath(id: String) = "$dir/logs/$id.log"

    private fun onExit(scope: CoroutineScope, event: Event.Exited) {
        // Exits of processes we stopped on purpose are already accounted for.
        if (procs[event.id] !== event.proc) return
        logger.warn { "newt for ${event.id} exited (exit ${event.code})" }
        procs.remove(event.id)
        recordExit(event.id, "exit ${event.code}")
        scheduleRetry(scope, event.id, event.proc.startedAt)
    }

    /**
     * Counts a restart of [id] and returns the wait before it. A connector that ran for [STABLE]
     * starts again from the shortest wait.
     */
    private fun nextBackoff(id: String, startedAt: Long?): Duration {
        restartCounts[id] = (restartCounts[id] ?: 0) + 1
        val stable = startedAt != null && nowMillis() - startedAt >= STABLE.inWholeMilliseconds
        val failureCount = if (stable) 1 else (failures[id] ?: 0) + 1
        failures[id] = failureCount
        val wait = backoff(failureCount)
        backoffUntil[id] = nowMillis() + wait.inWholeMilliseconds
        return wait
    }

    private fun scheduleRetry(scope: CoroutineScope, id: String, startedAt: Long?) {
        val wait = nextBackoff(id, startedAt)
        scope.launch {
            delay(wait)
            events.send(Event.Rescan)
        }
        publish(force = true)
    }

    private suspend fun stop(id: String, proc: Proc) {
        procs.remove(id)
        proc.child.terminate()
        if (withTimeoutOrNull(STOP_GRACE) { proc.exited.await() } == null) {
            proc.child.kill()
            proc.exited.await()
        }
    }

    /**
     * Restarts connectors that stay up without working, as seen in their logs:
     * - the kernel interface could not be created (Newt then runs without a tunnel).
     * - the login keeps reaching another server (a 404, or a reply that is not Pangolin's). Newt
     *   retries on the same open connection, so one made before the endpoint's address was right
     *   never moves. A new process looks the name up again (through the pins, see [syncHosts]).
     */
    private suspend fun checkLogs() {
        for ((id, proc) in procs.toMap()) {
            val fresh = freshLog(id, proc) ?: continue
            val reason = restartReason(proc, fresh) ?: continue
            logger.warn { "newt for $id: $reason; restarting" }
            stop(id, proc)
            recordExit(id, reason)
            nextBackoff(id, proc.startedAt)
            events.send(Event.Rescan)
        }
    }

    /**
     * The text that the log of [id] got after the last read, or null if there is none. Empties
     * a log that is larger than [MAX_LOG_BYTES].
     */
    private fun freshLog(id: String, proc: Proc): String? {
        val log = logPath(id)
        val size = Files.size(log)
        if (size < proc.logOffset) {
            proc.logOffset = 0
        }
        if (size == proc.logOffset) return null
        val fresh = Files.readFrom(log, proc.logOffset, LOG_READ_BYTES)
        proc.logOffset = size
        if (size > MAX_LOG_BYTES) {
            Files.truncate(log)
            proc.logOffset = 0
        }
        return fresh
    }

    /** Why [proc] needs a restart, from the new text [fresh] of its log, or null if it needs none. */
    private fun restartReason(proc: Proc, fresh: String): String? {
        val kernelInterface = proc.spec.nativeInterface
        if (kernelInterface != null && NATIVE_FAILURES.any { it in fresh }) {
            return "kernel interface $kernelInterface could not be created"
        }
        proc.loginRejections = loginRejections(fresh, proc.loginRejections)
        val rejections = proc.loginRejections
        if (rejections < LOGIN_REJECTIONS_BEFORE_RESTART) return null
        return "$rejections logins to ${proc.spec.endpoint} were answered by another server, not its Pangolin"
    }

    /**
     * Restarts the connectors the orchestrator asked for (a request file each), at once and
     * without backoff.
     */
    private suspend fun restartRequested() {
        for (id in specs().keys) {
            val request = TunnelFiles.restartPath(dir, id)
            if (!Files.exists(request)) continue
            Files.delete(request)
            procs[id]?.let { stop(id, it) }
            recordExit(id, "restarted from the dashboard")
            backoffUntil.remove(id)
            logger.info { "newt for $id: restarting as asked" }
            events.send(Event.Rescan)
        }
    }

    /** Writes the endpoint pins of [specs] into the hosts file when they changed. */
    private fun syncHosts(specs: Collection<TunnelSpec>) {
        val current = Files.readTextOrNull(hostsFile).orEmpty()
        val next = HostsPins.render(current, HostsPins.entries(specs))
        if (next == current) return
        try {
            Files.writeAtomic(hostsFile, next, WORLD_READABLE)
            hostsWarned = false
            logger.info { "updated $hostsFile (${HostsPins.entries(specs).size} entries)" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Connectors still work through public DNS. Say so once, not in every rescan.
            if (!hostsWarned) {
                logger.warn { "could not write endpoint pins to $hostsFile (${e.message}); connectors use public DNS" }
            }
            hostsWarned = true
        }
    }

    private suspend fun scrapeAll() {
        for ((id, proc) in procs) {
            metrics[id] = scrape(proc.spec.adminPort)
        }
    }

    /** Writes status files only when something changed, plus a heartbeat for staleness checks. */
    private fun publish(force: Boolean) {
        val now = nowMillis()
        for (spec in specs().values) {
            val id = spec.nodeId
            val proc = procs[id]
            val scraped = if (proc != null) metrics[id] else null
            val status = TunnelStatus(
                nodeId = id,
                running = proc != null,
                pid = null,
                startedAt = proc?.startedAt,
                restarts = restartCounts[id] ?: 0,
                lastExit = lastExit[id],
                exits = exits[id].orEmpty(),
                connected = scraped?.connected,
                bytesIn = scraped?.bytesIn,
                bytesOut = scraped?.bytesOut,
                updatedAt = 0,
            )
            val previous = published[id]
            val unchanged = previous != null &&
                previous.first == status &&
                now - previous.second < HEARTBEAT.inWholeMilliseconds
            if (!force && unchanged) continue
            published[id] = status to now
            val text = TunnelFiles.json.encodeToString(status.copy(updatedAt = now))
            Files.writeAtomic(TunnelFiles.statusPath(dir, id), text, WORLD_READABLE)
        }
    }

    data class Metrics(val connected: Boolean?, val bytesIn: Long?, val bytesOut: Long?)

    private suspend fun scrape(port: Int): Metrics? = runCatchingCancellable {
        parseMetrics(http.get("http://127.0.0.1:$port/metrics").bodyAsText())
    }.getOrNull()

    companion object {
        val TICK = 10.seconds
        val RESCAN = 60.seconds

        /** The supervisor writes each status file again at least this often (staleness check). */
        val HEARTBEAT = 20.seconds

        /** How long a stopping Newt gets to exit on SIGTERM before SIGKILL. */
        val STOP_GRACE = 5.seconds

        /** Restart delay after the [restarts]th failure: 2 s, 4 s, … up to a minute. */
        fun backoff(restarts: Int): Duration = minOf(2.seconds * restarts, 60.seconds)

        val STABLE = 10.minutes
        const val MAX_LOG_BYTES = 16L * 1024 * 1024

        /** The most log text that one tick reads. */
        private const val LOG_READ_BYTES = 262_144
        private const val EXIT_HISTORY_SIZE = 10

        /** The mode of the hosts file and the status files: all users can read them. */
        private val WORLD_READABLE = "644".toInt(8)

        /** Newt log lines that show a failure to create the kernel WireGuard tunnel. */
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

        /** About 15 s of Newt's 3 s retries. A few rejections while a node starts are normal. */
        const val LOGIN_REJECTIONS_BEFORE_RESTART = 5

        /**
         * The count of login rejections after the log text [fresh], with [before] as the count
         * until then. A connection sets the count back to zero.
         */
        fun loginRejections(fresh: String, before: Int): Int {
            var count = before
            for (line in fresh.lineSequence()) {
                if (WRONG_SERVER.any { it in line }) {
                    count++
                } else if (CONNECTED in line) {
                    count = 0
                }
            }
            return count
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
                        val ingress = line.contains("direction=\"ingress\"") || line.contains("direction=\"in\"")
                        val egress = line.contains("direction=\"egress\"") || line.contains("direction=\"out\"")
                        if (ingress) {
                            inBytes += value
                        } else if (egress) {
                            outBytes += value
                        }
                    }
                    line.startsWith("newt_websocket_connected") -> connected = (connected ?: false) || value >= 1.0
                }
            }
            return Metrics(
                connected = connected,
                bytesIn = if (sawBytes) inBytes.toLong() else null,
                bytesOut = if (sawBytes) outBytes.toLong() else null,
            )
        }
    }
}
