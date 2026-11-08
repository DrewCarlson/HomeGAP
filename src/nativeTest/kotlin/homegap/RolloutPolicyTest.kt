package homegap

import homegap.reconcile.NodeReconciler.Kind
import homegap.reconcile.RolloutPolicy
import homegap.reconcile.RolloutPolicy.Decision
import homegap.state.NodeState
import homegap.state.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RolloutPolicyTest {
    private val good = NodeState(lastProvisionOk = true, stackHash = "old", lastProvisionAt = 0)
    private val now = 10 * 60_000L

    private fun decide(id: String, kind: Kind, states: Map<String, NodeState>, updating: Set<String> = emptySet(), force: Boolean = false, hash: String = "new") =
        RolloutPolicy.decide(id, kind, hash, force, states, updating, now)

    @Test
    fun updatesOneNodeAtATime() {
        val states = mapOf("a" to good, "b" to good)
        assertEquals(Decision.Start, decide("a", Kind.UPDATE, states))
        val w = decide("b", Kind.UPDATE, states, updating = setOf("a"))
        assertIs<Decision.Wait>(w)
        assertTrue("a" in w.reason)
    }

    @Test
    fun failedUpdateHoldsTheRollout() {
        val failed = good.copy(update = UpdateState("new", "failed", "boom", now, rolledBack = true))
        val states = mapOf("a" to failed, "b" to good)
        // The failed node does not retry the same stack on its own...
        assertIs<Decision.Wait>(decide("a", Kind.UPDATE, states))
        // ...and the others wait for it.
        val w = decide("b", Kind.UPDATE, states)
        assertIs<Decision.Wait>(w)
        assertTrue("a failed" in w.reason)
        // A retry (force) or a new desired stack lets the failed node go first.
        assertEquals(Decision.Start, decide("a", Kind.UPDATE, states, force = true))
        assertEquals(Decision.Start, decide("a", Kind.UPDATE, states, hash = "newer"))
        assertIs<Decision.Wait>(decide("b", Kind.UPDATE, states, hash = "newer"))
    }

    @Test
    fun skippedNodeReleasesTheHold() {
        val skipped = good.copy(update = UpdateState("new", "skipped", "boom", now, rolledBack = true))
        val states = mapOf("a" to skipped, "b" to good)
        assertIs<Decision.Wait>(decide("a", Kind.UPDATE, states))
        assertEquals(Decision.Start, decide("b", Kind.UPDATE, states))
    }

    @Test
    fun newNodesAndRepairsDoNotWait() {
        val failed = good.copy(update = UpdateState("new", "failed", null, now))
        val states = mapOf("a" to failed, "c" to NodeState())
        assertEquals(Decision.Start, decide("c", Kind.FRESH, states, updating = setOf("b")))
        assertEquals(Decision.Start, decide("b", Kind.REPAIR, states + ("b" to good)))
    }

    @Test
    fun failedFirstProvisioningBacksOff() {
        val recent = NodeState(lastProvisionOk = false, lastProvisionAt = now - 60_000)
        assertIs<Decision.Wait>(decide("c", Kind.FRESH, mapOf("c" to recent)))
        assertEquals(Decision.Start, decide("c", Kind.FRESH, mapOf("c" to recent), force = true))
        val old = recent.copy(lastProvisionAt = now - RolloutPolicy.FRESH_RETRY_MS - 1)
        assertEquals(Decision.Start, decide("c", Kind.FRESH, mapOf("c" to old)))
    }
}

class TunnelSlotsTest {
    private fun good(slot: Int? = null) = NodeState(stackHash = "h", siteId = 1, lastProvisionOk = true, tunnelSlot = slot)

    @Test
    fun removingANodeDoesNotMoveTheOthers() {
        val states = mapOf("a" to good(0), "b" to good(1), "c" to good(2))
        // b removed from config but still known (awaiting decommission): c keeps 2, a new node d gets neither.
        val s = homegap.reconcile.TunnelSlots.assign(listOf("a", "c", "d"), states)
        assertEquals(0, s["a"]); assertEquals(2, s["c"]); assertEquals(3, s["d"])
        // Once b is decommissioned (state gone) its slot is free again.
        val s2 = homegap.reconcile.TunnelSlots.assign(listOf("a", "c", "d"), states - "b")
        assertEquals(1, s2["d"])
    }

    @Test
    fun legacyNodesStartFromTheirPosition() {
        val states = mapOf("a" to good(), "c" to good(), "n" to NodeState())
        val s = homegap.reconcile.TunnelSlots.assign(listOf("a", "c", "n"), states)
        assertEquals(0, s["a"]); assertEquals(1, s["c"]); assertEquals(2, s["n"])
    }

    @Test
    fun parsesSiteSubnets() {
        assertEquals(0, homegap.reconcile.TunnelSlots.fromSubnet("100.89.128.4/30"))
        assertEquals(2, homegap.reconcile.TunnelSlots.fromSubnet("100.89.136.4/30"))
        assertEquals(null, homegap.reconcile.TunnelSlots.fromSubnet("10.0.0.4/30"))
        assertEquals("100.89.136.0/22", homegap.reconcile.TunnelSlots.subnet(2))
    }
}
