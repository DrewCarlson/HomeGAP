package homegap.speedtest

import homegap.api.NodeCpuDto
import homegap.api.SpeedTestLiveDto
import homegap.platform.Files
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The test between the homelab and a node that runs now. The speed test origin measures it, because
 * the traffic of the test ends there. The script of the node sends one ping after the other on one
 * connection, so the gap between two pings is about one round trip. The bytes that the origin sends
 * and receives give the rates. When the script finishes, its own figures replace these.
 */
class SpeedTestLive(
    private val clock: () -> Long = ::nowMillis,
    /** The CPU counters of this host. A container reads the counters of the whole host here. */
    private val cpu: () -> CpuCounters? = { Files.readTextOrNull("/proc/stat")?.let(CpuCounters::parse) },
) {
    private val lock = SynchronizedObject()
    private var token: String? = null
    private var nodeId = ""
    private var phase = PING
    private var lastPing: Long? = null
    private val pings = mutableListOf<Double>()

    /** (time, total bytes) of the current phase, for the last 2 seconds. */
    private val window = ArrayDeque<Pair<Long, Long>>()
    private var bytes = 0L
    private var phaseStart = 0L
    private val samples = mutableListOf<Double>()
    private var lastSample = 0L
    private var downMbps: Double? = null

    /** The CPU counters at the start of the download, at the change to the upload, and at the end. */
    private val marks = arrayOfNulls<CpuCounters>(3)

    fun start(token: String, nodeId: String) = synchronized(lock) {
        this.token = token
        this.nodeId = nodeId
        phase = PING
        lastPing = null
        pings.clear()
        resetPhase()
        downMbps = null
        marks.fill(null)
    }

    fun stop(token: String) = synchronized(lock) {
        if (this.token == token) {
            this.token = null
        }
    }

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
        val now = clock()

        lastPing?.let { pings += (now - it).toDouble() }
        lastPing = now
    }

    /** Counts [n] bytes that the origin sends (download) or receives (upload) for the test that runs. */
    fun bytes(token: String?, upload: Boolean, n: Int) = synchronized(lock) {
        if (token == null || token != this.token) return@synchronized
        val nextPhase = if (upload) UPLOAD else DOWNLOAD
        if (phase != nextPhase) {
            if (phase == DOWNLOAD) {
                downMbps = average()
            }
            phase = nextPhase
            resetPhase()
            marks[if (upload) 1 else 0] = cpu()
        }
        val now = clock()
        bytes += n
        window.addLast(now to bytes)
        while (window.size > 2 && now - window.first().first > WINDOW_MILLIS) {
            window.removeFirst()
        }
        if (now - lastSample >= SAMPLE_INTERVAL_MILLIS) {
            rate(now)?.let { mbps ->
                samples += mbps
                if (samples.size > MAX_SAMPLES) {
                    samples.removeAt(0)
                }
            }
            lastSample = now
        }
    }

    fun snapshot(): SpeedTestLiveDto? = synchronized(lock) {
        if (token == null) return@synchronized null
        val roundTrips = pings.drop(1)
        val (average, jitter) = TunnelSpeedTest.stats(roundTrips)
        SpeedTestLiveDto(
            nodeId = nodeId,
            phase = phase,
            pingSamples = roundTrips.takeLast(MAX_SAMPLES),
            pingMs = average,
            jitterMs = jitter,
            mbps = if (phase == PING) null else rate(clock()),
            mbpsSamples = samples.toList(),
            downMbps = downMbps,
        )
    }

    private fun resetPhase() {
        window.clear()
        bytes = 0
        phaseStart = clock()
        samples.clear()
        lastSample = 0
    }

    /** Mbps over the last second of [window]. */
    private fun rate(now: Long): Double? {
        val from = window.firstOrNull { now - it.first <= RATE_MILLIS } ?: return null
        val last = window.last()
        val millis = last.first - from.first
        if (millis < MIN_RATE_MILLIS) return null
        return (last.second - from.second) * 8.0 / (millis / 1000.0) / 1e6
    }

    private fun average(): Double? {
        val millis = (window.lastOrNull()?.first ?: return null) - phaseStart
        return if (millis > 0) bytes * 8.0 / (millis / 1000.0) / 1e6 else null
    }

    private companion object {
        const val PING = "ping"
        const val DOWNLOAD = "download"
        const val UPLOAD = "upload"
        const val WINDOW_MILLIS = 2_000
        const val SAMPLE_INTERVAL_MILLIS = 250
        const val RATE_MILLIS = 1_000
        const val MIN_RATE_MILLIS = 200
        const val MAX_SAMPLES = 60
    }
}
