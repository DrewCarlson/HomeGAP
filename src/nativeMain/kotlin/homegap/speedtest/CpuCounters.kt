package homegap.speedtest

import homegap.api.NodeCpuDto
import kotlin.math.round

/**
 * The CPU lines of one read of `/proc/stat`: the line of all cores, and one line for each core.
 * Each line has the ticks of user, nice, system, idle, iowait, irq, softirq and steal.
 */
class CpuCounters(private val all: List<Long>, private val cores: List<List<Long>>) {
    /** The CPU use from [before] to this read. Null if no time passed. */
    fun since(before: CpuCounters): NodeCpuDto? {
        val spent = delta(all, before.all) ?: return null
        val total = spent.sum().toDouble()
        fun pct(ticks: Long) = round(ticks * 1000 / total) / 10
        val busiest = cores.zip(before.cores).mapNotNull { (now, then) ->
            delta(now, then)?.let { core -> (core.sum() - core[IDLE] - core[IOWAIT]) * 100.0 / core.sum() }
        }.maxOrNull()
        return NodeCpuDto(
            cores = cores.size.coerceAtLeast(1),
            busyPct = pct(spent.sum() - spent[IDLE] - spent[IOWAIT]),
            kernelPct = pct(spent[SYSTEM] + spent[IRQ] + spent[SOFTIRQ]),
            stealPct = pct(spent[STEAL]),
            maxCorePct = busiest?.let { round(it * 10) / 10 },
        )
    }

    private fun delta(now: List<Long>, then: List<Long>): List<Long>? =
        now.zip(then) { after, before -> (after - before).coerceAtLeast(0) }.takeIf { it.sum() > 0 }

    companion object {
        private const val SYSTEM = 2
        private const val IDLE = 3
        private const val IOWAIT = 4
        private const val IRQ = 5
        private const val SOFTIRQ = 6
        private const val STEAL = 7
        private const val TICK_COUNT = 8

        /** Reads the `cpu` lines of [stat], the text of `/proc/stat`. Null if the line of all cores is not there. */
        fun parse(stat: String): CpuCounters? {
            var all: List<Long>? = null
            val cores = mutableListOf<List<Long>>()
            for (line in stat.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (!fields[0].startsWith("cpu")) continue
                val ticks = fields.drop(1)
                    .take(TICK_COUNT)
                    .mapNotNull { it.toLongOrNull() }
                    .takeIf { it.size == TICK_COUNT }
                    ?: continue
                if (fields[0] == "cpu") all = ticks else cores += ticks
            }
            return all?.let { CpuCounters(it, cores) }
        }
    }
}
