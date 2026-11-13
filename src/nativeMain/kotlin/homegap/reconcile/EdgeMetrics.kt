package homegap.reconcile

import homegap.api.MetricPointDto
import homegap.api.MetricSeriesDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recent measurements of each edge, for the dashboard's graphs: one sample per reconcile pass,
 * kept for [capacity] samples (24 h at one a minute). In memory only, so graphs start empty after
 * a restart.
 */
class EdgeMetrics(private val capacity: Int = SAMPLES_PER_DAY) {
    private val mutex = Mutex()
    private val rings = mutableMapOf<SeriesKey, Ring>()
    private var lastBatch: Batch? = null

    private val _revision = MutableStateFlow(0L)

    /** Grows with every [record]. The event stream sends [latest] when it changes. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Adds one sample per point, all taken at [atSeconds]. */
    suspend fun record(atSeconds: Long, points: List<MetricPointDto>) {
        if (points.isEmpty()) return
        mutex.withLock {
            points.forEach { addSample(atSeconds, it) }
            lastBatch = Batch(atSeconds, points)
        }
        _revision.value++
    }

    private fun addSample(atSeconds: Long, point: MetricPointDto) {
        val ring = rings.getOrPut(SeriesKey(point.edge, point.series)) { Ring(capacity) }
        ring.add(time = atSeconds, value = point.value)
    }

    /** The samples of the last [record], or null before the first. */
    suspend fun latest(): Batch? = mutex.withLock { lastBatch }

    /** Every series taken since [sinceSeconds], optionally only [edge]'s. */
    suspend fun range(sinceSeconds: Long, edge: String? = null): List<MetricSeriesDto> = mutex.withLock {
        rings.filterKeys { edge == null || it.edge == edge }.map { (key, ring) ->
            val samples = ring.since(sinceSeconds)
            MetricSeriesDto(
                edge = key.edge,
                series = key.series,
                unit = unitOf(key.series),
                times = samples.map { it.first },
                values = samples.map { it.second },
            )
        }
    }

    /** Drops edges that are no longer configured. */
    suspend fun retain(edges: Set<String>) {
        mutex.withLock {
            rings.keys.retainAll { it.edge in edges }
        }
    }

    data class Batch(val atSeconds: Long, val points: List<MetricPointDto>)

    private data class SeriesKey(val edge: String, val series: String)

    /** A fixed-size ring of (epoch seconds, value) samples, oldest first. */
    private class Ring(private val capacity: Int) {
        private val times = LongArray(capacity)
        private val values = arrayOfNulls<Double>(capacity)
        private var start = 0
        private var size = 0

        fun add(time: Long, value: Double?) {
            val index = (start + size) % capacity
            times[index] = time
            values[index] = value
            if (size < capacity) {
                size++
            } else {
                start = (start + 1) % capacity
            }
        }

        fun since(sinceSeconds: Long): List<Pair<Long, Double?>> = (0 until size)
            .map { (start + it) % capacity }
            .filter { times[it] >= sinceSeconds }
            .map { times[it] to values[it] }
    }

    companion object {
        const val API = "api"
        const val MESH = "mesh"
        const val TUNNEL_IN = "in"
        const val TUNNEL_OUT = "out"
        const val REQUESTS = "rps"
        const val EGRESS = "egress"

        private const val SAMPLES_PER_DAY = 1440

        fun unitOf(series: String): String = when (series) {
            TUNNEL_IN, TUNNEL_OUT, EGRESS -> "Mb/s"
            REQUESTS -> "r/s"
            else -> "ms"
        }
    }
}
