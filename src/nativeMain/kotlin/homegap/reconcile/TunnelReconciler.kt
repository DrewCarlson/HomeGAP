package homegap.reconcile

import homegap.clients.PangolinSite
import homegap.config.NodeConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.state.NodeState
import homegap.tunnels.TunnelFiles
import homegap.tunnels.TunnelSpec

/**
 * Tunnel domain: one Pangolin site per node representing the homelab, and one Newt connector
 * per node on the homelab (run by the `homegap tunnels` supervisor from spec files).
 */
class TunnelReconciler(private val ctx: ReconcileContext) {
    private val orgId: String get() = ctx.config.pangolin.orgId

    /**
     * Makes sure that the node has the homelab site, and returns the site when it is ready.
     * [addresses] are the addresses of the management host from the DNS records of the plan. The
     * connector gets them as pins.
     */
    suspend fun reconcile(ready: ReadyNode, slot: Int, addresses: List<String>): PangolinSite? {
        val node = ready.node
        val siteNiceId = ctx.config.siteNiceId(node.id)
        val paused = ctx.nodePaused(node.id)
        val existing = ready.api.listSites(orgId).firstOrNull { it.niceId == siteNiceId }
        val state = ctx.state.node(node.id)
        val lostCredentials = state.newtId == null || state.newtSecret == null

        val site = when {
            existing == null -> {
                addDiff(node.id, "create")
                if (paused) return null
                createSite(ready)
            }
            lostCredentials && existing.online == true && savedSpec(node.id) != null -> {
                adoptSavedSpec(node.id, existing)
                existing
            }
            lostCredentials || state.siteId != existing.siteId -> {
                addDiff(node.id, "rekey")
                if (paused) return existing
                rekey(ready, existing, state)
                existing
            }
            else -> {
                addDiff(node.id, "none")
                existing
            }
        }

        val liveSlot = adoptLiveSlot(node.id, site, slot)
        writeSpec(connectorSpec(node, liveSlot, addresses))
        return site
    }

    private suspend fun createSite(ready: ReadyNode): PangolinSite {
        val node = ready.node
        val siteNiceId = ctx.config.siteNiceId(node.id)
        val siteName = ctx.config.pangolin.siteName
        val defaults = ready.api.pickSiteDefaults(orgId)
        val site = ready.api.createNewtSite(
            orgId,
            name = if (ctx.config.cluster != null) "$siteName via ${node.id}" else siteName,
            niceId = siteNiceId,
            defaults,
        )
        ctx.state.updateNode(node.id) {
            it.copy(siteId = site.siteId, newtId = defaults.newtId, newtSecret = defaults.newtSecret)
        }
        ctx.log.change(DOMAIN, "created site '$siteNiceId' and Newt credentials", node.id)
        return site
    }

    private fun savedSpec(nodeId: String): TunnelSpec? =
        TunnelFiles.readSpecs(ctx.tunnelsDir).firstOrNull { it.nodeId == nodeId }

    /** The state has no credentials, but the spec of the connector that runs has them. No new key. */
    private suspend fun adoptSavedSpec(nodeId: String, site: PangolinSite) {
        val spec = TunnelFiles.readSpecs(ctx.tunnelsDir).first { it.nodeId == nodeId }
        ctx.state.updateNode(nodeId) {
            it.copy(siteId = site.siteId, newtId = spec.newtId, newtSecret = spec.newtSecret)
        }
        ctx.log.info(DOMAIN, "recovered Newt credentials from the connector spec", nodeId)
    }

    /** The state of the controller has no credentials: makes a new secret and keeps the site. */
    private suspend fun rekey(ready: ReadyNode, site: PangolinSite, state: NodeState) {
        val nodeId = ready.node.id
        val secret = newNewtSecret()
        val newtId = ready.api.regenerateNewtSecret(site.siteId, secret)
            ?: state.newtId
            ?: error("Pangolin did not return the site's newtId")
        ctx.state.updateNode(nodeId) { it.copy(siteId = site.siteId, newtId = newtId, newtSecret = secret) }
        ctx.log.change(DOMAIN, "re-keyed Newt credentials for site '${site.niceId}'", nodeId)
    }

