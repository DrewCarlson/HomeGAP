package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.format.formatBytes

/** The cluster's database layer: where the primaries are, and each member's part. */
@Immutable
data class DatabasePanelModel(
    val primary: Badge,
    val valkeyPrimary: Badge,
    /** Shown while the primary is away from the preferred member. */
    val switchBack: String?,
    val members: List<DatabaseMemberRow>,
)

@Immutable
data class DatabaseMemberRow(
    val id: String,
    val address: String,
    val vote: Badge,
    val database: Badge,
    val lag: String,
)

fun ClusterStatusDto.toPanelModel(): DatabasePanelModel {
    fun where(member: String?, what: String) = when (member) {
        null -> Badge(Health.DOWN, "none", "No member holds the $what primary right now")
        preferred -> Badge(Health.OK, member)
        else -> Badge(Health.DEGRADED, member, "Away from $preferred until it is back and caught up")
    }
    return DatabasePanelModel(
        primary = where(primary, "PostgreSQL"),
        valkeyPrimary = where(valkeyPrimary, "Valkey"),
        switchBack = switchBack,
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
                    m.role == null -> Badge(Health.DOWN, "not running")
                    m.role == "leader" -> Badge(Health.OK, "primary")
                    m.state == "streaming" -> Badge(Health.OK, "replica")
                    else -> Badge(Health.DEGRADED, "${m.role} · ${m.state}")
                },
                lag = when {
                    m.role == "leader" || m.role == null -> "—"
                    m.lagBytes == null -> "unknown"
                    else -> formatBytes(m.lagBytes!!)
                },
            )
        },
    )
}
