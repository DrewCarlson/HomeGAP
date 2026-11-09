package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.api.MeshProbeDto
import homegap.api.MeshStatusDto
import homegap.presentation.common.Badge
import homegap.presentation.format.ago
import homegap.presentation.format.oneDecimal
import homegap.presentation.format.plural

/** The cluster mesh: one row per member, with how recently it heard from each other member. */
@Immutable
data class MeshPanelModel(
    val subnet: String,
    val members: List<MeshMemberRow>,
)

@Immutable
data class MeshMemberRow(
    val id: String,
    val address: String,
    val status: Badge,
    val error: String?,
    val peers: List<Badge>,
    /** This member's probe of each other member from the host (empty without a probe, e.g. the homelab). */
    val probes: List<Badge> = emptyList(),
    /** The same from inside its Docker network, where Pangolin's database traffic starts. */
    val containerProbes: List<Badge> = emptyList(),
)

/** Over half a percent lost, or any outage in the window, needs a look; over 5 % is broken. */
private const val LOSS_DEGRADED = 0.5
private const val LOSS_DOWN = 5.0

private fun probeBadge(peer: String, probe: MeshProbeDto, now: Long): Badge {
    val ongoing = probe.outages.firstOrNull { it.endAt == null }
    val past = probe.outages.filter { it.endAt != null }
    val rtt = probe.rttMs?.let { if (it >= 100) "${it.toLong()} ms" else "${it.oneDecimal()} ms" } ?: "no reply"
    val window = "${(probe.windowMs / 60_000).coerceAtLeast(1)} min"
    val text = buildString {
        append("$peer · $rtt")
        if (probe.lossPercent > 0) append(" · ${probe.lossPercent.oneDecimal()}% lost")
        if (past.isNotEmpty()) append(" · ${plural(past.size, "outage")}")
    }
    val tooltip = buildList {
        add("${probe.sent} probes in the last $window")
        ongoing?.let { add("Out since ${ago(it.startAt, now)}") }
        past.take(5).forEach { o -> add("Outage ${ago(o.startAt, now)}, lasted ${((o.endAt!! - o.startAt) / 1000).coerceAtLeast(1)}s") }
    }.joinToString("\n")
    val health = when {
        ongoing != null || probe.rttMs == null || probe.lossPercent >= LOSS_DOWN -> Health.DOWN
        past.isNotEmpty() || probe.lossPercent >= LOSS_DEGRADED -> Health.DEGRADED
        else -> Health.OK
    }
    return Badge(health, text, tooltip)
}

/** WireGuard re-handshakes about every two minutes while keepalives flow. */
private const val FRESH_MS = 3 * 60_000L
private const val STALE_MS = 10 * 60_000L

fun MeshStatusDto.toPanelModel(now: Long): MeshPanelModel {
    val ids = members.map { it.id }
    val rows = members.map { member ->
        val byId = member.peers.associateBy { it.id }
        val peers = ids.filter { it != member.id }.map { other ->
            val handshake = byId[other]?.lastHandshakeAt
            when {
                member.error != null && member.peers.isEmpty() -> Badge(Health.UNKNOWN, other, "This member could not be read")
                byId[other] == null -> Badge(Health.DOWN, "$other · not configured", "Not in this member's peer list yet")
                handshake == null -> Badge(Health.DOWN, "$other · never", "No handshake yet")
                now - handshake <= FRESH_MS -> Badge(Health.OK, "$other · ${ago(handshake, now)}")
                now - handshake <= STALE_MS -> Badge(Health.DEGRADED, "$other · ${ago(handshake, now)}", "No recent handshake")
                else -> Badge(Health.DOWN, "$other · ${ago(handshake, now)}", "No handshake for over 10 minutes")
            }
        }
        val status = when {
            member.error != null -> Badge(Health.DOWN, "error")
            peers.isEmpty() -> Badge(Health.UNKNOWN, "alone")
            peers.all { it.health == Health.OK } -> Badge(Health.OK, "connected")
            peers.any { it.health == Health.OK } -> Badge(Health.DEGRADED, "partial")
            else -> Badge(Health.DOWN, "disconnected")
        }
        val others = ids.filter { it != member.id }
        val probes = others.mapNotNull { other -> byId[other]?.probe?.let { probeBadge(other, it, now) } }
        val containerProbes = others.mapNotNull { other -> byId[other]?.containerProbe?.let { probeBadge(other, it, now) } }
        MeshMemberRow(member.id, member.address, status, member.error, peers, probes, containerProbes)
    }
    return MeshPanelModel(subnet, rows)
}
