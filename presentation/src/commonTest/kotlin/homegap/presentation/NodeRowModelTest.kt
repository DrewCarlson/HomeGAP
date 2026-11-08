package homegap.presentation

import homegap.api.Health
import homegap.api.NodeUpdateDto
import homegap.presentation.common.Badge
import homegap.presentation.nodes.toRowModel
import kotlin.test.*

class NodeRowModelTest {
    @Test
    fun summarisesAHealthyNode() {
        val row = node("a1").toRowModel(NOW)
        assertEquals("AS · vps · ubuntu-26.10", row.meta)
        assertEquals("tcp: bbr / fq", row.tcp)
        assertNull(row.edgeTcp, "unknown edge tcp is hidden")
        assertEquals("2m ago", row.provisioning.lastAt)
        assertEquals(Badge(Health.OK, "ok"), row.provisioning.badge)
        assertFalse(row.actions.canRetryUpdate)
    }

    @Test
    fun aFailedUpdateOffersRetryAndSkip() {
        val update = NodeUpdateDto("failed", "pull failed", NOW, rolledBack = true)
        val row = node("a1", update = update, waiting = "update failed; retry or skip").toRowModel(NOW)
        assertEquals(Badge(Health.DOWN, "update rolled back"), row.provisioning.badge)
        assertEquals(listOf("pull failed"), row.provisioning.errors)
        assertEquals("Pending: update failed; retry or skip", row.provisioning.pending)
        assertTrue(row.actions.canRetryUpdate)
        assertTrue(row.actions.canSkipUpdate)
    }

    @Test
    fun aRunningJobWinsAndBlocksReprovisioning() {
        val row = node("a1", job = "updating", waiting = "waiting").toRowModel(NOW)
        assertEquals(Badge(Health.DEGRADED, "updating…"), row.provisioning.badge)
        assertNull(row.provisioning.pending)
        assertFalse(row.actions.canReprovision)
    }
}
