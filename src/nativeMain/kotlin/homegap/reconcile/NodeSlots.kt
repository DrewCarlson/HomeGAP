package homegap.reconcile

import homegap.state.NodeState

/**
 * The permanent slot of each node. The slot gives the node its mesh address and its exit address.
 * The state holds the slots, so a node keeps its addresses when other nodes go or change order.
 */
object NodeSlots {
    const val MAX = 32

    /**
     * Returns the slot of each node of [order], the ids of the configured nodes. A node keeps its
     * stored slot. A new node takes the lowest slot that no known node holds, and a node that
     * waits for its teardown is a known node.
     */
    fun assign(order: List<String>, states: Map<String, NodeState>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        for ((id, state) in states) {
            val stored = state.slot ?: continue
            used += stored
            if (id in order) result[id] = stored
        }
        for (id in order) {
            if (id in result) continue
            val slot = (0 until MAX).firstOrNull { it !in used } ?: error("no free slot (max $MAX nodes)")
            result[id] = slot
            used += slot
        }
        return result
    }
}
