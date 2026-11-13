package homegap.reconcile

import homegap.api.ClusterAlertDto
import homegap.api.ClusterMemberDto
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.api.MeshMemberDto
import homegap.api.MeshStatusDto

/** What needs attention in the cluster, worst first. Pure: derived from the views of one pass. */
object ClusterAlerts {
    /** A replica that is further behind than this loses writes if it becomes the primary. */
    const val LAG_ALERT_BYTES = 16L * 1024 * 1024

    /** Mesh peers handshake at least every two minutes while the link is up. */
    const val HANDSHAKE_STALE_MS = 5L * 60 * 1000

    private const val BYTES_IN_MIB = 1024 * 1024

    /** The cluster's EE license, as HomeGAP knows it. */
    data class License(val configured: Boolean, val activated: Boolean, val rejected: Boolean)

    fun evaluate(cluster: ClusterStatusDto, mesh: MeshStatusDto?, license: License, now: Long): List<ClusterAlertDto> {
        val alerts = mutableListOf<ClusterAlertDto>()
        if (cluster.quorum == false) {
            alerts += ClusterAlertDto(
                Health.DOWN,
                "The cluster vote has no majority: no primary can be elected, " +
                    "so logins and changes fail until enough members are back.",
            )
        }
        if (cluster.primary == null && cluster.members.any { it.role != null }) {
            alerts += ClusterAlertDto(Health.DOWN, "There is no PostgreSQL primary right now.")
        }
        for (id in isolated(cluster, mesh, now)) {
            alerts += ClusterAlertDto(Health.DEGRADED, "$id is cut off from the rest of the cluster.")
        }
        alerts += lagAlerts(cluster.members)
        alerts += listOfNotNull(sizeAlert(cluster), licenseAlert(license))
        return alerts.sortedBy { severity(it.health) }
    }

    private fun lagAlerts(members: List<ClusterMemberDto>): List<ClusterAlertDto> =
        members.mapNotNull { member ->
            val lag = member.lagBytes ?: return@mapNotNull null
            if (member.role == "leader" || lag <= LAG_ALERT_BYTES) return@mapNotNull null
            val behind = "${lag / BYTES_IN_MIB} MiB"
            ClusterAlertDto(Health.DEGRADED, "${member.id}'s database is $behind behind the primary.")
        }

    /** With fewer than two nodes, the cluster cannot lose a member and continue. */
    private fun sizeAlert(cluster: ClusterStatusDto): ClusterAlertDto? {
        val preferred = cluster.preferred
        val nodes = cluster.members.count { !it.voteOnly && it.id != preferred }
        if (nodes >= 2) return null
        val size = if (nodes == 0) "no nodes" else "one node"
        val message = when (preferred) {
            null ->
                "With $size, the database has no second copy: " +
                    "add a second node to keep sign-ins working when a node is down."
            else ->
                "With $size, the cluster can't elect a primary without the $preferred: " +
                    "add a second node to keep sign-ins working while it's down."
        }
        return ClusterAlertDto(Health.UNKNOWN, message)
    }

    private fun licenseAlert(license: License): ClusterAlertDto? = when {
        license.rejected -> ClusterAlertDto(
            Health.DEGRADED,
            "The EE license was rejected: the key is bound to another Pangolin instance.",
        )
        !license.configured -> ClusterAlertDto(
            Health.UNKNOWN,
            "No EE license is configured (pangolin.license_key_env): " +
                "EE-only features such as wildcard resources are unavailable.",
        )
        !license.activated -> ClusterAlertDto(Health.UNKNOWN, "The EE license is not activated yet.")
        else -> null
    }

    /**
     * The members that can't reach the others. HomeGAP cannot read the mesh of the member, or no peer
     * has a recent handshake with it, or it is a voter whose etcd lost the majority.
     */
    fun isolated(cluster: ClusterStatusDto, mesh: MeshStatusDto?, now: Long): List<String> =
        cluster.members.mapNotNull { member ->
            val meshMember = mesh?.members?.firstOrNull { it.id == member.id }
            val meshCut = meshMember != null && (meshMember.error != null || meshMember.hasNoRecentHandshake(now))
            val voteCut = member.vote == "voter" && member.etcdHealthy == false && cluster.quorum != false
            member.id.takeIf { meshCut || voteCut }
        }

    private fun MeshMemberDto.hasNoRecentHandshake(now: Long): Boolean =
        peers.isNotEmpty() && peers.none { peer -> peer.lastHandshakeAt?.let { now - it < HANDSHAKE_STALE_MS } == true }

    private fun severity(health: Health) = when (health) {
        Health.DOWN -> 0
        Health.DEGRADED -> 1
        Health.UNKNOWN -> 2
        Health.OK -> 3
    }
}
