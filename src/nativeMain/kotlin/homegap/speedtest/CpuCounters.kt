package homegap.speedtest

import homegap.api.NodeCpuDto

/**
 * The CPU lines of one read of `/proc/stat`: the line of all cores, and one line for each core.
 * Each line has the ticks of user, nice, system, idle, iowait, irq, softirq and steal.
 */
class CpuCounters(private val all: List<Long>, private val cores: List<List<Long>>) {
    /** The CPU use from [before] to this read. Null if no time passed. */
    fun since(before: CpuCounters): NodeCpuDto? {
        val d = delta(all, before.all) ?: return null
        val total = d.sum().toDouble()
        fun pct(ticks: Long) = kotlin.math.round(ticks * 1000 / total) / 10
        val busiest = cores.zip(before.cores).mapNotNull { (now, then) ->
            delta(now, then)?.let { c -> (c.sum() - c[IDLE] - c[IOWAIT]) * 100.0 / c.sum() }
        }.maxOrNull()
        return NodeCpuDto(
            cores = cores.size.coerceAtLeast(1),
            busyPct = pct(d.sum() - d[IDLE] - d[IOWAIT]),
            kernelPct = pct(d[SYSTEM] + d[IRQ] + d[SOFTIRQ]),
            stealPct = pct(d[STEAL]),
            maxCorePct = busiest?.let { kotlin.math.round(it * 10) / 10 },
        )
    }

    private fun delta(now: List<Long>, then: List<Long>): List<Long>? =
        now.zip(then) { a, b -> (a - b).coerceAtLeast(0) }.takeIf { it.sum() > 0 }

    companion object {
        private const val SYSTEM = 2
        private const val IDLE = 3
        private const val IOWAIT = 4
        private const val IRQ = 5
        private const val SOFTIRQ = 6
        private const val STEAL = 7

        /** Reads the `cpu` lines of [stat], the text of `/proc/stat`. Null if the line of all cores is not there. */
        fun parse(stat: String): CpuCounters? {
            var all: List<Long>? = null
            val cores = mutableListOf<List<Long>>()
            for (line in stat.lineSequence()) {
                val p = line.trim().split(Regex("\\s+"))
                if (!p[0].startsWith("cpu")) continue
                val ticks = p.drop(1).take(8).mapNotNull { it.toLongOrNull() }.takeIf { it.size == 8 } ?: continue
                if (p[0] == "cpu") all = ticks else cores += ticks
            }
            return all?.let { CpuCounters(it, cores) }
        }
    }
}
