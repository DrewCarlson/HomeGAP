package homegap.provision

import homegap.api.MeshOutageDto
import homegap.api.MeshProbeDto

/**
 * Each node's mesh probe: a container of the cluster member that opens a TCP connection to every
 * other member's etcd port over the mesh once a second, and records round trips, losses and
 * outages (three or more lost probes in a row) under [STATE_DIR] on the node. A TCP connect is
 * used rather than ping because it is what the database traffic needs, and the cluster image has
 * no ping. The orchestrator reads the state each pass ([READ_CMD]) and keeps the history.
 */
object MeshProbe {
    const val SERVICE = "probe"
    /** On the node's /run (memory): readings are only kept while it runs. */
    const val STATE_DIR = "/run/homegap-probe"
    /** A port every member listens on over the mesh. */
    val PORT = ClusterTemplates.ETCD_CLIENT_PORT

    /** `id ip` per line: the other members. */
    fun targets(self: String, members: List<ClusterTemplates.Member>): String =
        members.filter { it.id != self }.joinToString("") { "${it.id} ${it.meshIp}\n" }

    /**
     * The probe, one Python process (the cluster image has Python for Patroni): each round opens
     * a non-blocking connection to every peer at once and waits up to [TIMEOUT_S] for them, so it
     * starts no processes and costs next to nothing. A refused connection still counts as a reply.
     */
    val SCRIPT = $$"""
        |# Managed by HomeGAP: probes every other mesh member over the mesh once a second.
        |import errno, os, select, socket, time
        |OUT, PORT, TIMEOUT = "/probe", $$PORT, $$TIMEOUT_S
        |targets = [l.split()[:2] for l in open("/config/probe.targets") if len(l.split()) >= 2]
        |os.makedirs(OUT, exist_ok=True)
        |for f in os.listdir(OUT):
        |    if f.endswith(".state"):
        |        os.remove(os.path.join(OUT, f))
        |st = {i: {"sent": 0, "lost": 0, "miss": 0, "start": 0} for i, _ in targets}
        |def now_ms():
        |    return int(time.time() * 1000)
        |def write(i, at, rtt):
        |    s = st[i]
        |    ongoing = s["start"] if s["miss"] >= 3 else 0
        |    tmp = os.path.join(OUT, "." + i + ".tmp")
        |    with open(tmp, "w") as f:
        |        f.write("%d %d %d %d %d\n" % (at, s["sent"], s["lost"], rtt, ongoing))
        |    os.replace(tmp, os.path.join(OUT, i + ".state"))
        |rounds = 0
        |while True:
        |    begin, at = time.monotonic(), now_ms()
        |    pending = {}
        |    for i, ip in targets:
        |        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        |        s.setblocking(False)
        |        s.connect_ex((ip, PORT))
        |        pending[s] = (i, time.monotonic())
        |    rtts = {}
        |    while pending and time.monotonic() - begin < TIMEOUT:
        |        _, ready, _ = select.select([], list(pending), [], TIMEOUT - (time.monotonic() - begin))
        |        for s in ready:
        |            i, t0 = pending.pop(s)
        |            err = s.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR)
        |            if err in (0, errno.ECONNREFUSED):
        |                rtts[i] = int((time.monotonic() - t0) * 1e6)
        |            s.close()
        |    for s in pending:
        |        s.close()
        |    for i, _ in targets:
        |        p = st[i]
        |        p["sent"] += 1
        |        if i in rtts:
        |            if p["miss"] >= 3:
        |                with open(os.path.join(OUT, "outages"), "a") as f:
        |                    f.write("%s %d %d\n" % (i, p["start"], at))
        |            p["miss"] = 0
        |        else:
        |            p["lost"] += 1
        |            p["miss"] += 1
        |            if p["miss"] == 1:
        |                p["start"] = at
        |        write(i, at, rtts.get(i, -1))
        |    rounds += 1
        |    if rounds % 300 == 0:
        |        path = os.path.join(OUT, "outages")
        |        if os.path.exists(path):
        |            lines = open(path).readlines()
        |            if len(lines) > 500:
        |                with open(path + ".tmp", "w") as f:
        |                    f.writelines(lines[-200:])
        |                os.replace(path + ".tmp", path)
        |    time.sleep(max(0.0, 1.0 - (time.monotonic() - begin)))
        |""".trimMargin()

