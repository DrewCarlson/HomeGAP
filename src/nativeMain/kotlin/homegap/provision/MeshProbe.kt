package homegap.provision

import homegap.api.MeshOutageDto
import homegap.api.MeshProbeDto
import kotlin.time.Instant

/**
 * The mesh probe of each node: a container of the cluster member. Once a second, it opens a TCP
 * connection over the mesh to the etcd port of each other member. It records the round trips,
 * the losses and the outages (three or more lost probes in a row) under [STATE_DIR] on the node.
 * The probe uses a TCP connection and not ping, because the database traffic needs TCP and the
 * cluster image has no ping. The orchestrator reads the state in each pass ([READ_CMD]) and keeps
 * the history.
 */
object MeshProbe {
    const val SERVICE = "probe"

    /** On the /run of the node (memory). The readings stay only while the node runs. */
    const val STATE_DIR = "/run/homegap-probe"

    /** A port that each member listens on over the mesh. */
    const val PORT = ClusterTemplates.ETCD_CLIENT_PORT

    /** The other members, as one `id ip` line for each. */
    fun targets(self: String, members: List<ClusterTemplates.Member>): String =
        members.filter { it.id != self }.joinToString("") { "${it.id} ${it.meshIp}\n" }

    /**
     * The probe, as one Python process (the cluster image has Python for Patroni). In each round,
     * it opens a non-blocking connection to all the peers at the same time and waits up to
     * [TIMEOUT_S] for them. Thus it starts no processes and uses almost no CPU. A refused
     * connection also counts as an answer.
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

    /** The time that a probe waits for a peer. It has room for one lost SYN, which goes again after a second. */
    const val TIMEOUT_S = 1.5

    private const val READ_STATE = $$"[ -f \"$f\" ] && echo \"S $(basename \"$f\" .state) $(cat \"$f\")\""
    private const val READ_OUTAGES = "[ -f $STATE_DIR/outages ] && tail -n 100 $STATE_DIR/outages | sed 's/^/O /'"

    /**
     * Prints `S <peer> <at> <sent> <lost> <rtt µs> <ongoing outage start>` lines and
     * `O <peer> <start> <end>` lines.
     */
    const val READ_CMD = "for f in $STATE_DIR/*.state; do $READ_STATE; done; $READ_OUTAGES; true"

    // The number of fields in a line of READ_CMD.
    private const val STATE_FIELDS = 7
    private const val OUTAGE_FIELDS = 4

    private const val MS_PER_SECOND = 1000L
    private const val MAX_OUTAGES = 20

    /** One reading of the probe to a peer. */
    data class Reading(val at: Long, val sent: Long, val lost: Long, val rttMicros: Long?, val ongoingSince: Long?)

    data class Output(val readings: Map<String, Reading>, val outages: List<Triple<String, Long, Long>>)

    fun parse(text: String): Output {
        val readings = mutableMapOf<String, Reading>()
        val outages = mutableListOf<Triple<String, Long, Long>>()
        for (line in text.lines()) {
            val fields = line.trim().split(' ')
            when (fields.firstOrNull()) {
                "S" -> if (fields.size >= STATE_FIELDS) {
                    val at = fields[2].toLongOrNull() ?: continue
                    readings[fields[1]] = Reading(
                        at = at,
                        sent = fields[3].toLongOrNull() ?: 0,
                        lost = fields[4].toLongOrNull() ?: 0,
                        rttMicros = fields[5].toLongOrNull()?.takeIf { it >= 0 },
                        ongoingSince = fields[6].toLongOrNull()?.takeIf { it > 0 },
                    )
                }
                "O" -> if (fields.size >= OUTAGE_FIELDS) {
                    val start = fields[2].toLongOrNull() ?: continue
                    val end = fields[3].toLongOrNull() ?: continue
                    outages += Triple(fields[1], start, end)
                }
            }
        }
        return Output(readings, outages)
    }

    // ------------------------------------------------------------------ container path

    /** The name in HAProxy of the probe on the container path. */
    const val CONTAINER_BACKEND = "mesh-probe"
    private const val FALL = 3

    /** The time from the first missed check to the moment HAProxy marks the member down. */
    private const val DOWN_LEAD_MS = (FALL - 1) * MS_PER_SECOND + MS_PER_SECOND

    /**
     * The same probe from the Docker network, where the database traffic of Pangolin starts.
     * Once a second, HAProxy checks the etcd port of each other member with a TCP connection, and
     * waits up to one second. It marks a member down after [FALL] misses in a row, as the host
     * probe does for an outage. HAProxy sends no traffic through this backend.
     */
    fun containerBackend(peers: List<ClusterTemplates.Member>): String = buildString {
        appendLine("backend $CONTAINER_BACKEND")
        appendLine("  default-server inter 1s fall $FALL rise 1")
        peers.forEach { appendLine("  server ${it.id} ${it.meshIp}:$PORT check") }
    }

