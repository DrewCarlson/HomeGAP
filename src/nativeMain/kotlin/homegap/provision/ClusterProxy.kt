package homegap.provision

import homegap.api.CheckDto
import homegap.api.Health

/**
 * What a node's cluster proxy (HAProxy) sees: which member it sends PostgreSQL and Valkey to,
 * and how often it had none. HAProxy's stats page listens on the node's loopback only.
 */
object ClusterProxy {
    const val STATS_PORT = 8404

    /** The stats frontend added to haproxy.cfg. */
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

    /** A readable summary for the diagnostics bundle. */
    val STATS_CMD = "$CSV_CMD | awk -F, 'NR==1{for(i=1;i<=NF;i++)h[\$i]=i; next} " +
        "\$2!=\"FRONTEND\"{printf \"%-18s %-10s %-10s drops=%s last-change=%ss check=%s %sms\\n\", \$1, \$2, \$h[\"status\"], \$h[\"chkdown\"], \$h[\"lastchg\"], \$h[\"check_status\"], \$h[\"check_duration\"]}' " +
        "|| echo 'stats not available'"

    /** The proxied primaries, by HAProxy proxy name, with the name the check uses. */
    private val PROXIES = listOf("postgres-primary" to "database", "valkey-primary" to "valkey")

    /** One proxied primary in the stats. */
    data class Proxy(val name: String, val upServers: List<String>, val outages: Long, val lastChangeSeconds: Long?)

    /** Parses HAProxy's CSV stats (header line first) into the proxied primaries. */
    fun parse(csv: String): List<Proxy> {
        val lines = csv.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.removePrefix("# ")?.split(',') ?: return emptyList()
        val col = header.withIndex().associate { (i, n) -> n to i }
        fun List<String>.f(name: String) = col[name]?.let { getOrNull(it) }.orEmpty()
        val rows = lines.drop(1).map { it.split(',') }
        return PROXIES.mapNotNull { (proxy, _) ->
            val mine = rows.filter { it.f("pxname") == proxy }
            val backend = mine.firstOrNull { it.f("svname") == "BACKEND" } ?: return@mapNotNull null
            val up = mine.filter { it.f("svname") !in setOf("FRONTEND", "BACKEND") && it.f("status").startsWith("UP") }.map { it.f("svname") }
            Proxy(proxy, up, backend.f("chkdown").toLongOrNull() ?: 0, backend.f("lastchg").toLongOrNull())
        }
    }

    /**
     * Turns the stats into the node's `cluster-proxy` check. [history] holds the readings of the
     * last [windowMs] (epoch ms to each proxy's outage count). A loss is an increase between two
     * readings (a restart's reset counts nothing); its time is the reading that saw it, so to within
     * one pass. [planned] holds when HomeGAP moved a primary on purpose (a switch-back, maintenance,
     * a node update, the dashboard): the proxy briefly has no primary then, which is expected, so a
     * loss seen within [PLANNED_SLACK_MS] of one does not count against the node.
     *
     * The check is red while a primary has no reachable member, yellow for [recentMs] after an
     * unplanned loss, and green otherwise, still saying when the last loss was.
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
        if (proxies.isEmpty()) return CheckDto("cluster-proxy", Health.UNKNOWN, "stats not available")
        history.removeAll { it.first < now - windowMs }
        history += now to proxies.associate { it.name to it.outages }
        var drops = 0L
        var lastLossAt: Long? = null
        var lastPlannedAt: Long? = null
        history.zipWithNext { (before, old), (at, new) ->
            val lost = new.entries.sumOf { (name, n) -> (n - (old[name] ?: n)).coerceAtLeast(0) }
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
        val routes = proxies.joinToString(", ") { "${label[it.name]} → ${it.upServers.joinToString("/").ifEmpty { "none" }}" }
        val last = lastLossAt
        val recent = last != null && now - last <= recentMs
        val notes = buildList {
            if (last != null) {
                add("lost the primary $drops time${if (drops == 1L) "" else "s"} in the last ${windowMs / 60_000} min, last ${minutesAgo(now - last)}" +
                    if (!recent && down.isEmpty()) " (recovered)" else "")
            }
            lastPlannedAt?.let { add("planned switch ${minutesAgo(now - it)}") }
        }
        val health = when {
            down.isNotEmpty() -> Health.DOWN
            recent -> Health.DEGRADED
            else -> Health.OK
        }
        return CheckDto("cluster-proxy", health, (listOf(routes) + notes).joinToString("; "))
    }

    /** A planned move this long before a reading interval still explains a loss seen at its end (HAProxy notices within seconds). */
    const val PLANNED_SLACK_MS = 30_000L

    private fun minutesAgo(ms: Long): String = (ms / 60_000).let { if (it < 1) "just now" else "${it} min ago" }
}