    /** How long a probe waits for a peer: room for one lost SYN (resent after a second). */
    const val TIMEOUT_S = 1.5

    /** Prints `S <peer> <at> <sent> <lost> <rtt µs> <ongoing outage start>` and `O <peer> <start> <end>` lines. */
    const val READ_CMD = "for f in $STATE_DIR/*.state; do [ -f \"\$f\" ] && echo \"S \$(basename \"\$f\" .state) \$(cat \"\$f\")\"; done; " +
        "[ -f $STATE_DIR/outages ] && tail -n 100 $STATE_DIR/outages | sed 's/^/O /'; true"

    /** One reading of the probe to a peer. */
    data class Reading(val at: Long, val sent: Long, val lost: Long, val rttMicros: Long?, val ongoingSince: Long?)

    data class Output(val readings: Map<String, Reading>, val outages: List<Triple<String, Long, Long>>)

    fun parse(text: String): Output {
        val readings = mutableMapOf<String, Reading>()
        val outages = mutableListOf<Triple<String, Long, Long>>()
        for (line in text.lines()) {
            val f = line.trim().split(' ')
            when (f.firstOrNull()) {
                "S" -> if (f.size >= 7) {
                    val at = f[2].toLongOrNull() ?: continue
                    readings[f[1]] = Reading(
                        at, f[3].toLongOrNull() ?: 0, f[4].toLongOrNull() ?: 0,
                        f[5].toLongOrNull()?.takeIf { it >= 0 }, f[6].toLongOrNull()?.takeIf { it > 0 },
                    )
                }
                "O" -> if (f.size >= 4) {
                    val start = f[2].toLongOrNull() ?: continue
                    val end = f[3].toLongOrNull() ?: continue
                    outages += Triple(f[1], start, end)
                }
            }
        }
        return Output(readings, outages)
    }

    // ------------------------------------------------------------------ container path

    /** HAProxy's name for the container-path probe. */
    const val CONTAINER_BACKEND = "mesh-probe"
    private const val FALL = 3

    /**
     * The same probe from inside Docker's network, where Pangolin's database traffic starts:
     * HAProxy checks every other member's etcd port once a second (a TCP connect, waiting up to a
     * second), and marks one down after [FALL] misses in a row, like the host probe's outages.
     * Nothing is ever sent through it.
     */
    fun containerBackend(peers: List<ClusterTemplates.Member>): String = buildString {
        appendLine("backend $CONTAINER_BACKEND")
        appendLine("  default-server inter 1s fall $FALL rise 1")
        peers.forEach { appendLine("  server ${it.id} ${it.meshIp}:$PORT check") }
    }

    /**
     * Prints HAProxy's stats for the probe as `C <csv>` lines (header first) and its log lines
     * about probed members going down or up in the last hour as `L <line>`.
     */
    fun containerReadCmd(installDir: String): String =
        // Each pipeline may find nothing (no HAProxy yet, no transitions): never fail the script.
        "{ ${ClusterProxy.CSV_CMD} | awk -F, 'NR==1 || \$1==\"$CONTAINER_BACKEND\"' | sed 's/^/C /'; } || true; " +
            "{ (cd '$installDir/cluster' 2>/dev/null && docker compose logs --no-color --timestamps --since 65m haproxy 2>/dev/null) " +
            "| grep -E 'Server $CONTAINER_BACKEND/[^ ]+ is (UP|DOWN)' | tail -n 200 | sed 's/^/L /'; } || true"

    private val TRANSITION = Regex("""(\d{4}-\d\d-\d\dT[0-9:.]+Z).*Server $CONTAINER_BACKEND/(\S+) is (UP|DOWN)""")

