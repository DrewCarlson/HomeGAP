package homegap.reconcile

import homegap.clients.PangolinSite
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.tunnels.TunnelFiles
import homegap.tunnels.TunnelSpec

/**
 * Tunnel domain: one Pangolin site per node representing the homelab, and one Newt connector
 * per node on the homelab (run by the `homegap tunnels` supervisor from spec files).
 */
class TunnelReconciler(private val ctx: ReconcileContext) {
    private val domain = "tunnels"

    /** Ensures the homelab site exists on the node; returns its niceId when ready. */
    suspend fun reconcile(ready: ReadyNode, slot: Int): PangolinSite? {
        val node = ready.node
        val org = ctx.config.pangolin.orgId
        val siteNice = ctx.config.pangolin.siteName
        val endpoint = "https://${node.managementHost(ctx.config.domain)}"
        val paused = ctx.nodePaused(node.id)

        val sites = ready.api.listSites(org)
        var site = sites.firstOrNull { it.niceId == siteNice }
        var st = ctx.state.node(node.id)

        if (site == null) {
            ctx.diff.add(domain, node.id, "site:$siteNice", "create")
            if (paused) return null
            val d = ready.api.pickSiteDefaults(org)
            site = ready.api.createNewtSite(org, ctx.config.pangolin.siteName, siteNice, d)
            ctx.state.updateNode(node.id) { it.copy(siteId = site.siteId, newtId = d.newtId, newtSecret = d.newtSecret) }
            ctx.log.change(domain, "created site '$siteNice' and Newt credentials", node.id)
        } else if ((st.newtId == null || st.newtSecret == null) && site.online == true &&
            TunnelFiles.readSpecs(ctx.tunnelsDir).firstOrNull { it.nodeId == node.id } != null
        ) {
            // State cache lost but the running connector's spec survived: adopt it, no re-key.
            val spec = TunnelFiles.readSpecs(ctx.tunnelsDir).first { it.nodeId == node.id }
            ctx.state.updateNode(node.id) { it.copy(siteId = site.siteId, newtId = spec.newtId, newtSecret = spec.newtSecret) }
            ctx.log.info(domain, "recovered Newt credentials from the connector spec", node.id)
        } else if (st.newtId == null || st.newtSecret == null || st.siteId != site.siteId) {
            ctx.diff.add(domain, node.id, "site:$siteNice", "rekey")
            if (paused) return site
            // Controller state was lost: rotate the secret instead of recreating the site.
            val secret = Crypto.token(48, "abcdefghijklmnopqrstuvwxyz0123456789")
            val newtId = ready.api.regenerateNewtSecret(site.siteId, secret) ?: st.newtId
                ?: error("Pangolin did not return the site's newtId")
            ctx.state.updateNode(node.id) { it.copy(siteId = site.siteId, newtId = newtId, newtSecret = secret) }
            ctx.log.change(domain, "re-keyed Newt credentials for site '$siteNice'", node.id)
        } else {
            ctx.diff.add(domain, node.id, "site:$siteNice", "none")
        }

        val liveSlot = adoptLiveSlot(node.id, site, slot)
        st = ctx.state.node(node.id)
        val spec = TunnelSpec(
            nodeId = node.id,
            endpoint = endpoint,
            newtId = st.newtId!!,
            newtSecret = st.newtSecret!!,
            adminPort = ADMIN_PORT_BASE + liveSlot,
            mtu = ctx.config.tunnels.mtu,
            // Interface names are limited to 15 characters; the permanent slot keeps them unique.
            nativeInterface = if (ctx.config.tunnels.native) TunnelSlots.interfaceName(liveSlot) else null,
        )
        writeSpec(spec)
        return site
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
            if (!ctx.state.node(nodeId).tunnelSlotConfirmed && !ctx.dryRun) ctx.state.updateNode(nodeId) { it.copy(tunnelSlotConfirmed = true) }
            return assigned
        }
        val holder = ctx.state.snapshot().nodes.entries.firstOrNull { (id, s) -> id != nodeId && s.tunnelSlot == live }
        if (holder != null && holder.value.tunnelSlotConfirmed) {
            ctx.log.error(
                domain,
                "tunnel subnet ${site.subnet} collides with node ${holder.key} (both use slot $live). Kernel WireGuard " +
                    "(tunnels.native) cannot carry both; re-create one node's Pangolin database to give it a new subnet.",
                nodeId,
            )
            return assigned
        }
        if (ctx.dryRun) return live
        // The holder's slot was only a guess from its list position: hand it this node's guess.
        if (holder != null) {
            ctx.state.updateNode(holder.key) { it.copy(tunnelSlot = assigned) }
            ctx.log.change(domain, "tunnel slot $live -> $assigned (provisional, swapped with $nodeId)", holder.key)
        }
        ctx.state.updateNode(nodeId) { it.copy(tunnelSlot = live, tunnelSlotConfirmed = true) }
        ctx.log.change(domain, "tunnel slot $assigned -> $live (from the live site subnet ${site.subnet})", nodeId)
        return live
    }

    private fun writeSpec(spec: TunnelSpec) {
        val path = TunnelFiles.specPath(ctx.tunnelsDir, spec.nodeId)
        val text = TunnelFiles.json.encodeToString(TunnelSpec.serializer(), spec)
        if (Files.readTextOrNull(path) == text) return
        Files.mkdirs(ctx.tunnelsDir)
        Files.writeAtomic(path, text, "600".toInt(8))
        ctx.log.change(domain, "wrote Newt connector spec -> ${spec.endpoint}", spec.nodeId)
    }

    /** Removes connector specs for nodes that are no longer desired. */
    fun prune(activeNodeIds: Set<String>) {
        for (f in Files.list(ctx.tunnelsDir).filter { it.endsWith(".tunnel.json") }) {
            val id = f.removeSuffix(".tunnel.json")
            if (id in activeNodeIds) continue
            if (ctx.dryRun) {
                ctx.log.info(domain, "[dry-run] would remove Newt connector", id)
                continue
            }
            Files.delete("${ctx.tunnelsDir}/$f")
            ctx.log.change(domain, "removed Newt connector", id)
        }
    }

    companion object {
        const val ADMIN_PORT_BASE = 47900
    }
}
