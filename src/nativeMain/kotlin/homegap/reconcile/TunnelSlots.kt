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

    /**
     * Slots for [order] (configured node ids). Nodes keep their stored slot. Nodes provisioned
     * before slots existed start from their position in the config (how their subnet was
     * picked) and are corrected from the live site subnet by the tunnel domain. New nodes take
     * the lowest slot no known node (configured or awaiting decommission) holds.
     */
    fun assign(order: List<String>, states: Map<String, NodeState>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        for ((id, s) in states) s.tunnelSlot?.let { used += it; if (id in order) result[id] = it }
        fun lowestFree(): Int = (0 until MAX).firstOrNull { it !in used } ?: error("no free tunnel slot (max $MAX nodes)")
        order.forEachIndexed { i, id ->
            val s = states[id] ?: return@forEachIndexed
            if (id in result || (s.stackHash == null && s.siteId == null)) return@forEachIndexed
            val slot = if (i !in used && i < MAX) i else lowestFree()
            result[id] = slot; used += slot
        }
        for (id in order) if (id !in result) lowestFree().also { result[id] = it; used += it }
        return result
    }

    /** Slot of a site subnet such as `100.89.136.4/30` (null outside HomeGAP's range). */
    fun fromSubnet(subnet: String?): Int? {
        val parts = subnet?.substringBefore('/')?.split('.') ?: return null
        if (parts.size != 4 || parts[0] != "100" || parts[1] != "89") return null
        val third = parts[2].toIntOrNull() ?: return null
        if (third < 128) return null
        return ((third - 128) / 4).takeIf { it < MAX }
    }

    fun subnet(slot: Int): String = "100.89.${128 + slot * 4}.0/22"
    fun interfaceName(slot: Int): String = "hgnewt$slot"
}
