package homegap.speedtest

import homegap.platform.Files
import homegap.api.NodeCpuDto
import homegap.api.SpeedTestLiveDto
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The homelab ↔ node test running now, measured where its traffic ends: the speed test origin.
 * The node's script pings one after another on one connection, so the gap between two pings is
 * about one round trip; the bytes the origin sends and receives give the rates. The script's own
 * figures replace these once it finishes.
 */
class SpeedTestLive(
    private val clock: () -> Long = ::nowMillis,
    /** The CPU counters of this host. A container reads the counters of the whole host here. */
    private val cpu: () -> CpuCounters? = { Files.readTextOrNull("/proc/stat")?.let(CpuCounters::parse) },
) {
    private val lock = SynchronizedObject()
    private var token: String? = null
    private var nodeId = ""
    private var phase = "ping"
    private var lastPing: Long? = null
    private val pings = mutableListOf<Double>()
    /** (time, total bytes) of the current phase, the last ~2 s. */
    private val window = ArrayDeque<Pair<Long, Long>>()
    private var bytes = 0L
    private var phaseStart = 0L
    private val samples = mutableListOf<Double>()
    private var lastSample = 0L
    private var downMbps: Double? = null
    /** The CPU counters at the start of the download, at the change to the upload, and at the end. */
    private val marks = arrayOfNulls<CpuCounters>(3)

    fun start(token: String, nodeId: String) = synchronized(lock) {
        this.token = token; this.nodeId = nodeId; phase = "ping"; lastPing = null; pings.clear()
        resetPhase()
        downMbps = null
        marks.fill(null)
    }

    fun stop(token: String) = synchronized(lock) { if (this.token == token) this.token = null }

    /**
     * How busy this host was in the download and in the upload phase of the test of [token].
     * Call it when the test ends, before [stop].
     */
    fun hostCpu(token: String): Pair<NodeCpuDto?, NodeCpuDto?> = synchronized(lock) {
        if (this.token != token) return@synchronized null to null
        val end = cpu()
        val down = marks[0]?.let { start -> (marks[1] ?: end)?.since(start) }
        val up = marks[1]?.let { start -> end?.since(start) }
        down to up
    }

    fun ping(token: String?) = synchronized(lock) {
        if (token == null || token != this.token) return@synchronized
        val t = clock()
        lastPing?.let { pings += (t - it).toDouble() }
        lastPing = t
    }

    /** [n] bytes sent (download) or received (upload) for the running test. */
    fun bytes(token: String?, upload: Boolean, n: Int) = synchronized(lock) {
        if (token == null || token != this.token) return@synchronized
        val p = if (upload) "upload" else "download"
        if (phase != p) {
            if (phase == "download") downMbps = average()
            phase = p
            resetPhase()
            marks[if (upload) 1 else 0] = cpu()
        }
        val t = clock()
        bytes += n
        window.addLast(t to bytes)
        while (window.size > 2 && t - window.first().first > 2_000) window.removeFirst()
        if (t - lastSample >= 250) {
            rate(t)?.let { samples += it; if (samples.size > 60) samples.removeAt(0) }
            lastSample = t
        }
    }

    fun snapshot(): SpeedTestLiveDto? = synchronized(lock) {
        if (token == null) return@synchronized null
        val rtts = pings.drop(1)
        val (avg, jitter) = TunnelSpeedTest.stats(rtts)
        SpeedTestLiveDto(
            nodeId = nodeId, phase = phase, pingSamples = rtts.takeLast(60), pingMs = avg, jitterMs = jitter,
            mbps = if (phase == "ping") null else rate(clock()), mbpsSamples = samples.toList(), downMbps = downMbps,
        )
    }

    private fun resetPhase() {
        window.clear(); bytes = 0; phaseStart = clock(); samples.clear(); lastSample = 0
    }

    /** Mbps over the last second of [window]. */
    private fun rate(t: Long): Double? {
        val from = window.firstOrNull { t - it.first <= 1_000 } ?: return null
        val last = window.last()
        val ms = last.first - from.first
        if (ms < 200) return null
        return (last.second - from.second) * 8.0 / (ms / 1000.0) / 1e6
    }

    private fun average(): Double? {
        val ms = (window.lastOrNull()?.first ?: return null) - phaseStart
        return if (ms > 0) bytes * 8.0 / (ms / 1000.0) / 1e6 else null
    }
}
