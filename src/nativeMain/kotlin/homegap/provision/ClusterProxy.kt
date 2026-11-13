package homegap.provision

import homegap.api.CheckDto
import homegap.api.Health

/**
 * What the cluster proxy (HAProxy) of a node sees: the member that gets PostgreSQL and Valkey, and
 * how often the proxy had no member. The stats page of HAProxy listens only on the loopback of
 * the node.
 */
object ClusterProxy {
    const val STATS_PORT = 8404

    /** The stats frontend in haproxy.cfg. */
    val STATS_CONFIG = """
        |frontend stats
        |  mode http
        |  bind :$STATS_PORT
        |  stats enable
        |  stats uri /stats
        |  stats refresh 0
        |""".trimMargin()

    /** Prints the stats as CSV (on the node, as root). */
    const val CSV_CMD = "curl -fsS -m 5 'http://127.0.0.1:$STATS_PORT/stats;csv' 2>/dev/null"

    // The awk program of STATS_CMD. The header line gives the column of each field.
    private const val STATS_HEADER = $$"NR==1{for(i=1;i<=NF;i++)h[$i]=i; next}"
    private const val STATS_FORMAT = "%-18s %-10s %-10s drops=%s last-change=%ss check=%s %sms\\n"
    private const val STATS_FIELDS =
        $$"""$1, $2, $h["status"], $h["chkdown"], $h["lastchg"], $h["check_status"], $h["check_duration"]"""
    private const val STATS_ROW = $$"$2!=\"FRONTEND\"{printf \"$$STATS_FORMAT\", $$STATS_FIELDS}"

    /** A readable summary for the diagnostics bundle. */
    const val STATS_CMD = "$CSV_CMD | awk -F, '$STATS_HEADER $STATS_ROW' || echo 'stats not available'"

    /** A planned move this long before the start of an interval between two readings explains a loss in it. */
    const val PLANNED_SLACK_MS = 30_000L

    private const val CHECK_NAME = "cluster-proxy"
    private const val MS_PER_MINUTE = 60_000

    /** The proxied primaries: the proxy name in HAProxy, and the name that the check shows. */
    private val PROXIES = listOf("postgres-primary" to "database", "valkey-primary" to "valkey")

    /** The rows of a proxy that are not a server. */
    private val SUMMARY_ROWS = setOf("FRONTEND", "BACKEND")

    /** One proxied primary in the stats. */
    data class Proxy(val name: String, val upServers: List<String>, val outages: Long, val lastChangeSeconds: Long?)

    /** Parses the CSV stats of HAProxy (header line first) into the proxied primaries. */
    fun parse(csv: String): List<Proxy> {
        val stats = HaproxyStats.of(csv.lines().filter { it.isNotBlank() }) ?: return emptyList()
        return PROXIES.mapNotNull { (proxy, _) ->
            val proxyRows = stats.rows.filter { stats.field(it, "pxname") == proxy }
            val backend = proxyRows.firstOrNull { stats.field(it, "svname") == "BACKEND" } ?: return@mapNotNull null
            val upServers = proxyRows
                .filter { stats.field(it, "svname") !in SUMMARY_ROWS && stats.field(it, "status").startsWith("UP") }
                .map { stats.field(it, "svname") }
            Proxy(
                name = proxy,
                upServers = upServers,
                outages = stats.field(backend, "chkdown").toLongOrNull() ?: 0,
                lastChangeSeconds = stats.field(backend, "lastchg").toLongOrNull(),
            )
        }
    }

    /**
     * Makes the `cluster-proxy` check of the node from the stats. [history] holds the readings of
     * the last [windowMs]: the time in epoch ms, and the outage count of each proxy. A loss is an
     * increase between two readings. A decrease after a restart counts nothing. The time of a loss
     * is the time of the reading that shows it, so it is correct to one pass.
     *
     * [planned] holds the times at which HomeGAP moved a primary on purpose (a switch-back,
     * maintenance, a node update, the dashboard). The proxy has no primary for a short time then.
     * A loss within [PLANNED_SLACK_MS] of a planned move does not count against the node.
     *
     * The check is red while a primary has no reachable member. It is yellow for [recentMs] after
     * a loss that was not planned. In all other cases it is green, and it still gives the time of
     * the last loss.
     */
    fun check(
        csv: String?,
        history: MutableList<Pair<Long, Map<String, Long>>>,
        now: Long,
        planned: List<Long> = emptyList(),
        windowMs: Long = 3_600_000L,
        recentMs: Long = 5 * 60_000L,
    ): CheckDto {
        val proxies = csv?.let { parse(it) }.orEmpty()
        if (proxies.isEmpty()) return CheckDto(CHECK_NAME, Health.UNKNOWN, "stats not available")
        history.removeAll { it.first < now - windowMs }
        history += now to proxies.associate { it.name to it.outages }
        var drops = 0L
        var lastLossAt: Long? = null
        var lastPlannedAt: Long? = null
        history.zipWithNext { (before, old), (at, new) ->
            val lost = new.entries.sumOf { (name, count) -> (count - (old[name] ?: count)).coerceAtLeast(0) }
            if (lost == 0L) return@zipWithNext
            if (planned.any { it in (before - PLANNED_SLACK_MS)..at }) {
                lastPlannedAt = at
            } else {
                drops += lost
                lastLossAt = at
            }
        }
        val down = proxies.filter { it.upServers.isEmpty() }
        val label = PROXIES.toMap()
        val routes = proxies.joinToString(", ") { proxy ->
            val servers = proxy.upServers.joinToString("/").ifEmpty { "none" }
            "${label[proxy.name]} → $servers"
        }
        val last = lastLossAt
        val recent = last != null && now - last <= recentMs
        val notes = buildList {
            if (last != null) {
                val times = "$drops time${if (drops == 1L) "" else "s"}"
                val window = "${windowMs / MS_PER_MINUTE} min"
                val recovered = if (!recent && down.isEmpty()) " (recovered)" else ""
                add("lost the primary $times in the last $window, last ${minutesAgo(now - last)}$recovered")
            }
            lastPlannedAt?.let { add("planned switch ${minutesAgo(now - it)}") }
        }
        val health = when {
            down.isNotEmpty() -> Health.DOWN
            recent -> Health.DEGRADED
            else -> Health.OK
        }
        return CheckDto(CHECK_NAME, health, (listOf(routes) + notes).joinToString("; "))
    }

    private fun minutesAgo(elapsedMs: Long): String {
        val minutes = elapsedMs / MS_PER_MINUTE
        return if (minutes < 1) "just now" else "$minutes min ago"
    }
}

/** The CSV stats of HAProxy. The header line gives the name of each column. */
internal class HaproxyStats private constructor(
    private val columns: Map<String, Int>,
    val rows: List<List<String>>,
) {
    /** Returns the field [name] of [row], or an empty string if the row does not have it. */
    fun field(row: List<String>, name: String): String = columns[name]?.let { row.getOrNull(it) }.orEmpty()

    companion object {
        /** Reads [lines], with the header line first. Returns null if there is no line. */
        fun of(lines: List<String>): HaproxyStats? {
            val header = lines.firstOrNull()?.removePrefix("# ")?.split(',') ?: return null
            return HaproxyStats(
                columns = header.withIndex().associate { (index, name) -> name to index },
                rows = lines.drop(1).map { it.split(',') },
            )
        }
    }
}
