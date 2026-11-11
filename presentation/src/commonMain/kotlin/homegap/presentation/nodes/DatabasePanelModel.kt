package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.format.ago
import homegap.presentation.format.formatBytes

/** The cluster's database layer: where the primaries are, and each member's part. */
@Immutable
data class DatabasePanelModel(
    val primary: Badge,
    val valkeyPrimary: Badge,
    /** Whether a primary can be elected (a majority of the vote is healthy). */
    val quorum: Badge,
    /** Shown while the primary is away from the preferred member. */
    val switchBack: String?,
    /** Problems worth acting on, worst first. */
    val alerts: List<Badge>,
    val members: List<DatabaseMemberRow>,
    /** Recent changes of primary, newest first. */
    val history: List<PrimaryChangeRow>,
)

@Immutable
data class DatabaseMemberRow(
    val id: String,
    val address: String,
    val vote: Badge,
    val database: Badge,
    val valkey: Badge,
    val lag: String,
    /** "Make primary": shown for caught-up replicas. */
    val canBecomePrimary: Boolean,
)

@Immutable
data class PrimaryChangeRow(val time: String, val change: String, val cause: Badge)

fun ClusterStatusDto.toPanelModel(now: Long = 0): DatabasePanelModel {
    fun where(member: String?, what: String) = when {
        member == null -> Badge(Health.DOWN, "none", "No member holds the $what primary right now")
        // With no preferred member (the homelab only votes), the primary belongs wherever it is.
        preferred == null || member == preferred -> Badge(Health.OK, member)
        else -> Badge(Health.DEGRADED, member, "Away from $preferred until it is back and caught up")
    }
    return DatabasePanelModel(
        primary = where(primary, "PostgreSQL"),
        valkeyPrimary = where(valkeyPrimary, "Valkey"),
        quorum = when (quorum) {
            true -> Badge(Health.OK, "majority")
            false -> Badge(Health.DOWN, "no majority", "No primary can be elected until enough members are back")
            null -> Badge(Health.UNKNOWN, "unknown")
        },
        switchBack = switchBack?.let { note ->
            val due = switchBackAt
            if (due != null && due > now) "$note (due ${inMinutes(due - now)})" else note
        },
        alerts = alerts.map { Badge(it.health, it.message) },
        history = history.map { h ->
            PrimaryChangeRow(
                time = ago(h.at, now),
                change = h.from?.let { "$it → ${h.to}" } ?: h.to,
                cause = when (h.cause) {
                    "failover" -> Badge(Health.DEGRADED, "failover", "Elected by the cluster when the primary was lost")
                    "switch-back" -> Badge(Health.OK, "switch-back", "Moved back by HomeGAP after the hold")
                    "manual" -> Badge(Health.UNKNOWN, "manual", "Switched from the dashboard")
                    "maintenance" -> Badge(Health.UNKNOWN, "maintenance", "Moved off the homelab before an upgrade")
                    "initial" -> Badge(Health.OK, "first seen", "The primary when HomeGAP started recording")
                    else -> Badge(Health.UNKNOWN, h.cause)
                },
            )
        },
        members = members.map { m ->
            DatabaseMemberRow(
                id = m.id,
                address = m.address,
                vote = when (m.vote) {
                    "voter" -> Badge(Health.OK, "voter")
                    "joining" -> Badge(Health.DEGRADED, "joining")
                    "not joined" -> Badge(Health.UNKNOWN, "not joined")
                    else -> Badge(Health.UNKNOWN, m.vote)
                },
                database = when {
                    m.voteOnly -> Badge(Health.OK, "vote only", "This member only votes: it holds no database")
                    m.role == null -> Badge(Health.DOWN, "not running")
                    m.role == "leader" -> Badge(Health.OK, "primary")
                    m.state == "streaming" -> Badge(Health.OK, "replica")
                    else -> Badge(Health.DEGRADED, listOfNotNull(m.role, m.state).joinToString(" · "))
                },
                valkey = if (m.voteOnly) Badge(Health.OK, "vote only", "This member only votes: it holds no Valkey") else when (m.valkeyRole) {
                    "master" -> Badge(Health.OK, "primary")
                    "slave", "replica" -> Badge(Health.OK, "replica")
                    null -> Badge(Health.DOWN, "not answering")
                    else -> Badge(Health.UNKNOWN, m.valkeyRole!!)
                },
                lag = when {
                    m.role == "leader" || m.role == null -> "—"
                    m.lagBytes == null -> "unknown"
                    else -> formatBytes(m.lagBytes!!) + m.lagMs?.let { " · ${formatDuration(it)}" }.orEmpty()
                },
                canBecomePrimary = primary != null && m.role != "leader" && m.state == "streaming" && m.lagBytes == 0L,
            )
        },
    )
}

/** "in 4 min", "in 40 s". */
private fun inMinutes(ms: Long): String = if (ms >= 60_000) "in ${(ms + 59_999) / 60_000} min" else "in ${(ms + 999) / 1000} s"

/** "850 ms", "12 s", "4 min". */
internal fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "$ms ms"
    ms < 120_000 -> "${ms / 1000} s"
    else -> "${ms / 60_000} min"
}
