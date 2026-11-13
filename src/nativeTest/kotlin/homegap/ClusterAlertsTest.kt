package homegap

import homegap.api.ClusterMemberDto
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.api.MeshMemberDto
import homegap.api.MeshPeerDto
import homegap.api.MeshStatusDto
import homegap.reconcile.ClusterAlerts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterAlertsTest {
    private val now = 10_000_000L
    private val licensed = ClusterAlerts.License(configured = true, activated = true, rejected = false)

    private fun member(id: String, role: String? = "replica", state: String? = "streaming", lag: Long? = 0) =
        ClusterMemberDto(
            id = id,
            address = "10.77.0.x",
            vote = "voter",
            role = role,
            state = state,
            lagBytes = lag,
            timeline = 3,
            valkeyRole = "slave",
            etcdHealthy = true,
        )

    private val healthy = ClusterStatusDto(
        primary = "homelab",
        valkeyPrimary = "homelab",
        quorum = true,
        members = listOf(
            member("homelab", role = "leader", state = "running", lag = null),
            member("na1"),
            member("as1"),
        ),
    )

    private fun mesh(vararg cut: String): MeshStatusDto {
        val members = listOf("homelab", "na1", "as1").map { id ->
            val lastHandshake = if (id in cut) now - 10 * 60_000 else now - 30_000
            MeshMemberDto(id, "10.77.0.x", peers = listOf(MeshPeerDto("other", lastHandshakeAt = lastHandshake)))
        }
        return MeshStatusDto("10.77.0.0/24", members)
    }

    @Test
    fun aHealthyClusterHasNoAlerts() {
        assertEquals(emptyList(), ClusterAlerts.evaluate(healthy, mesh(), licensed, now))
    }

    @Test
    fun noMajorityComesFirstAndAnIsolatedMemberIsNamed() {
        val members = healthy.members.map {
            if (it.id == "homelab") it.copy(role = "replica", etcdHealthy = false) else it.copy(etcdHealthy = false)
        }
        val split = healthy.copy(quorum = false, primary = null, members = members)
        val alerts = ClusterAlerts.evaluate(split, mesh("homelab"), licensed, now)
        assertEquals(Health.DOWN, alerts.first().health)
        assertTrue("no majority" in alerts.first().message)
        assertTrue(alerts.any { it.message == "homelab is cut off from the rest of the cluster." })
        // Without a majority every voter's etcd is unhealthy: that alone doesn't make them isolated.
        assertTrue(alerts.none { it.message.startsWith("na1 is cut off") })
    }

    @Test
    fun lagLicenseAndTooFewNodes() {
        val lagging = healthy.copy(members = listOf(healthy.members[0], member("na1", lag = 64L * 1024 * 1024)))
        val rejected = ClusterAlerts.License(configured = true, activated = false, rejected = true)
        val alerts = ClusterAlerts.evaluate(lagging, mesh(), rejected, now)
        assertTrue(alerts.any { it.message == "na1's database is 64 MiB behind the primary." })
        assertTrue(alerts.any { "one node" in it.message })
        assertTrue(alerts.any { "rejected" in it.message })
        val worstFirst = listOf(Health.DOWN, Health.DEGRADED, Health.UNKNOWN, Health.OK)
        assertEquals(alerts.sortedBy { worstFirst.indexOf(it.health) }, alerts)
    }
}
