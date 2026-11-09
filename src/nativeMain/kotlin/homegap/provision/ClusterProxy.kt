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
     * Turns the stats into the node's `cluster-proxy` check. [history] holds earlier readings
     * (epoch ms to each proxy's outage count) and is trimmed to [windowMs]; outages counted in the
     * window make the check degraded, a primary with no reachable member makes it down.
     */
    fun check(csv: String?, history: MutableList<Pair<Long, Map<String, Long>>>, now: Long, windowMs: Long = 3_600_000L): CheckDto {
        val proxies = csv?.let { parse(it) }.orEmpty()
        if (proxies.isEmpty()) return CheckDto("cluster-proxy", Health.UNKNOWN, "stats not available")
        history.removeAll { it.first < now - windowMs }
        val counts = proxies.associate { it.name to it.outages }
        // Counters restart with HAProxy: only increases count.
        val first = history.firstOrNull()?.second
        history += now to counts
        val drops = proxies.sumOf { p -> (p.outages - (first?.get(p.name) ?: p.outages)).coerceAtLeast(0) }
        val down = proxies.filter { it.upServers.isEmpty() }
        val label = PROXIES.toMap()
        val routes = proxies.joinToString(", ") { "${label[it.name]} → ${it.upServers.joinToString("/").ifEmpty { "none" }}" }
        val dropText = if (drops > 0) "; lost the primary $drops time${if (drops == 1L) "" else "s"} in the last ${windowMs / 60_000} min" else ""
        val health = when {
            down.isNotEmpty() -> Health.DOWN
            drops > 0 -> Health.DEGRADED
            else -> Health.OK
        }
        return CheckDto("cluster-proxy", health, routes + dropText)
    }
}