    private fun connectorSpec(node: NodeConfig, slot: Int, addresses: List<String>): TunnelSpec {
        val state = ctx.state.node(node.id)
        return TunnelSpec(
            nodeId = node.id,
            endpoint = "https://${node.managementHost(ctx.config.domain)}",
            newtId = state.newtId!!,
            newtSecret = state.newtSecret!!,
            adminPort = ADMIN_PORT_BASE + slot,
            mtu = ctx.config.tunnels.mtu,
            // An interface name has 15 characters at most. The permanent slot keeps each name unique.
            nativeInterface = if (ctx.config.tunnels.native) TunnelSlots.interfaceName(slot) else null,
            addresses = addresses,
        )
    }

    /**
     * The site's subnet shows which slot Pangolin really gave this node (nodes provisioned before
     * slots were stored got theirs from their list position). Adopts it, or reports a collision
     * with another node, which kernel WireGuard cannot carry.
     */
    private suspend fun adoptLiveSlot(nodeId: String, site: PangolinSite, passSlot: Int): Int {
        // Another node's correction earlier in this pass may already have moved this one.
        val assigned = ctx.state.node(nodeId).tunnelSlot ?: passSlot
        val live = TunnelSlots.fromSubnet(site.subnet) ?: return assigned
        if (live == assigned) {
            if (!ctx.state.node(nodeId).tunnelSlotConfirmed && !ctx.dryRun) {
                ctx.state.updateNode(nodeId) { it.copy(tunnelSlotConfirmed = true) }
            }
            return assigned
        }
        val holder = ctx.state.snapshot().nodes.entries.firstOrNull { (id, state) ->
            id != nodeId && state.tunnelSlot == live
        }
        if (holder != null && holder.value.tunnelSlotConfirmed) {
            val collision = "tunnel subnet ${site.subnet} collides with node ${holder.key} (both use slot $live)"
            val remedy = "re-create one node's Pangolin database to give it a new subnet"
            ctx.log.error(
                DOMAIN,
                "$collision. Kernel WireGuard (tunnels.native) cannot carry both; $remedy.",
                nodeId,
            )
            return assigned
        }
        if (ctx.dryRun) return live
        // The holder's slot was only a guess from its list position: hand it this node's guess.
        if (holder != null) {
            ctx.state.updateNode(holder.key) { it.copy(tunnelSlot = assigned) }
            ctx.log.change(DOMAIN, "tunnel slot $live -> $assigned (provisional, swapped with $nodeId)", holder.key)
        }
        ctx.state.updateNode(nodeId) { it.copy(tunnelSlot = live, tunnelSlotConfirmed = true) }
        ctx.log.change(DOMAIN, "tunnel slot $assigned -> $live (from the live site subnet ${site.subnet})", nodeId)
        return live
    }

    private fun writeSpec(spec: TunnelSpec) {
        val path = TunnelFiles.specPath(ctx.tunnelsDir, spec.nodeId)
        val text = TunnelFiles.json.encodeToString(spec)
        if (Files.readTextOrNull(path) == text) return
        Files.mkdirs(ctx.tunnelsDir)
        Files.writeAtomic(path, text, "600".toInt(8))
        ctx.log.change(DOMAIN, "wrote Newt connector spec -> ${spec.endpoint}", spec.nodeId)
    }

    /** Removes connector specs for nodes that are no longer desired. */
    fun prune(activeNodeIds: Set<String>) {
        for (file in Files.list(ctx.tunnelsDir).filter { it.endsWith(SPEC_SUFFIX) }) {
            val id = file.removeSuffix(SPEC_SUFFIX)
            if (id in activeNodeIds) continue
            if (ctx.dryRun) {
                ctx.log.info(DOMAIN, "[dry-run] would remove Newt connector", id)
                continue
            }
            Files.delete("${ctx.tunnelsDir}/$file")
            ctx.log.change(DOMAIN, "removed Newt connector", id)
        }
    }

    private fun addDiff(nodeId: String, action: String) {
        ctx.diff.add(domain = DOMAIN, target = nodeId, key = "site:${ctx.config.siteNiceId(nodeId)}", action = action)
    }

    companion object {
        const val ADMIN_PORT_BASE = 47900

        private const val DOMAIN = "tunnels"
        private const val SPEC_SUFFIX = ".tunnel.json"
        private const val NEWT_SECRET_LENGTH = 48
        private const val NEWT_SECRET_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Returns a new secret for a Newt connector. */
        internal fun newNewtSecret(): String = Crypto.token(NEWT_SECRET_LENGTH, NEWT_SECRET_ALPHABET)
    }
}
