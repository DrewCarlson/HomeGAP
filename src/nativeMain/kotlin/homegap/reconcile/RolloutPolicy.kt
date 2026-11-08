package homegap.reconcile

import homegap.reconcile.NodeReconciler.Kind
import homegap.state.NodeState

/**
 * Decides when a node that needs provisioning may start. Stack updates to healthy nodes roll
 * out one node at a time, and a failed update holds the rollout until it is retried, the
 * desired stack changes again, or the user skips that node. New nodes and repairs do not wait.
 */
object RolloutPolicy {
    /** A first provisioning that failed is retried after this long (or right away on request). */
    const val FRESH_RETRY_MS = 5 * 60_000L

    sealed interface Decision {
        data object Start : Decision
        data class Wait(val reason: String) : Decision
    }

    /**
     * @param states every node's persisted state.
     * @param updating ids of nodes with an update job in progress.
     * @param force the user requested this node's provisioning (re-provision or retry).
     */
    fun decide(
        nodeId: String,
        kind: Kind,
        hash: String,
        force: Boolean,
        states: Map<String, NodeState>,
        updating: Set<String>,
        now: Long,
    ): Decision {
        if (force || kind == Kind.REPAIR || kind == Kind.NONE) return Decision.Start
        val st = states[nodeId] ?: NodeState()
        if (kind == Kind.FRESH) {
            val last = st.lastProvisionAt
            if (st.lastProvisionOk == false && last != null && now - last < FRESH_RETRY_MS) {
                return Decision.Wait("previous attempt failed; retrying in ${(FRESH_RETRY_MS - (now - last)) / 1000 + 1}s (or re-provision now)")
            }
            return Decision.Start
        }
        // Kind.UPDATE
        st.update?.takeIf { it.targetHash == hash }?.let {
            return Decision.Wait(
                if (it.status == "failed") "update failed; retry or skip it from the dashboard"
                else "update skipped; retry it from the dashboard"
            )
        }
        val blocker = states.entries.firstOrNull { (id, s) -> id != nodeId && !s.pendingDecommission && s.update?.status == "failed" }
        if (blocker != null) return Decision.Wait("rollout held: ${blocker.key} failed to update")
        val other = updating.firstOrNull { it != nodeId }
        if (other != null) return Decision.Wait("waiting for $other to finish updating")
        return Decision.Start
    }
}
