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

    /** The probe loop, run by bash in the cluster image. */
    val SCRIPT = $$"""
        |#!/bin/bash
        |# Managed by HomeGAP: probes every other mesh member once a second.
        |OUT=/probe
        |PORT=$$PORT
        |mkdir -p "$OUT"
        |probe() {
        |  local id=$1 ip=$2 sent=0 lost=0 miss=0 start=0 t rtt
        |  while :; do
        |    t=$(date +%s%3N)
        |    # Prints the connect time in microseconds; nothing when it timed out (refused still answers).
        |    rtt=$(timeout 2 bash -c 's=$(date +%s%N); true 3<>"/dev/tcp/$0/$1" 2>/dev/null; echo $(( ($(date +%s%N) - s) / 1000 ))' "$ip" "$PORT" 2>/dev/null)
        |    sent=$((sent + 1))
        |    if [ -n "$rtt" ]; then
        |      [ "$miss" -ge 3 ] && echo "$id $start $t" >> "$OUT/outages"
        |      miss=0
        |    else
        |      lost=$((lost + 1)); miss=$((miss + 1)); [ "$miss" -eq 1 ] && start=$t; rtt=-1
        |    fi
        |    ongoing=0; [ "$miss" -ge 3 ] && ongoing=$start
        |    printf '%s %s %s %s %s\n' "$t" "$sent" "$lost" "$rtt" "$ongoing" > "$OUT/.$id.tmp" && mv -f "$OUT/.$id.tmp" "$OUT/$id.state"
        |    sleep 1
        |  done
        |}
        |rm -f "$OUT"/*.state
        |while read -r id ip; do [ -n "$id" ] && probe "$id" "$ip" & done < /config/probe.targets
        |while :; do
        |  sleep 300
        |  f="$OUT/outages"
        |  if [ -f "$f" ] && [ "$(wc -l < "$f")" -gt 500 ]; then tail -n 200 "$f" > "$f.tmp" && mv -f "$f.tmp" "$f"; fi
        |done
        |""".trimMargin()

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

    /**
     * Keeps each (member, peer) probe's readings for [windowMs], so loss covers the window rather
     * than the probe's lifetime. Long-lived, one per controller.
     */
    class History(private val windowMs: Long = 3_600_000L) {
        private val readings = mutableMapOf<Pair<String, String>, ArrayDeque<Reading>>()

        /** Records [output] from [member] and returns its view of each peer. */
        fun record(member: String, output: Output, now: Long): Map<String, MeshProbeDto> =
            output.readings.mapValues { (peer, r) ->
                val q = readings.getOrPut(member to peer) { ArrayDeque() }
                // The probe restarted (its counters went back): start over.
                if (q.lastOrNull()?.let { r.sent < it.sent } == true) q.clear()
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
