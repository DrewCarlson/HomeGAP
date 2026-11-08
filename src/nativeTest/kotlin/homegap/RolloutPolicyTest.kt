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
