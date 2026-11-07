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
    suspend fun reconcile(ready: ReadyNode, index: Int): PangolinSite? {
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

        st = ctx.state.node(node.id)
        val spec = TunnelSpec(
            nodeId = node.id,
            endpoint = endpoint,
            newtId = st.newtId!!,
            newtSecret = st.newtSecret!!,
            adminPort = ADMIN_PORT_BASE + index,
        )
        writeSpec(spec)
        return site
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