    /**
     * Turns HAProxy's view into the host probe's [Output], read at [now]: `sent` counts seconds
     * (one check a second), `lost` is failed checks plus seconds spent down (HAProxy stops counting
     * failures once a member is down), and outages come from the log, starting when the first of
     * the [FALL] missed checks went out.
     */
    fun parseContainer(text: String, now: Long): Output {
        val csv = text.lines().filter { it.startsWith("C ") }.map { it.removePrefix("C ") }
        val header = csv.firstOrNull()?.removePrefix("# ")?.split(',') ?: return Output(emptyMap(), emptyList())
        val col = header.withIndex().associate { (i, n) -> n to i }
        fun List<String>.f(name: String) = col[name]?.let { getOrNull(it) }.orEmpty()
        val lead = (FALL - 1) * 1000L + 1000L
        val readings = csv.drop(1).map { it.split(',') }
            .filter { it.f("pxname") == CONTAINER_BACKEND && it.f("svname") !in setOf("FRONTEND", "BACKEND") }
            .associate { row ->
                val up = row.f("status").startsWith("UP")
                val failed = (row.f("chkfail").toLongOrNull() ?: 0) + (row.f("downtime").toLongOrNull() ?: 0)
                val since = row.f("lastchg").toLongOrNull()?.let { now - it * 1000 - lead }
                row.f("svname") to Reading(
                    at = now, sent = now / 1000, lost = failed,
                    rttMicros = if (up) row.f("check_duration").toLongOrNull()?.let { it * 1000 } else null,
                    ongoingSince = if (up) null else since,
                )
            }
        val outages = mutableListOf<Triple<String, Long, Long>>()
        val downAt = mutableMapOf<String, Long>()
        for (line in text.lines().filter { it.startsWith("L ") }) {
            val m = TRANSITION.find(line) ?: continue
            val at = runCatching { kotlin.time.Instant.parse(m.groupValues[1]).toEpochMilliseconds() }.getOrNull() ?: continue
            val peer = m.groupValues[2]
            if (m.groupValues[3] == "DOWN") downAt[peer] = at - lead
            else downAt.remove(peer)?.let { outages += Triple(peer, it, at) }
        }
        return Output(readings, outages)
    }

    /**
     * Keeps each (member, peer) probe's readings for [windowMs], so loss covers the window rather
     * than the probe's lifetime. Long-lived, one per controller and probe path.
     */
    class History(private val windowMs: Long = 3_600_000L) {
        private val readings = mutableMapOf<Pair<String, String>, ArrayDeque<Reading>>()

        /** Records [output] from [member] and returns its view of each peer. */
        fun record(member: String, output: Output, now: Long): Map<String, MeshProbeDto> =
            output.readings.mapValues { (peer, r) ->
                val q = readings.getOrPut(member to peer) { ArrayDeque() }
                // The probe (or HAProxy) restarted, so its counters went back: start over.
                if (q.lastOrNull()?.let { r.sent < it.sent || r.lost < it.lost } == true) q.clear()
                q.addLast(r)
                while (q.size > 1 && q.first().at < now - windowMs) q.removeFirst()
                val first = q.first()
                val sent = r.sent - (if (q.size > 1) first.sent else 0)
                val lost = r.lost - (if (q.size > 1) first.lost else 0)
                val past = output.outages.filter { it.first == peer && it.third >= now - windowMs }.map { MeshOutageDto(it.second, it.third) }
                val ongoing = r.ongoingSince?.let { listOf(MeshOutageDto(it, null)) }.orEmpty()
                MeshProbeDto(
                    rttMs = r.rttMicros?.let { it / 1000.0 },
                    lossPercent = if (sent > 0) lost * 100.0 / sent else 0.0,
                    sent = sent,
                    windowMs = if (q.size > 1) r.at - first.at else 0,
                    outages = (ongoing + past.sortedByDescending { it.startAt }).take(20),
                    at = r.at,
                )
            }

        /** Forgets members and peers no longer in the mesh. */
        fun retain(ids: Set<String>) {
            readings.keys.removeAll { it.first !in ids || it.second !in ids }
        }
    }
}
