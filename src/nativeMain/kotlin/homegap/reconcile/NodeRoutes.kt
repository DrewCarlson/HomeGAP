package homegap.reconcile

import homegap.acme.CertMaterial
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.node.AgentSpec
import homegap.platform.Crypto
import homegap.provision.MaintenancePage
import homegap.provision.NodeTemplates
import kotlin.io.encoding.Base64

/**
 * The files of a node that change with the services: the routes of its Traefik, the files of the
 * maintenance page, the spec of its agent, and the certificate that the node serves. No
 * container of the node starts again for them.
 */
class NodeRoutes(
    private val ctx: ReconcileContext,
    /** The certificate that each node serves for the names it terminates, or null if there is none. */
    private val certificate: CertMaterial? = null,
) {
    private val installDir: String get() = ctx.config.nodeStack.installDir

    /**
     * Writes the files of [node] for [desired] if one of them changed, or if the node had a
     * check since the last time. Traefik and the agent of the node load them with no restart.
     */
    suspend fun write(node: NodeConfig, desired: NodeDesired) {
        val nodeId = node.id
        val content = NodeTemplates.dynamicRoutes(desired)
        val maintenance = MaintenancePage.routes(desired)
        val probes = MaintenancePage.probes(desired)
        val agent = AgentSpec.of(desired).render()
        val hash = Crypto.fnv64(content + maintenance + probes + agent + certificate?.hash)
        val state = ctx.state.node(nodeId)
        val resend = state.facts[CERT_RESEND] != null && certificate != null
        val lastCheckAt = state.lastCheckAt ?: 0L
        val routesCheckedAt = state.facts[ROUTES_CHECKED_FACT]?.toLongOrNull() ?: 0L
        val due = state.facts[ROUTES_HASH_FACT] != hash || state.drift.isNotEmpty() || lastCheckAt > routesCheckedAt
        if (!due) return

        val script = routesScript(RouteFiles(content, maintenance, probes, agent), resend)
        val output = ctx.ssh.session(node).runScriptOrThrow("routes", script, ROUTES_TIMEOUT_SECONDS)
        if (output.contains("CERTIFICATE installed")) {
            val names = certificate?.names?.size
            ctx.log.change(DOMAIN, "installed the certificate of the nodes ($names names)", nodeId)
        }
        if (output.contains("CERTIFICATE removed")) {
            ctx.log.change(DOMAIN, "removed the certificate from the node: HomeGAP has none to give", nodeId)
        }
        if (output.contains("ROUTES changed")) {
            val passthrough = "${desired.sniRoutes.size} SNI passthrough"
            val health = "health=${desired.healthResourceNiceId != null}"
            ctx.log.change(DOMAIN, "updated Traefik routes ($passthrough, $health)", nodeId)
        }
        val checked = mapOf(ROUTES_HASH_FACT to hash, ROUTES_CHECKED_FACT to lastCheckAt.toString())
        ctx.state.updateNode(nodeId) { it.copy(facts = it.facts - CERT_RESEND + checked) }
    }

    /** The files of a node that change with the services: no container starts again for them. */
    private class RouteFiles(val routes: String, val maintenance: String, val probes: String, val agent: String)

    private fun routesScript(files: RouteFiles, resend: Boolean): String {
        val file = shQuote("$installDir/${NodeTemplates.DYNAMIC_ROUTES}")
        val encoded = Base64.encode(files.routes.encodeToByteArray())
        val writeMaintenance = SshSession.writeFileSnippet("$installDir/${MaintenancePage.ROUTES}", files.maintenance)
        val writeProbes = SshSession.writeFileSnippet("$installDir/${MaintenancePage.PROBES}", files.probes)
        val writeAgent = SshSession.writeFileSnippet("$installDir/${AgentSpec.PATH}", files.agent)
        val maintenanceDir = shQuote("$installDir/${MaintenancePage.DIR}")
        val writeCertificate = certificateScript(resend)
        return """
            |install -d -m 0755 "$(dirname $file)"
            |printf '%s' '$encoded' | base64 -d > $file.homegap-new
            |if ! cmp -s $file.homegap-new $file 2>/dev/null; then chmod 0644 $file.homegap-new; mv -f $file.homegap-new $file; echo 'ROUTES changed'; else rm -f $file.homegap-new; fi
            |hg_own $file
            |$writeMaintenance
            |$writeProbes
            |$writeAgent
            |hg_own $maintenanceDir
            |$writeCertificate
        """.trimMargin()
    }

    /**
     * Writes the certificate and its key, then the file that points Traefik at them. Without a
     * certificate, it removes all three, so a key from an earlier certificate does not stay on the
     * node.
     */
    private fun certificateScript(resend: Boolean): String {
        val cert = shQuote("$installDir/${NodeTemplates.CERT}")
        val key = shQuote("$installDir/${NodeTemplates.KEY}")
        val tls = shQuote("$installDir/${NodeTemplates.DYNAMIC_TLS}")
        val removeFiles = "rm -f $tls $cert $key"
        val material = certificate
            ?: return "if [ -e $cert ] || [ -e $key ] || [ -e $tls ]; then $removeFiles; echo 'CERTIFICATE removed'; fi"
        // Send again: without the old files, the node writes each one anew and Traefik reloads them.
        val removeOld = if (resend) removeFiles else ""
        val writeKey = SshSession.writeFileSnippet("$installDir/${NodeTemplates.KEY}", material.keyPem, "0600")
        val writeCert = SshSession.writeFileSnippet("$installDir/${NodeTemplates.CERT}", material.certPem, "0600")
        val tlsConfig = NodeTemplates.tlsDynamic(material.hash)
        val writeTls = SshSession.writeFileSnippet("$installDir/${NodeTemplates.DYNAMIC_TLS}", tlsConfig)
        return """
            |$removeOld
            |install -d -m 0700 "$(dirname $cert)"
            |umask 077
            |$writeKey
            |$writeCert
            |umask 022
            |chmod 0700 "$(dirname $cert)"
            |$writeTls | sed 's/^CHANGED.*/CERTIFICATE installed/'
            |hg_own "$(dirname $cert)"
            |hg_own $tls
        """.trimMargin()
    }

    companion object {
        /** A node fact: the dashboard asked to send the certificate to the node again. */
        const val CERT_RESEND = "certResend"

        private const val DOMAIN = "routes"
        private const val ROUTES_TIMEOUT_SECONDS = 60
        private const val ROUTES_HASH_FACT = "routesHash"
        private const val ROUTES_CHECKED_FACT = "routesCheckedAt"
    }
}
