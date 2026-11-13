package homegap.reconcile

import homegap.clients.PangolinSite
import homegap.config.ExitNodesConfig
import homegap.platform.Crypto
import homegap.provision.ExitNodes
import homegap.state.NodeState

/**
 * A node's internet exit (`cluster.exit_nodes.nodes`): its own Pangolin site, created through the
 * node (so it uses the node's exit node), and a Newt connector for it on the node. If the config
 * turns the exit off, the reconciler removes the connector and the site.
 */
class ExitReconciler(private val ctx: ReconcileContext) {
    private val orgId: String get() = ctx.config.pangolin.orgId
    private val installDir: String get() = ctx.config.pangolin.installDir

    /**
     * Returns the niceId of the exit site of the node, or null if the node has none. [addresses]
     * are the addresses of the management host from the DNS records of the plan. The connector
     * gets them as pins.
     */
    suspend fun reconcile(ready: ReadyNode, addresses: List<String>): String? {
        val node = ready.node
        val niceId = ExitNodes.siteNiceId(node.id)
        val exitNodes = ctx.config.cluster?.exitNodes?.takeIf { it.nodes }
        val readOnly = ctx.nodePaused(node.id) || ctx.dryRun
        val state = ctx.state.node(node.id)
        val site = ready.api.listSites(orgId).firstOrNull { it.niceId == niceId }

        if (exitNodes == null) {
            if (site != null || state.exitNewtId != null) {
                remove(ready, site, readOnly)
            }
            return null
        }

        when {
            site == null -> {
                addDiff(node.id, "create")
                if (readOnly) return null
                createSite(ready)
            }
            state.exitNewtId == null || state.exitNewtSecret == null || state.exitSiteId != site.siteId -> {
                // Credentials lost (or the site was re-created): rotate instead of re-creating it.
                addDiff(node.id, "rekey")
                if (readOnly) return niceId
                rekey(ready, site, state)
            }
            else -> addDiff(node.id, "none")
        }
        if (readOnly) return niceId

        deploy(ready, exitNodes, addresses, connected = site?.online == true)
        return niceId
    }

    private suspend fun remove(ready: ReadyNode, site: PangolinSite?, readOnly: Boolean) {
        val node = ready.node
        val niceId = ExitNodes.siteNiceId(node.id)
        addDiff(node.id, "delete")
        if (readOnly) return
        val script = ExitNodes.teardownScript(installDir)
        val output = ctx.ssh.session(node).runScriptOrThrow("exit", script, TEARDOWN_TIMEOUT_SECONDS)
        logChanges(output, node.id)
        if (site != null) {
            ready.api.deleteSite(site.siteId)
            ctx.log.change(DOMAIN, "deleted exit site '$niceId'", node.id)
        }
        ctx.state.updateNode(node.id) {
            it.copy(exitSiteId = null, exitNewtId = null, exitNewtSecret = null, facts = it.facts - DEPLOYED_FACT)
        }
    }

    private suspend fun createSite(ready: ReadyNode) {
        val node = ready.node
        val niceId = ExitNodes.siteNiceId(node.id)
        val defaults = ready.api.pickSiteDefaults(orgId)
        val created = ready.api.createNewtSite(orgId, name = ExitNodes.siteName(node.id), niceId = niceId, defaults)
        ctx.state.updateNode(node.id) {
            it.copy(exitSiteId = created.siteId, exitNewtId = defaults.newtId, exitNewtSecret = defaults.newtSecret)
        }
        ctx.log.change(DOMAIN, "created exit site '$niceId'", node.id)
    }

    private suspend fun rekey(ready: ReadyNode, site: PangolinSite, state: NodeState) {
        val node = ready.node
        val secret = TunnelReconciler.newNewtSecret()
        val newtId = ready.api.regenerateNewtSecret(site.siteId, secret)
            ?: state.exitNewtId
            ?: error("Pangolin did not return the exit site's newtId")
        ctx.state.updateNode(node.id) {
            it.copy(exitSiteId = site.siteId, exitNewtId = newtId, exitNewtSecret = secret)
        }
        ctx.log.change(DOMAIN, "re-keyed the exit site's connector", node.id)
    }

    /**
     * Deploys the connector project on the node. If the node has the same project and the
     * connector is [connected], there is nothing to do: Docker starts the connector again itself.
     */
    private suspend fun deploy(
        ready: ReadyNode,
        exitNodes: ExitNodesConfig,
        addresses: List<String>,
        connected: Boolean,
    ) {
        val node = ready.node
        val credentials = ctx.state.node(node.id)
        val image = exitNodes.newtImage
        val pins = addresses.map { node.managementHost(ctx.config.domain) to it }
        // The hash covers both files, so any change to the project redeploys it.
        val compose = ExitNodes.compose(image, pins)
        val env = ExitNodes.env(credentials.exitNewtId.orEmpty(), credentials.exitNewtSecret.orEmpty())
        val hash = Crypto.fnv64(compose + env)
        if (credentials.facts[DEPLOYED_FACT] == hash && connected) return

        val script = ExitNodes.deployScript(
            installDir = installDir,
            image = image,
            newtId = credentials.exitNewtId!!,
            newtSecret = credentials.exitNewtSecret!!,
            pins = pins,
        )
        val output = ctx.ssh.session(node).runScriptOrThrow("exit", script, DEPLOY_TIMEOUT_SECONDS)
        logChanges(output, node.id)
        if (WAITING_FOR_DOCKER !in output) {
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (DEPLOYED_FACT to hash)) }
        }
    }

    private fun addDiff(nodeId: String, action: String) {
        ctx.diff.add(domain = DOMAIN, target = nodeId, key = "exit:${ExitNodes.siteNiceId(nodeId)}", action = action)
    }

    private fun logChanges(output: String, nodeId: String) {
        output.lines()
            .filter { it.startsWith(CHANGED) }
            .forEach { ctx.log.change(DOMAIN, it.removePrefix(CHANGED), nodeId) }
    }

    companion object {
        /** Hash of what the node's exit connector was last deployed with. */
        const val DEPLOYED_FACT = "exitDeployed"

        private const val DOMAIN = "exits"
        private const val TEARDOWN_TIMEOUT_SECONDS = 300
        private const val DEPLOY_TIMEOUT_SECONDS = 600

        // Lines in the output of the scripts.
        private const val CHANGED = "CHANGED "
        private const val WAITING_FOR_DOCKER = "WAITING-FOR-DOCKER"
    }
}
