package homegap.presentation

import homegap.api.ClusterAlertDto
import homegap.api.ClusterMemberDto
import homegap.api.ClusterStatusDto
import homegap.api.Health
import homegap.api.PrimaryChangeDto
import homegap.presentation.nodes.toPanelModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabasePanelModelTest {
    @Test
    fun aVoteOnlyHomelabIsNotAProblem() {
        val model = ClusterStatusDto(
            primary = "na1", valkeyPrimary = "na1", preferred = null, quorum = true,
            members = listOf(
                homegap.api.ClusterMemberDto(id = "homelab", address = "10.77.0.1", vote = "voter", voteOnly = true),
                homegap.api.ClusterMemberDto(id = "na1", address = "10.77.0.10", vote = "voter", role = "leader", state = "running", valkeyRole = "master"),
            ),
        ).toPanelModel()
        // No preferred member: the primary is fine on a node, and the homelab's empty roles are expected.
        kotlin.test.assertEquals(homegap.api.Health.OK, model.primary.health)
        kotlin.test.assertEquals("vote only", model.members.first().database.text)
        kotlin.test.assertEquals(homegap.api.Health.OK, model.members.first().valkey.health)
    }

    @Test
    fun showsTheSwitchBackCountdownHistoryAndWhoCanTakeOver() {
        val now = 1_000_000L
        val status = ClusterStatusDto(
            primary = "na1", valkeyPrimary = "na1", preferred = "homelab", quorum = true,
            switchBack = "homelab caught up; switching back in 3 min", switchBackAt = now + 150_000,
            members = listOf(
                ClusterMemberDto("homelab", "10.77.0.1", "voter", "replica", "streaming", 0, 4, "slave", true),
                ClusterMemberDto("na1", "10.77.0.10", "voter", "leader", "running", null, 4, "master", true),
                ClusterMemberDto("as1", "10.77.0.11", "voter", "replica", "streaming", 4096, 4, null, true),
            ),
            history = listOf(PrimaryChangeDto(now - 120_000, "homelab", "na1", "failover")),
            alerts = listOf(ClusterAlertDto(Health.DEGRADED, "something")),
        )
        val m = status.toPanelModel(now)
        assertEquals("homelab caught up; switching back in 3 min (due in 3 min)", m.switchBack)
        assertEquals(listOf("homelab"), m.members.filter { it.canBecomePrimary }.map { it.id }, "only caught-up replicas")
        assertEquals("not answering", m.members.single { it.id == "as1" }.valkey.text)
        assertEquals("homelab → na1", m.history.single().change)
        assertEquals("failover", m.history.single().cause.text)
        assertEquals("majority", m.quorum.text)
        assertTrue(m.alerts.single().text == "something")
    }
}
