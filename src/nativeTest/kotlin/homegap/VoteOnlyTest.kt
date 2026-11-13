package homegap

import homegap.api.ClusterAlertDto
import homegap.api.ClusterMemberDto
import homegap.api.ClusterStatusDto
import homegap.config.ConfigLoader
import homegap.config.homelabVotesOnly
import homegap.reconcile.ClusterAlerts
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoteOnlyTest {
    private val license = ClusterAlerts.License(configured = true, activated = true, rejected = false)

    @Test
    fun theHomelabRoleIsDatabaseUnlessTheConfigSaysVote() {
        assertFalse(ConfigLoader.parse("$TEST_CONFIG\ncluster: {}\n").cluster!!.homelabVotesOnly)
        assertTrue(ConfigLoader.parse("$TEST_CONFIG\ncluster:\n  homelab_role: vote\n").cluster!!.homelabVotesOnly)
        val unknownRole = "$TEST_CONFIG\ncluster:\n  homelab_role: replica\n"
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(unknownRole) }
    }

    @Test
    fun aVoteOnlyHomelabIsNotCountedAsACopyOfTheDatabase() {
        val homelab = ClusterMemberDto(id = "homelab", address = "10.77.0.1", vote = "voter", voteOnly = true)
        val na1 = ClusterMemberDto(
            id = "na1",
            address = "10.77.0.10",
            vote = "voter",
            role = "leader",
            state = "running",
        )
        val as1 = ClusterMemberDto(
            id = "as1",
            address = "10.77.0.11",
            vote = "voter",
            role = "replica",
            state = "streaming",
        )
        fun alerts(vararg members: ClusterMemberDto): List<ClusterAlertDto> {
            val cluster = ClusterStatusDto(primary = "na1", preferred = null, quorum = true, members = members.toList())
            return ClusterAlerts.evaluate(cluster, mesh = null, license = license, now = 0)
        }
        assertTrue(alerts(homelab, na1).any { "no second copy" in it.message })
        assertTrue(alerts(homelab, na1, as1).none { "second" in it.message })
    }
}
