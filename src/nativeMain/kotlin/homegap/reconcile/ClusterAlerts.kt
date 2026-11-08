package homegap.reconcile

import homegap.api.ClusterAlertDto
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.api.MeshStatusDto

/** What needs attention in the cluster, worst first. Pure: derived from one pass's views. */
object ClusterAlerts {
    /** A replica further behind than this would lose writes if it were promoted. */
    const val LAG_ALERT_BYTES = 16L * 1024 * 1024
    /** Mesh peers handshake at least every two minutes while the link is up. */
    const val HANDSHAKE_STALE_MS = 5L * 60 * 1000

    /** The cluster's EE license, as HomeGAP knows it. */
    data class License(val configured: Boolean, val activated: Boolean, val rejected: Boolean)

    fun evaluate(cluster: ClusterStatusDto, mesh: MeshStatusDto?, license: License, now: Long): List<ClusterAlertDto> {
        val out = mutableListOf<ClusterAlertDto>()
        if (cluster.quorum == false) {
            out += ClusterAlertDto(Health.DOWN, "The cluster vote has no majority: no primary can be elected, so logins and changes fail until enough members are back.")
        }
        if (cluster.primary == null && cluster.members.any { it.role != null }) {
            out += ClusterAlertDto(Health.DOWN, "There is no PostgreSQL primary right now.")
        }
        for (m in isolated(cluster, mesh, now)) {
            out += ClusterAlertDto(Health.DEGRADED, "$m is cut off from the rest of the cluster.")
        }
        for (m in cluster.members) {
            val lag = m.lagBytes ?: continue
            if (m.role != "leader" && lag > LAG_ALERT_BYTES) {
                out += ClusterAlertDto(Health.DEGRADED, "${m.id}'s database is ${lag / (1024 * 1024)} MiB behind the primary.")
            }
        }
        val nodes = cluster.members.count { it.id != cluster.preferred }
        if (nodes < 2) {
            out += ClusterAlertDto(
                Health.UNKNOWN,
                "With ${if (nodes == 0) "no nodes" else "one node"}, the cluster can't elect a primary without the ${cluster.preferred}: add a second node to keep sign-ins working while it's down.",
            )
        }
        when {
            license.rejected -> out += ClusterAlertDto(Health.DEGRADED, "The EE license was rejected: the key is bound to another Pangolin instance.")
            !license.configured -> out += ClusterAlertDto(Health.UNKNOWN, "No EE license is configured (pangolin.license_key_env): EE-only features such as wildcard resources are unavailable.")
            !license.activated -> out += ClusterAlertDto(Health.UNKNOWN, "The EE license is not activated yet.")
        }
        return out.sortedBy { severity(it.health) }
    }

    /**
     * Members that can't reach the others: their mesh couldn't be read, no peer has handshaked
     * with them recently, or (being a voter) their etcd has lost the majority.
     */
    fun isolated(cluster: ClusterStatusDto, mesh: MeshStatusDto?, now: Long): List<String> =
        cluster.members.mapNotNull { m ->
            val meshMember = mesh?.members?.firstOrNull { it.id == m.id }
            val meshCut = meshMember != null && (
                meshMember.error != null ||
                    (meshMember.peers.isNotEmpty() && meshMember.peers.none { p -> p.lastHandshakeAt?.let { now - it < HANDSHAKE_STALE_MS } == true })
                )
            val voteCut = m.vote == "voter" && m.etcdHealthy == false && cluster.quorum != false
            m.id.takeIf { meshCut || voteCut }
        }

    private fun severity(h: Health) = when (h) {
        Health.DOWN -> 0
        Health.DEGRADED -> 1
        Health.UNKNOWN -> 2
        Health.OK -> 3
    }
}
