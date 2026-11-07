package homegap.reconcile

import homegap.clients.PangolinApiException
import homegap.clients.PangolinBootstrapClient
import homegap.clients.PangolinClient
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.platform.Crypto
import homegap.platform.nowMillis
import homegap.provision.CertMaterial
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.RenderedFile
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Node lifecycle domain: turns a bare SSH-reachable Ubuntu VPS into a Pangolin EE node and
 * keeps it there. Every step is idempotent so the same pass provisions and repairs drift.
 */
class NodeReconciler(private val ctx: ReconcileContext) {
    private val domain = "nodes"
    private val log get() = ctx.log
    private val dir get() = ctx.config.pangolin.installDir

    /**
     * @param force re-run every provisioning step even if nothing appears to have changed.
     * @param certs wildcard certificate material to install, if configured.
     * @return a handle to the node's Integration API, or null if the node is not ready.
     */
    suspend fun reconcile(desired: NodeDesired, index: Int, force: Boolean, certs: CertMaterial?): ReadyNode? {
        val node = desired.node
        val paused = ctx.nodePaused(node.id)
        val st = ctx.state.node(node.id)

        if (ctx.dryRun) {
            val secrets = st.toSecrets() ?: placeholderSecrets()
            val files = renderAll(node, desired, secrets, certs)
            log.info(domain, "[dry-run] would ensure stack (${files.size} files, hash ${hash(files)}) at $dir; raw ports: ${desired.rawPorts.joinToString { it.entryPoint }.ifEmpty { "none" }}", node.id)
            ctx.diff.add(domain, node.id, "stack", if (st.stackHash == hash(files)) "none" else "provision", hash(files), st.stackHash)
            return null
        }

        val ssh = ctx.ssh.session(node)
        try {
            ssh.ensureMaster()
        } catch (e: Exception) {
            log.error(domain, "unreachable over SSH: ${e.message}", node.id)
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("reachable" to "false")) }
            return null
        }

        if (paused) {
            log.info(domain, "paused: skipping provisioning and drift repair", node.id)
            return connectApi(node, ssh, index, st.apiKeyId, st.apiKey)
        }

        val secrets = ensureSecrets(node, ssh)
        val files = renderAll(node, desired, secrets, certs)
        val stackHash = hash(files)
        val dueCheck = force || st.lastCheckAt == null || nowMillis() - st.lastCheckAt > ctx.config.reconcile.nodeCheck.inWholeMilliseconds
        var drift = emptyList<String>()
        if (!force && dueCheck && st.lastProvisionOk == true && st.stackHash == stackHash) {
            drift = checkDrift(ssh, files)
            ctx.state.updateNode(node.id) { it.copy(lastCheckAt = nowMillis(), drift = drift) }
            if (drift.isNotEmpty()) log.warn(domain, "drift detected: ${drift.joinToString("; ")}", node.id)
        }
        val needsProvision = force || st.lastProvisionOk != true || st.stackHash != stackHash || drift.isNotEmpty()
        ctx.diff.add(domain, node.id, "stack", if (needsProvision) "provision" else "none", stackHash, st.stackHash)

        if (needsProvision) {
            val reason = when {
                force -> "requested"
                st.lastProvisionOk == null -> "new node"
                st.lastProvisionOk == false -> "previous attempt failed"
                st.stackHash != stackHash -> "stack changed"
                else -> "drift"
            }
            log.change(domain, "provisioning ($reason)", node.id)
            try {
                provision(node, ssh, desired, files)
                ctx.state.updateNode(node.id) {
                    it.copy(stackHash = stackHash, lastProvisionAt = nowMillis(), lastProvisionOk = true, lastProvisionError = null, lastCheckAt = nowMillis(), drift = emptyList())
                }
            } catch (e: Exception) {
                log.error(domain, "provisioning failed: ${e.message}", node.id)
                ctx.state.updateNode(node.id) { it.copy(lastProvisionAt = nowMillis(), lastProvisionOk = false, lastProvisionError = e.message?.take(2000)) }
                return null
            }
        }

        return try {
            val ready = bootstrap(node, ssh, index, secrets)
            if (needsProvision) verify(node, ssh, desired)
            ready
        } catch (e: Exception) {
            log.error(domain, "bootstrap failed: ${e.message}", node.id)
            ctx.state.updateNode(node.id) { it.copy(lastProvisionOk = false, lastProvisionError = "bootstrap: ${e.message}") }
            null
        }
    }

    private fun homegap.state.NodeState.toSecrets(): NodeSecrets? {
        if (setupToken == null || serverSecret == null || adminEmail == null || adminPassword == null) return null
        return NodeSecrets(setupToken, serverSecret, adminEmail, adminPassword, facts["crowdsecKey"] ?: "")
    }

    private fun placeholderSecrets() = NodeSecrets("<generated>", "<generated>", NodeTemplates.adminEmail(ctx.config), "<generated>", "<generated>")

    /** Node-held secrets are authoritative; the local cache is refilled from them when lost. */
    private suspend fun ensureSecrets(node: NodeConfig, ssh: SshSession): NodeSecrets {
        ctx.state.node(node.id).toSecrets()?.takeIf { it.crowdsecBouncerKey.isNotEmpty() }?.let { return it }
        val remote = ssh.runScript("cat ${shQuote("$dir/${NodeTemplates.SECRETS}")} 2>/dev/null || true", 60).stdout.trim()
        val secrets = if (remote.startsWith("{")) {
            log.info(domain, "recovered node secrets from the node", node.id)
            Json { ignoreUnknownKeys = true }.decodeFromString(NodeSecrets.serializer(), remote).let {
                if (it.crowdsecBouncerKey.isEmpty()) it.copy(crowdsecBouncerKey = Crypto.token(40)) else it
            }
        } else {
            log.change(domain, "generated node secrets (admin, server secret, setup token)", node.id)
            NodeSecrets(
                setupToken = Crypto.setupToken(),
                serverSecret = Crypto.token(48),
                adminEmail = NodeTemplates.adminEmail(ctx.config),
                adminPassword = Crypto.password(),
                crowdsecBouncerKey = Crypto.token(40),
            )
        }
        ctx.state.updateNode(node.id) {
            it.copy(
                setupToken = secrets.setupToken, serverSecret = secrets.serverSecret,
                adminEmail = secrets.adminEmail, adminPassword = secrets.adminPassword,
                facts = it.facts + ("crowdsecKey" to secrets.crowdsecBouncerKey),
            )
        }
        return secrets
    }

    fun renderAll(node: NodeConfig, desired: NodeDesired, secrets: NodeSecrets, certs: CertMaterial?): List<RenderedFile> {
        val files = NodeTemplates.staticFiles(ctx.config, node, secrets, desired.rawPorts).toMutableList()
        if (certs != null) {
            files += RenderedFile(NodeTemplates.CERT, certs.certPem, "0600")
            files += RenderedFile(NodeTemplates.KEY, certs.keyPem, "0600")
            files += RenderedFile(NodeTemplates.DYNAMIC_TLS, NodeTemplates.tlsDynamic())
        }
        return files
    }

    private fun hash(files: List<RenderedFile>): String =
        Crypto.fnv64(files.sortedBy { it.path }.joinToString("\u0000") { "${it.path}\u0001${it.mode}\u0001${it.content}" })

    // ---------------------------------------------------------------- provisioning steps

    private suspend fun provision(node: NodeConfig, ssh: SshSession, desired: NodeDesired, files: List<RenderedFile>) {
        // 1. Preflight
        val facts = preflight(node, ssh)
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + facts + ("reachable" to "true")) }

        // 2. Docker
        if (node.installDocker) ssh.runScriptOrThrow("docker", DOCKER_SCRIPT, 1200)
        ssh.runScriptOrThrow("docker-check", "docker version --format '{{.Server.Version}}' && docker compose version --short", 60)

        // 3. Firewall
        if (node.firewall) {
            ssh.runScriptOrThrow("firewall", firewallScript(node, desired), 300)
        }

        // 4 + 5. Stack files and secrets (restrictive modes), then 6. bring up and wait for health.
        val out = ssh.runScriptOrThrow("stack", stackScript(files), 1200)
        out.lines().filter { it.startsWith("CHANGED ") }.forEach { log.change(domain, "updated ${it.removePrefix("CHANGED ")}", node.id) }

        // 7. Kernel network tuning (best effort: some virtualization types lock these knobs).
        val t = ssh.runScriptOrThrow("tuning", tuningScript(), 120)
        t.lines().forEach { l ->
            when {
                l.startsWith("CHANGED ") -> log.change(domain, l.removePrefix("CHANGED "), node.id)
                l.startsWith("WARN ") -> log.warn(domain, l.removePrefix("WARN "), node.id)
            }
        }
        val tf = t.lines().filter { it.startsWith("FACT ") }.associate { it.removePrefix("FACT ").substringBefore('=') to it.substringAfter('=') }
        if (tf.isNotEmpty()) ctx.state.updateNode(node.id) { it.copy(facts = it.facts + tf) }
    }

    private fun tuningScript(): String {
        val src = shQuote("$dir/${NodeTemplates.SYSCTL}")
        val dst = "/etc/sysctl.d/99-homegap.conf"
        return if (ctx.config.pangolin.tcpTuning) """
            |modprobe tcp_bbr 2>/dev/null || true
            |if ! cmp -s $src $dst 2>/dev/null; then install -m 0644 $src $dst; echo "CHANGED installed TCP tuning ($dst)"; fi
            |sysctl -p $dst >/dev/null 2>&1 || echo "WARN some TCP tuning sysctls could not be applied (container/VPS kernel restrictions?)"
            |echo "FACT tcp_cc=$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
            |echo "FACT qdisc=$(sysctl -n net.core.default_qdisc 2>/dev/null || echo unknown)"
        """.trimMargin() else """
            |if [ -f $dst ]; then rm -f $dst; echo "CHANGED removed TCP tuning (live values stay until reboot)"; fi
            |echo "FACT tcp_cc=$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
            |echo "FACT qdisc=$(sysctl -n net.core.default_qdisc 2>/dev/null || echo unknown)"
        """.trimMargin()
    }

    private suspend fun preflight(node: NodeConfig, ssh: SshSession): Map<String, String> {
        val out = ssh.runScriptOrThrow("preflight", PREFLIGHT_SCRIPT, 120)
        val facts = out.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        val os = facts["ID"] ?: "unknown"
        val ver = facts["VERSION_ID"] ?: "unknown"
        val arch = facts["ARCH"] ?: "unknown"
        if (os != "ubuntu" || ver !in SUPPORTED_UBUNTU) {
            throw IllegalStateException("unsupported OS $os $ver (need Ubuntu ${SUPPORTED_UBUNTU.joinToString("/")})")
        }
        if (arch !in setOf("x86_64", "aarch64")) throw IllegalStateException("unsupported architecture $arch")
        if (node.os.isNotBlank() && node.os != "ubuntu-$ver") {
            throw IllegalStateException("config declares os=${node.os} but host runs ubuntu-$ver")
        }
        if (facts["SUDO"] == "no") throw IllegalStateException("user ${node.sshUser} cannot run sudo non-interactively")
        return facts.mapKeys { (k, _) -> k.lowercase() } + ("os" to "ubuntu-$ver")
    }

    private fun firewallScript(node: NodeConfig, desired: NodeDesired): String {
        val wanted = buildList {
            add("${node.sshPort}/tcp"); add("80/tcp"); add("443/tcp"); add("443/udp"); add("51820/udp"); add("21820/udp")
            desired.rawPorts.forEach { add("${it.port}/${it.protocol}") }
        }.distinct()
        return """
            |export DEBIAN_FRONTEND=noninteractive
            |command -v ufw >/dev/null || { apt-get update -q >/dev/null && apt-get install -yq ufw >/dev/null; }
            |ufw default deny incoming >/dev/null
            |ufw default allow outgoing >/dev/null
            |WANTED="${wanted.joinToString(" ")}"
            |for r in ${'$'}WANTED; do ufw allow "${'$'}r" comment homegap >/dev/null; done
            |# drop homegap rules that are no longer wanted (raw ports removed from labels)
            |ufw show added 2>/dev/null | sed -n 's/^ufw allow \([0-9]*\/[a-z]*\) comment .homegap.$/\1/p' | sort -u | while read -r r; do
            |  case " ${'$'}WANTED " in *" ${'$'}r "*) ;; *) ufw delete allow "${'$'}r" >/dev/null && echo "CHANGED ufw removed ${'$'}r";; esac
            |done
            |ufw status | grep -q '^Status: active' || { ufw --force enable >/dev/null && echo "CHANGED ufw enabled"; }
        """.trimMargin()
    }

    private fun stackScript(files: List<RenderedFile>): String = buildString {
        val d = shQuote(dir)
        appendLine("install -d -m 0755 $d $d/config $d/config/traefik $d/config/traefik/dynamic $d/config/letsencrypt $d/config/traefik/logs")
        appendLine("install -d -m 0700 $d/secrets $d/config/traefik/certs")
        appendLine("RESTART=\"\"")
        for (f in files) {
            val path = "$dir/${f.path}"
            appendLine(writeFile(path, f.content, f.mode, onChange = buildString {
                append("echo \"CHANGED ${f.path}\"")
                f.restarts?.let { append("; RESTART=\"\$RESTART $it\"") }
            }))
        }
        appendLine("cd $d")
        appendLine("docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20")
        appendLine("for s in \$(echo \$RESTART | tr ' ' '\\n' | sort -u); do docker compose restart \"\$s\" >/dev/null 2>&1 || true; done")
        appendLine(
            """
            |ok=0
            |for i in $(seq 1 120); do
            |  if curl -fsS -o /dev/null http://127.0.0.1:3000/api/v1/ 2>/dev/null; then ok=1; break; fi
            |  sleep 2
            |done
            |if [ "${'$'}ok" != 1 ]; then echo "Pangolin did not become healthy" >&2; docker compose ps >&2; docker compose logs --tail 40 pangolin >&2; exit 1; fi
            """.trimMargin()
        )
    }

    private fun writeFile(path: String, content: String, mode: String, onChange: String): String {
        val b64 = Base64.encode(content.encodeToByteArray())
        val q = shQuote(path)
        return """
            |printf '%s' '$b64' | base64 -d > $q.homegap-new
            |if ! cmp -s $q.homegap-new $q 2>/dev/null; then chmod $mode $q.homegap-new; mv -f $q.homegap-new $q; $onChange; else rm -f $q.homegap-new; chmod $mode $q; fi
        """.trimMargin()
    }

    private suspend fun checkDrift(ssh: SshSession, files: List<RenderedFile>): List<String> {
        val script = buildString {
            for (f in files) {
                val b64 = Base64.encode(f.content.encodeToByteArray())
                val q = shQuote("$dir/${f.path}")
                appendLine("printf '%s' '$b64' | base64 -d | cmp -s - $q 2>/dev/null || echo \"DRIFT file ${f.path}\"")
            }
            appendLine("cd ${shQuote(dir)} 2>/dev/null || { echo 'DRIFT install dir missing'; exit 0; }")
            appendLine("for s in pangolin gerbil traefik; do st=\$(docker inspect -f '{{.State.Status}}' \$s 2>/dev/null || echo missing); [ \"\$st\" = running ] || echo \"DRIFT container \$s \$st\"; done")
        }
        val r = ssh.runScript(script, 120)
        if (!r.ok) return listOf("check failed: ${r.stderr.trim().take(200)}")
        return r.stdout.lines().filter { it.startsWith("DRIFT ") }.map { it.removePrefix("DRIFT ") }
    }

    // ---------------------------------------------------------------- headless bootstrap

    private suspend fun connectApi(node: NodeConfig, ssh: SshSession, index: Int, keyId: String?, key: String?): ReadyNode? {
        if (keyId == null || key == null) return null
        return runCatching {
            val port = ssh.forward(3003, LOCAL_PORT_BASE + index * 10)
            ReadyNode(node, PangolinClient(ctx.localHttp, "http://127.0.0.1:$port/v1", "$keyId.$key"))
        }.getOrNull()
    }

    private suspend fun bootstrap(node: NodeConfig, ssh: SshSession, index: Int, secrets: NodeSecrets): ReadyNode {
        val org = ctx.config.pangolin.orgId
        val st = ctx.state.node(node.id)
        val apiPort = ssh.forward(3003, LOCAL_PORT_BASE + index * 10)
        val integrationUrl = "http://127.0.0.1:$apiPort/v1"

        // Fast path: the cached key still works.
        if (st.apiKeyId != null && st.apiKey != null) {
            val api = PangolinClient(ctx.localHttp, integrationUrl, "${st.apiKeyId}.${st.apiKey}")
            val ok = runCatching { api.getOrg(org) }.isSuccess
            if (ok) {
                maybeActivateLicense(node, ssh, index, secrets)
                return ReadyNode(node, api)
            }
            log.warn(domain, "cached API key rejected; minting a new one", node.id)
        }

        val dashPort = ssh.forward(3000, LOCAL_PORT_BASE + index * 10 + 5)
        val boot = PangolinBootstrapClient(ctx.localHttp, "http://127.0.0.1:$dashPort/api/v1")
        waitFor("Pangolin API") { runCatching { boot.initialSetupComplete(); true }.getOrDefault(false) }

        if (!boot.initialSetupComplete()) {
            boot.setServerAdmin(secrets.adminEmail, secrets.adminPassword, secrets.setupToken)
            log.change(domain, "created server admin ${secrets.adminEmail}", node.id)
        }
        try {
            boot.login(secrets.adminEmail, secrets.adminPassword)
        } catch (e: PangolinApiException) {
            throw IllegalStateException(
                "cannot log in as ${secrets.adminEmail} (${e.message}). The node has an admin the controller does not know; " +
                    "restore $dir/${NodeTemplates.SECRETS} or wipe the node's $dir/config/db to re-bootstrap."
            )
        }
        if (!boot.orgExists(org)) {
            boot.createOrg(org, ctx.config.pangolin.orgName)
            log.change(domain, "created org $org", node.id)
        }
        val (keyId, key) = boot.createRootApiKey("homegap-orchestrator")
        boot.setApiKeyActions(keyId, PangolinBootstrapClient.ORCHESTRATOR_ACTIONS)
        // Retire keys minted by earlier controller instances.
        runCatching {
            boot.listRootApiKeys().filter { (id, name) -> name == "homegap-orchestrator" && id != keyId }.forEach { (id, _) -> boot.deleteRootApiKey(id) }
        }
        log.change(domain, "minted orchestrator API key", node.id)
        ctx.state.updateNode(node.id) { it.copy(apiKeyId = keyId, apiKey = key) }
        maybeActivateLicense(node, ssh, index, secrets, boot)
        return ReadyNode(node, PangolinClient(ctx.localHttp, integrationUrl, "$keyId.$key"))
    }

    private suspend fun maybeActivateLicense(node: NodeConfig, ssh: SshSession, index: Int, secrets: NodeSecrets, existing: PangolinBootstrapClient? = null) {
        val ref = node.licenseKey(ctx.config.pangolin) ?: return
        if (ctx.state.node(node.id).licenseActivated) return
        val key = ref.resolve() ?: run {
            log.warn(domain, "license key ${ref.describe()} not set; EE wildcard resources will be refused", node.id)
            return
        }
        // A key the license server already rejected for this node is not retried every pass
        // (host-bound keys stay rejected); changing the key or re-provisioning clears this.
        val keyHash = Crypto.fnv64(key)
        if (ctx.state.node(node.id).facts["licenseRejected"] == keyHash) {
            log.warn(domain, "EE license was rejected for this node (see earlier error); wildcard resources unavailable", node.id)
            return
        }
        try {
            val boot = existing ?: PangolinBootstrapClient(ctx.localHttp, "http://127.0.0.1:${ssh.forward(3000, LOCAL_PORT_BASE + index * 10 + 5)}/api/v1").also {
                it.login(secrets.adminEmail, secrets.adminPassword)
            }
            boot.activateLicense(key)
            ctx.state.updateNode(node.id) { it.copy(licenseActivated = true) }
            log.change(domain, "activated Pangolin EE license", node.id)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            when {
                // Only the local "this instance already stores the key" case means success.
                msg.contains("License key already exists") ->
                    ctx.state.updateNode(node.id) { it.copy(licenseActivated = true) }
                msg.contains("different instance", ignoreCase = true) -> {
                    ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("licenseRejected" to keyHash)) }
                    log.error(
                        domain,
                        "EE license rejected: the key is already bound to another Pangolin instance. " +
                            "Personal keys are per host; give this node its own key via nodes[].license_key_env.",
                        node.id,
                    )
                }
                else -> log.error(domain, "license activation failed: $msg", node.id)
            }
        }
    }

    private suspend fun verify(node: NodeConfig, ssh: SshSession, desired: NodeDesired) {
        val r = ssh.runScript(
            """
            |cd ${shQuote(dir)}
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "CHECK gerbil-udp ok" || echo "CHECK gerbil-udp missing"
            |(ss -lun 2>/dev/null | grep -q ':21820 ') && echo "CHECK gerbil-clients-udp ok" || echo "CHECK gerbil-clients-udp missing"
            |curl -fsS -o /dev/null http://127.0.0.1:3003/v1/ && echo "CHECK integration-api ok" || echo "CHECK integration-api missing"
            """.trimMargin(), 60,
        )
        r.stdout.lines().filter { it.startsWith("CHECK ") && it.endsWith("missing") }.forEach {
            log.warn(domain, "verify: ${it.removePrefix("CHECK ")}", node.id)
        }
    }

    private suspend fun waitFor(what: String, attempts: Int = 60, check: suspend () -> Boolean) {
        repeat(attempts) {
            if (check()) return
            delay(2000)
        }
        throw IllegalStateException("$what not reachable")
    }

    /** Stops and removes the node stack (decommission). Data volumes are kept under install_dir. */
    suspend fun decommission(node: NodeConfig) {
        val ssh = ctx.ssh.session(node)
        ssh.runScriptOrThrow("decommission", "cd ${shQuote(dir)} 2>/dev/null && docker compose down --remove-orphans || true", 300)
        ctx.ssh.drop(node.id)
    }

    companion object {
        const val LOCAL_PORT_BASE = 47100
        val SUPPORTED_UBUNTU = setOf("22.04", "24.04")

        val PREFLIGHT_SCRIPT = """
            |. /etc/os-release
            |echo "ID=${'$'}ID"
            |echo "VERSION_ID=${'$'}VERSION_ID"
            |echo "CODENAME=${'$'}{VERSION_CODENAME:-}"
            |echo "ARCH=$(uname -m)"
            |echo "KERNEL=$(uname -r)"
            |echo "MEM_KB=$(awk '/MemTotal/{print ${'$'}2}' /proc/meminfo)"
            |echo "DOCKER=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)"
            |echo "SUDO=yes"
            |command -v curl >/dev/null || { export DEBIAN_FRONTEND=noninteractive; apt-get update -q >/dev/null && apt-get install -yq curl ca-certificates >/dev/null; }
        """.trimMargin()

        val DOCKER_SCRIPT = """
            |if ! command -v docker >/dev/null 2>&1; then
            |  export DEBIAN_FRONTEND=noninteractive
            |  apt-get update -q
            |  apt-get install -yq ca-certificates curl
            |  install -m 0755 -d /etc/apt/keyrings
            |  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
            |  chmod a+r /etc/apt/keyrings/docker.asc
            |  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo ${'$'}VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
            |  apt-get update -q
            |  apt-get install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
            |  systemctl enable --now docker
            |  echo "CHANGED docker installed"
            |fi
            |docker compose version >/dev/null 2>&1 || { DEBIAN_FRONTEND=noninteractive apt-get install -yq docker-compose-plugin; }
        """.trimMargin()
    }
}