    /**
     * Prints the stats of HAProxy for the probe as `C <csv>` lines, with the header first. Prints
     * the log lines of HAProxy from the last hour about a probed member that goes down or up, as
     * `L <line>` lines.
     */
    fun containerReadCmd(installDir: String): String {
        val probeRows = $$"awk -F, 'NR==1 || $1==\"$$CONTAINER_BACKEND\"'"
        val stats = "${ClusterProxy.CSV_CMD} | $probeRows | sed 's/^/C /'"
        val logs = "(cd '$installDir/cluster' 2>/dev/null && " +
            "docker compose logs --no-color --timestamps --since 65m haproxy 2>/dev/null)"
        val transitions = "grep -E 'Server $CONTAINER_BACKEND/[^ ]+ is (UP|DOWN)' | tail -n 200 | sed 's/^/L /'"
        // A pipeline can find nothing (no HAProxy yet, no transitions). That must not fail the script.
        return "{ $stats; } || true; { $logs | $transitions; } || true"
    }

    private val TRANSITION = Regex("""(\d{4}-\d\d-\d\dT[0-9:.]+Z).*Server $CONTAINER_BACKEND/(\S+) is (UP|DOWN)""")

    /**
     * Converts the view of HAProxy, read at [now], into the [Output] of the host probe. `sent`
     * counts seconds, because HAProxy does one check a second. `lost` is the failed checks plus
     * the seconds that the member was down, because HAProxy does not count failures while a
     * member is down. The outages come from the log. An outage starts at the first of the [FALL]
     * missed checks.
     */
    fun parseContainer(text: String, now: Long): Output {
        val lines = text.lines()
        val csv = lines.filter { it.startsWith("C ") }.map { it.removePrefix("C ") }
        val stats = HaproxyStats.of(csv) ?: return Output(emptyMap(), emptyList())
        return Output(containerReadings(stats, now), containerOutages(lines.filter { it.startsWith("L ") }))
    }

    private fun containerReadings(stats: HaproxyStats, now: Long): Map<String, Reading> =
        stats.rows
            .filter { row ->
                stats.field(row, "pxname") == CONTAINER_BACKEND &&
                    stats.field(row, "svname") !in setOf("FRONTEND", "BACKEND")
            }
            .associate { row ->
                val up = stats.field(row, "status").startsWith("UP")
                val failedChecks = stats.field(row, "chkfail").toLongOrNull() ?: 0
                val secondsDown = stats.field(row, "downtime").toLongOrNull() ?: 0
                val downSince = stats.field(row, "lastchg").toLongOrNull()?.let { now - it * 1000 - DOWN_LEAD_MS }
                val checkMicros = stats.field(row, "check_duration").toLongOrNull()?.let { it * 1000 }
                stats.field(row, "svname") to Reading(
                    at = now,
                    sent = now / 1000,
                    lost = failedChecks + secondsDown,
                    rttMicros = if (up) checkMicros else null,
                    ongoingSince = if (up) null else downSince,
                )
            }

    private fun containerOutages(logLines: List<String>): List<Triple<String, Long, Long>> {
        val outages = mutableListOf<Triple<String, Long, Long>>()
        val downAt = mutableMapOf<String, Long>()
        for (line in logLines) {
            val match = TRANSITION.find(line) ?: continue
            val (time, peer, state) = match.destructured
            val at = runCatching { Instant.parse(time).toEpochMilliseconds() }.getOrNull() ?: continue
            if (state == "DOWN") {
                downAt[peer] = at - DOWN_LEAD_MS
            } else {
                downAt.remove(peer)?.let { outages += Triple(peer, it, at) }
            }
        }
        return outages
    }

    /**
     * Keeps the readings of each (member, peer) probe for [windowMs], so the loss covers the
     * window and not the full run of the probe. The controller keeps one instance for each probe
     * path.
     */
    class History(private val windowMs: Long = 3_600_000L) {
        private val readings = mutableMapOf<Pair<String, String>, ArrayDeque<Reading>>()

        /** Records the [output] of [member] and returns the view that the member has of each peer. */
        fun record(member: String, output: Output, now: Long): Map<String, MeshProbeDto> =
            output.readings.mapValues { (peer, reading) ->
                val kept = readings.getOrPut(member to peer) { ArrayDeque() }
                // The counters went back, because the probe (or HAProxy) started again. Start a new window.
                if (kept.lastOrNull()?.let { reading.sent < it.sent || reading.lost < it.lost } == true) {
                    kept.clear()
                }
                kept.addLast(reading)
                while (kept.size > 1 && kept.first().at < now - windowMs) {
                    kept.removeFirst()
                }
                val first = kept.first()
                val sent = reading.sent - (if (kept.size > 1) first.sent else 0)
                val lost = reading.lost - (if (kept.size > 1) first.lost else 0)
                val past = output.outages
                    .filter { it.first == peer && it.third >= now - windowMs }
                    .map { MeshOutageDto(it.second, it.third) }
                val ongoing = reading.ongoingSince?.let { listOf(MeshOutageDto(it, null)) }.orEmpty()
                MeshProbeDto(
                    rttMs = reading.rttMicros?.let { it / 1000.0 },
                    lossPercent = if (sent > 0) lost * 100.0 / sent else 0.0,
                    sent = sent,
                    windowMs = if (kept.size > 1) reading.at - first.at else 0,
                    outages = (ongoing + past.sortedByDescending { it.startAt }).take(MAX_OUTAGES),
                    at = reading.at,
                )
            }

        /** Removes the members and peers that are not in the mesh any more. */
        fun retain(ids: Set<String>) {
            readings.keys.removeAll { it.first !in ids || it.second !in ids }
        }
    }
}
