package homegap

import homegap.reconcile.NodeReconciler.Kind
import homegap.reconcile.RolloutPolicy
import homegap.reconcile.RolloutPolicy.Decision
import homegap.reconcile.TunnelSlots
import homegap.state.NodeState
import homegap.state.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RolloutPolicyTest {
    private val good = NodeState(lastProvisionOk = true, stackHash = "old", lastProvisionAt = 0)
    private val now = 10 * 60_000L

    /** What the policy knows about the other nodes: their saved states and the ones that update now. */
    private class Rollout(val states: Map<String, NodeState>, val updating: Set<String> = emptySet())

    private fun Rollout.decide(id: String, kind: Kind, force: Boolean = false, hash: String = "new") =
        RolloutPolicy.decide(
            nodeId = id,
            kind = kind,
            hash = hash,
            force = force,
            states = states,
            updating = updating,
            now = now,
        )

    @Test
    fun updatesOneNodeAtATime() {
        val states = mapOf("a" to good, "b" to good)
        assertEquals(Decision.Start, Rollout(states).decide("a", Kind.UPDATE))
        val wait = Rollout(states, updating = setOf("a")).decide("b", Kind.UPDATE)
        assertIs<Decision.Wait>(wait)
        assertTrue("a" in wait.reason)
    }

    @Test
    fun failedUpdateHoldsTheRollout() {
        val failure = UpdateState(targetHash = "new", status = "failed", error = "boom", at = now, rolledBack = true)
        val failed = good.copy(update = failure)
        val states = mapOf("a" to failed, "b" to good)
        // The failed node does not retry the same stack on its own...
        assertIs<Decision.Wait>(Rollout(states).decide("a", Kind.UPDATE))
        // ...and the others wait for it.
        val wait = Rollout(states).decide("b", Kind.UPDATE)
        assertIs<Decision.Wait>(wait)
        assertTrue("a failed" in wait.reason)
        // A retry (force) or a new desired stack lets the failed node go first.
        assertEquals(Decision.Start, Rollout(states).decide("a", Kind.UPDATE, force = true))
        assertEquals(Decision.Start, Rollout(states).decide("a", Kind.UPDATE, hash = "newer"))
        assertIs<Decision.Wait>(Rollout(states).decide("b", Kind.UPDATE, hash = "newer"))
    }

    @Test
    fun skippedNodeReleasesTheHold() {
        val skip = UpdateState(targetHash = "new", status = "skipped", error = "boom", at = now, rolledBack = true)
        val skipped = good.copy(update = skip)
        val states = mapOf("a" to skipped, "b" to good)
        assertIs<Decision.Wait>(Rollout(states).decide("a", Kind.UPDATE))
        assertEquals(Decision.Start, Rollout(states).decide("b", Kind.UPDATE))
    }

    @Test
    fun newNodesAndRepairsDoNotWait() {
        val failed = good.copy(update = UpdateState(targetHash = "new", status = "failed", error = null, at = now))
        val states = mapOf("a" to failed, "c" to NodeState())
        assertEquals(Decision.Start, Rollout(states, updating = setOf("b")).decide("c", Kind.FRESH))
        assertEquals(Decision.Start, Rollout(states + ("b" to good)).decide("b", Kind.REPAIR))
    }

    @Test
    fun failedFirstProvisioningBacksOff() {
        val recent = NodeState(lastProvisionOk = false, lastProvisionAt = now - 60_000)
        assertIs<Decision.Wait>(Rollout(mapOf("c" to recent)).decide("c", Kind.FRESH))
        assertEquals(Decision.Start, Rollout(mapOf("c" to recent)).decide("c", Kind.FRESH, force = true))
        val old = recent.copy(lastProvisionAt = now - RolloutPolicy.FRESH_RETRY_MS - 1)
        assertEquals(Decision.Start, Rollout(mapOf("c" to old)).decide("c", Kind.FRESH))
    }
}

class TunnelSlotsTest {
    private fun good(slot: Int? = null) = NodeState(
        stackHash = "h",
        siteId = 1,
        lastProvisionOk = true,
        tunnelSlot = slot,
    )

    @Test
    fun removingANodeDoesNotMoveTheOthers() {
        val states = mapOf("a" to good(0), "b" to good(1), "c" to good(2))
        // b is gone from the config, but its state stays until the decommission.
        // c keeps 2, and a new node d gets neither.
        val slots = TunnelSlots.assign(listOf("a", "c", "d"), states)
        assertEquals(0, slots["a"])
        assertEquals(2, slots["c"])
        assertEquals(3, slots["d"])
        // After the decommission of b (its state is gone), its slot is free again.
        val afterDecommission = TunnelSlots.assign(listOf("a", "c", "d"), states - "b")
        assertEquals(1, afterDecommission["d"])
    }

    @Test
    fun legacyNodesStartFromTheirPosition() {
        val states = mapOf("a" to good(), "c" to good(), "n" to NodeState())
        val slots = TunnelSlots.assign(listOf("a", "c", "n"), states)
        assertEquals(0, slots["a"])
        assertEquals(1, slots["c"])
        assertEquals(2, slots["n"])
    }

    @Test
    fun parsesSiteSubnets() {
        assertEquals(0, TunnelSlots.fromSubnet("100.89.128.4/30"))
        assertEquals(2, TunnelSlots.fromSubnet("100.89.136.4/30"))
        assertEquals(null, TunnelSlots.fromSubnet("10.0.0.4/30"))
        assertEquals("100.89.136.0/22", TunnelSlots.subnet(2))
    }
}
