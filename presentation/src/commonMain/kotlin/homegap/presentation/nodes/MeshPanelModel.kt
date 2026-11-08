package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.api.MeshStatusDto
import homegap.presentation.common.Badge
import homegap.presentation.format.ago

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
)

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
        MeshMemberRow(member.id, member.address, status, member.error, peers)
    }
    return MeshPanelModel(subnet, rows)
}
