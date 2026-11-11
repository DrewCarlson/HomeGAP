package homegap.reconcile

import homegap.platform.Crypto
import homegap.provision.ExitNodes

/**
 * A node's internet exit (`cluster.exit_nodes.nodes`): its own Pangolin site, created through the
 * node (so it uses the node's exit node), and a Newt connector for it on the node. Turned off, the
 * connector and the site are removed.
 */
class ExitReconciler(private val ctx: ReconcileContext) {
    private val domain = "exits"

    /** Returns the node's exit site niceId when it has one, else null. */
    /** [addresses]: the management host's, from the plan's DNS records, pinned for the connector. */
    suspend fun reconcile(ready: ReadyNode, addresses: List<String>): String? {
        val node = ready.node
        val org = ctx.config.pangolin.orgId
        val nice = ExitNodes.siteNiceId(node.id)
        val wanted = ctx.config.cluster?.exitNodes?.nodes == true
        val paused = ctx.nodePaused(node.id)
        val st = ctx.state.node(node.id)
        val site = ready.api.listSites(org).firstOrNull { it.niceId == nice }

        if (!wanted) {
            if (site == null && st.exitNewtId == null) return null
            ctx.diff.add(domain, node.id, "exit:$nice", "delete")
            if (paused || ctx.dryRun) return null
            val out = ctx.ssh.session(node).runScriptOrThrow("exit", ExitNodes.teardownScript(ctx.config.pangolin.installDir), 300)
            out.lines().filter { it.startsWith("CHANGED ") }.forEach { ctx.log.change(domain, it.removePrefix("CHANGED "), node.id) }
            if (site != null) {
                ready.api.deleteSite(site.siteId)
                ctx.log.change(domain, "deleted exit site '$nice'", node.id)
            }
            ctx.state.updateNode(node.id) { it.copy(exitSiteId = null, exitNewtId = null, exitNewtSecret = null, facts = it.facts - DEPLOYED_FACT) }
            return null
        }

        var siteId = site?.siteId
        when {
            site == null -> {
                ctx.diff.add(domain, node.id, "exit:$nice", "create")
                if (paused || ctx.dryRun) return null
                val d = ready.api.pickSiteDefaults(org)
                val created = ready.api.createNewtSite(org, ExitNodes.siteName(node.id), nice, d)
                siteId = created.siteId
                ctx.state.updateNode(node.id) { it.copy(exitSiteId = created.siteId, exitNewtId = d.newtId, exitNewtSecret = d.newtSecret) }
                ctx.log.change(domain, "created exit site '$nice'", node.id)
            }
            st.exitNewtId == null || st.exitNewtSecret == null || st.exitSiteId != site.siteId -> {
                // Credentials lost (or the site was re-created): rotate instead of re-creating it.
                ctx.diff.add(domain, node.id, "exit:$nice", "rekey")
                if (paused || ctx.dryRun) return nice
                val secret = Crypto.token(48, "abcdefghijklmnopqrstuvwxyz0123456789")
                val newtId = ready.api.regenerateNewtSecret(site.siteId, secret) ?: st.exitNewtId
                    ?: error("Pangolin did not return the exit site's newtId")
                ctx.state.updateNode(node.id) { it.copy(exitSiteId = site.siteId, exitNewtId = newtId, exitNewtSecret = secret) }
                ctx.log.change(domain, "re-keyed the exit site's connector", node.id)
            }
            else -> ctx.diff.add(domain, node.id, "exit:$nice", "none")
        }
        if (paused || ctx.dryRun || siteId == null) return nice

        val creds = ctx.state.node(node.id)
        val image = ctx.config.cluster!!.exitNodes.newtImage
        // Deployed already and connected: nothing to do (Docker restarts the connector itself).
        val pins = addresses.map { node.managementHost(ctx.config.domain) to it }
        val hash = Crypto.fnv64("$image|${creds.exitNewtId}|${creds.exitNewtSecret}" + if (pins.isEmpty()) "" else "|$pins")
        if (creds.facts[DEPLOYED_FACT] == hash && site?.online == true) return nice
        val script = ExitNodes.deployScript(ctx.config.pangolin.installDir, image, creds.exitNewtId!!, creds.exitNewtSecret!!, pins)
        val out = ctx.ssh.session(node).runScriptOrThrow("exit", script, 600)
        out.lines().filter { it.startsWith("CHANGED ") }.forEach { ctx.log.change(domain, it.removePrefix("CHANGED "), node.id) }
        if ("WAITING-FOR-DOCKER" !in out) ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (DEPLOYED_FACT to hash)) }
        return nice
    }

    companion object {
        /** Hash of what the node's exit connector was last deployed with. */
        const val DEPLOYED_FACT = "exitDeployed"
    }
}
