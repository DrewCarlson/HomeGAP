package homegap.reconcile

import homegap.api.MeshOutageDto
import homegap.api.MeshProbeDto

/**
 * The homelab's own probe of its mesh peers: one request per pass to each member's Patroni over
 * the mesh, timed. Nodes run a probe every second; the homelab has no probe container, so this
 * fills its row of the mesh matrix with a coarser view: the latest round trip, the share of
 * passes without an answer in the last hour, and outages (three or more misses in a row).
 */
class HomelabProbe(private val windowMs: Long = 3_600_000L) {
    private val samples = mutableMapOf<String, ArrayDeque<Pair<Long, Double?>>>()

    /** Records one sample of [peer] taken at [at]: the round trip in ms, or null without an answer. */
    fun record(peer: String, at: Long, rttMs: Double?) {
        val queue = samples.getOrPut(peer) { ArrayDeque() }
        queue.addLast(at to rttMs)
        while (queue.isNotEmpty() && queue.first().first < at - windowMs) queue.removeFirst()
    }

    /** Each peer's view, as the mesh matrix shows it. */
    fun view(): Map<String, MeshProbeDto> = samples.mapValues { (_, queue) -> viewOf(queue.toList()) }

    fun retain(peers: Set<String>) {
        samples.keys.retainAll(peers)
    }

    private fun viewOf(points: List<Pair<Long, Double?>>): MeshProbeDto {
        val lost = points.count { it.second == null }
        val outages = mutableListOf<MeshOutageDto>()
        var runStart: Long? = null
        var runLength = 0
        for ((at, rtt) in points) {
            if (rtt == null) {
                if (runStart == null) runStart = at
                runLength++
            } else {
                if (runLength >= OUTAGE_MISSES && runStart != null) outages += MeshOutageDto(runStart, at)
                runStart = null
                runLength = 0
            }
        }
        if (runLength >= OUTAGE_MISSES && runStart != null) outages += MeshOutageDto(runStart, null)
        return MeshProbeDto(
            rttMs = points.lastOrNull()?.second,
            lossPercent = if (points.isEmpty()) 0.0 else lost * 100.0 / points.size,
            sent = points.size.toLong(),
            windowMs = if (points.size > 1) points.last().first - points.first().first else 0,
            outages = outages.sortedByDescending { it.startAt }.take(20),
            at = points.lastOrNull()?.first ?: 0,
        )
    }

    private companion object {
        const val OUTAGE_MISSES = 3
    }
}
