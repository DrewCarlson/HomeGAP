package homegap.reconcile

import homegap.state.NodeState

/**
 * Each node's permanent tunnel slot. The slot picks the node's WireGuard subnet
 * (`100.89.<128 + 4*slot>.0/22`, fixed by Pangolin when the node first registers its exit node),
 * its kernel interface name in the `tunnels` container and its local port numbers. Slots are
 * stored in state, so removing or reordering nodes never moves anyone onto another node's
 * address or interface.
 */
object TunnelSlots {
    const val MAX = 32

    private const val FIRST_OCTETS = "100.89"
    private const val THIRD_OCTET_BASE = 128
    private const val THIRD_OCTETS_PER_SLOT = 4

    /**
     * Slots for [order] (configured node ids). Nodes keep their stored slot. Nodes provisioned
     * before slots existed start from their position in the config (how their subnet was
     * picked) and are corrected from the live site subnet by the tunnel domain. New nodes take
     * the lowest slot no known node (configured or awaiting decommission) holds.
     */
    fun assign(order: List<String>, states: Map<String, NodeState>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        for ((id, state) in states) {
            val stored = state.tunnelSlot ?: continue
            used += stored
            if (id in order) result[id] = stored
        }
        fun lowestFree(): Int =
            (0 until MAX).firstOrNull { it !in used } ?: error("no free tunnel slot (max $MAX nodes)")

        order.forEachIndexed { index, id ->
            val state = states[id] ?: return@forEachIndexed
            if (id in result || (state.stackHash == null && state.siteId == null)) return@forEachIndexed
            val slot = if (index !in used && index < MAX) index else lowestFree()
            result[id] = slot
            used += slot
        }
        for (id in order) {
            if (id in result) continue
            val slot = lowestFree()
            result[id] = slot
            used += slot
        }
        return result
    }

    /** Slot of a site subnet such as `100.89.136.4/30` (null outside HomeGAP's range). */
    fun fromSubnet(subnet: String?): Int? {
        val parts = subnet?.substringBefore('/')?.split('.') ?: return null
        if (parts.size != 4 || "${parts[0]}.${parts[1]}" != FIRST_OCTETS) return null
        val third = parts[2].toIntOrNull() ?: return null
        if (third < THIRD_OCTET_BASE) return null
        return ((third - THIRD_OCTET_BASE) / THIRD_OCTETS_PER_SLOT).takeIf { it < MAX }
    }

    fun subnet(slot: Int): String = "$FIRST_OCTETS.${THIRD_OCTET_BASE + slot * THIRD_OCTETS_PER_SLOT}.0/22"

    fun interfaceName(slot: Int): String = "hgnewt$slot"
}
