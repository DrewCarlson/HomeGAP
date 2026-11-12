package homegap.reconcile

import homegap.state.RunRecord
import homegap.state.withRun
import homegap.clients.PangolinApiException
import homegap.clients.PangolinBootstrapClient
import homegap.clients.PangolinClient
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.platform.Crypto
import homegap.platform.nowMillis
import homegap.provision.Apt
import homegap.provision.MaintenancePage
import homegap.provision.CertMaterial
import homegap.provision.ClusterLink
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.Mesh
import homegap.provision.FirewallRules
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.RenderedFile
import homegap.state.ClusterPangolinState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Node lifecycle domain: turns a bare SSH-reachable Ubuntu VPS into a Pangolin EE node and
 * keeps it there. Every step is idempotent so the same pass provisions and repairs drift.
 */
class NodeReconciler(
    private val ctx: ReconcileContext,
    private val cluster: ClusterReconciler? = null,
) {
    private val domain = "nodes"
    private val log get() = ctx.log
    private val dir get() = ctx.config.pangolin.installDir
    /** Every node runs one Pangolin of the cluster, sharing its database. */
    private val clustered get() = ctx.config.cluster != null

    /** What kind of provisioning a node needs. */
    enum class Kind {
        /** Nothing to do. */
        NONE,
        /** First provisioning, or retrying one that never succeeded. No previous stack to keep. */
        FRESH,
        /** A healthy node's stack changed: rolled out one node at a time, with rollback. */
        UPDATE,
        /** Same stack re-applied (drift, or a requested re-provision). */
        REPAIR,
    }

    /** A node's state as seen by this pass, before anything is changed. */
    class Assessment(
        val desired: NodeDesired,
        val ssh: SshSession,
        val secrets: NodeSecrets,
        val files: List<RenderedFile>,
        val hash: String,
        val kind: Kind,
        val reason: String?,
        val paused: Boolean,
        /** Ready handle for a paused node (no provisioning or bootstrap is attempted). */
        val pausedReady: ReadyNode? = null,
    ) {
        val node: NodeConfig get() = desired.node
    }

    /**
     * Connects, renders the stack and decides whether the node needs provisioning. Changes nothing
     * on the node except a periodic drift check. Returns null when the node is unreachable or in
     * dry-run mode.
     *
     * @param force re-run every provisioning step even if nothing appears to have changed.
     * @param certs wildcard certificate material to install, if configured.
     */
    suspend fun assess(desired: NodeDesired, slot: Int, force: Boolean, certs: CertMaterial?): Assessment? {
        val node = desired.node
        val paused = ctx.nodePaused(node.id)
        val st = ctx.state.node(node.id)

        if (ctx.dryRun) {
            val secrets = (if (clustered) ctx.state.snapshot().clusterPangolin?.let { sharedSecrets(it, "<generated>") } else st.toSecrets()) ?: placeholderSecrets()
            val files = renderAll(node, desired, secrets, certs, slot)
            log.info(domain, "[dry-run] would ensure stack (${files.size} files, hash ${hash(files)}) at $dir; raw ports: ${desired.rawPorts.joinToString { it.entryPoint }.ifEmpty { "none" }}", node.id)
            ctx.diff.add(domain, node.id, "stack", if (st.stackHash == hash(files)) "none" else "provision", hash(files), st.stackHash)
            return null
        }

        val ssh = ctx.ssh.session(node)
        try {
            ssh.ensureMaster()
        } catch (e: Exception) {
            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            log.error(domain, "unreachable over SSH: ${e.message}", node.id)
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("reachable" to "false")) }
            return null
        }

        if (paused) {
            log.info(domain, "paused: skipping provisioning and drift repair", node.id)
            val secrets = st.toSecrets() ?: placeholderSecrets()
            val (keyId, key) = cachedKey(node.id) ?: (null to null)
            return Assessment(desired, ssh, secrets, emptyList(), st.stackHash.orEmpty(), Kind.NONE, null, true, connectApi(node, ssh, slot, keyId, key))
        }

        val secrets = ensureSecrets(node, ssh)
        val files = renderAll(node, desired, secrets, certs, slot)
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
        val hadGoodStack = st.lastProvisionOk == true && st.stackHash != null
        val kind = when {
            !needsProvision -> Kind.NONE
            hadGoodStack && st.stackHash != stackHash -> Kind.UPDATE
            hadGoodStack -> Kind.REPAIR
            else -> Kind.FRESH
        }
        val reason = if (!needsProvision) null else when {
            force -> "requested"
            st.lastProvisionOk == null -> "new node"
            !st.lastProvisionOk -> "previous attempt failed"
            st.stackHash != stackHash -> "stack changed"
            else -> "drift"
        }
        return Assessment(desired, ssh, secrets, files, stackHash, kind, reason, false)
    }

    /** API handle for a node that needs no provisioning now (or is waiting for its update). */
    suspend fun ready(a: Assessment, slot: Int): ReadyNode? {
        if (a.paused) return a.pausedReady
        // A node whose stack isn't the cluster's yet (not provisioned) has no cluster Pangolin to
        // drive: never mint the cluster's key or license against it.
        if (clustered && ctx.state.node(a.node.id).facts[CLUSTER_STACK_FACT] != "true") return null
        return try {
            bootstrap(a.node, a.ssh, slot, a.secrets)
        } catch (e: Exception) {
            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            log.error(domain, "bootstrap failed: ${e.message}", a.node.id)
            ctx.state.updateNode(a.node.id) { it.copy(lastProvisionOk = false, lastProvisionError = "bootstrap: ${e.message}") }
            null
        }
    }

    /**
     * Provisions the node; runs as a background job so passes keep serving the other nodes.
     * An [Kind.UPDATE] backs up the running stack first and restores it if any step, the
     * bootstrap, the verify checks or the tunnel reconnect fails.
     *
     * @param tunnelWasUp the node's tunnel was online before the update (so it must come back).
     * @return true when the node ends up on the new stack.
     */
    suspend fun provisionJob(
        a: Assessment,
        slot: Int,
        tunnelWasUp: Boolean,
        waitForCluster: Boolean = true,
        progress: (String) -> Unit = {},
    ): Boolean {
        val node = a.node
        val ssh = a.ssh
        val update = a.kind == Kind.UPDATE
        val started = nowMillis()
        log.change(domain, "${if (update) "updating" else "provisioning"} (${a.reason})", node.id)
        // The phases, numbered, for the dashboard's rollout strip.
        val phases = buildList {
            add("preparing the host")
            if (clustered) add("waiting for the cluster")
            if (update) add("backing up the stack")
            add("writing the stack")
            add("starting the stack")
            add("verifying")
            if (update && tunnelWasUp) add("waiting for the tunnel")
        }
        fun phase(name: String) = progress("$name (${phases.indexOf(name) + 1} of ${phases.size})")
        var changed = false
        try {
            phase("preparing the host")
            provisionHost(node, ssh, a.desired)
            if (clustered) {
                phase("waiting for the cluster")
                awaitClusterMember(node, waitForCluster)
            }
            val ready = firstClusterStart(node) {
                if (update) {
                    phase("backing up the stack")
                    backup(node, ssh)
                }
                changed = true
                phase("writing the stack")
                provisionStack(node, ssh, a.files)
                phase("starting the stack")
                bootstrap(node, ssh, slot, a.secrets)
            }
            phase("verifying")
            val missing = verify(node, ssh)
            if (update && missing.isNotEmpty()) throw IllegalStateException("verify failed: ${missing.joinToString(", ")}")
            if (update && tunnelWasUp) {
                phase("waiting for the tunnel")
                waitForTunnel(node, ready)
            }
            ctx.state.updateNode(node.id) {
                it.copy(
                    stackHash = a.hash, lastProvisionAt = nowMillis(), lastProvisionOk = true, lastProvisionError = null,
                    lastCheckAt = nowMillis(), drift = emptyList(), update = null,
                    runs = it.runs.withRun(RunRecord(if (update) "update" else "provision", started, nowMillis(), ok = true, stack = a.hash)),
                    facts = it.facts + (PANGOLIN_IMAGE_FACT to ctx.config.pangolin.pangolinImage(clustered)) + (CLUSTER_STACK_FACT to clustered.toString()),
                )
            }
            if (update) log.change(domain, "update complete", node.id)
            pruneImages(node, ssh)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            val msg = e.message?.take(2000) ?: e.toString()
            if (!update) {
                log.error(domain, "provisioning failed: $msg", node.id)
                ctx.state.updateNode(node.id) {
                    it.copy(
                        lastProvisionAt = nowMillis(),
                        lastProvisionOk = false,
                        lastProvisionError = msg,
                        runs = it.runs.withRun(RunRecord("provision", started, nowMillis(), ok = false, error = msg.take(300), stack = a.hash)),
                    )
                }
                return false
            }
            log.error(domain, "update failed: $msg", node.id)
            val restored = if (!changed) true else try {
                log.change(domain, "rolling back to the previous stack", node.id)
                progress("rolling back")
                rollback(ssh, a.files)
                log.change(domain, "rollback complete; previous stack is running", node.id)
                true
            } catch (r: Exception) {
                if (r is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                log.error(domain, "rollback failed: ${r.message}", node.id)
                false
            }
            ctx.state.updateNode(node.id) {
                it.copy(
                    lastProvisionAt = nowMillis(),
                    lastProvisionOk = restored,
                    lastProvisionError = if (restored) null else "update failed and rollback failed: $msg",
                    update = homegap.state.UpdateState(a.hash, "failed", msg, nowMillis(), restored),
                    runs = it.runs.withRun(
                        RunRecord("update", started, nowMillis(), ok = false, error = msg.take(300), stack = a.hash, rolledBack = restored)
                    ),
                )
            }
            return false
        }
    }

    /**
     * Removes the images no container uses any more (the versions this update replaced), once
     * the node runs its new stack. Images of stopped containers are kept. Best effort: a failure
     * only leaves the disk as it was.
     */
    private suspend fun pruneImages(node: NodeConfig, ssh: SshSession) {
        val out = try {
            ssh.runScript("docker image prune --all --force 2>/dev/null | tail -n 1", 300).stdout.trim()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            return
        }
        val reclaimed = out.substringAfter("Total reclaimed space:", "").trim()
        if (reclaimed.isNotEmpty() && reclaimed != "0B") log.change(domain, "removed unused Docker images ($reclaimed)", node.id)
    }

    /** Waits for the node's Newt site to reconnect after an update. */
    private suspend fun waitForTunnel(node: NodeConfig, ready: ReadyNode) {
        val siteId = ctx.state.node(node.id).siteId ?: return
        val org = ctx.config.pangolin.orgId
        waitFor("tunnel to reconnect (site $siteId)", attempts = 60) {
            runCatching {
                ready.api.listSites(org)
                    .firstOrNull { it.siteId == siteId }?.online == true
            }.onFailure {
                if (it is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Keeps a copy of the running stack: every managed file (not data: Pangolin's database,
     * certificates, logs and CrowdSec's data are left in place) and, when the Pangolin image
     * changes, its database, since a newer Pangolin migrates it on start.
     */
    private suspend fun backup(node: NodeConfig, ssh: SshSession) {
        val d = shQuote(dir)
        val image = shQuote(ctx.config.pangolin.pangolinImage(clustered))
        val out = ssh.runScriptOrThrow(
            "backup",
            $$"""
            |cd $$d
            |B=$$d/$$ROLLBACK_DIR
            |rm -rf "$B" && mkdir -p "$B" && chmod 0700 "$B"
            |set --
            |for p in compose.yml .env secrets config; do [ -e "$p" ] && set -- "$@" "$p"; done
            |tar -czf "$B/files.tgz" --exclude=config/db --exclude=config/letsencrypt --exclude=config/traefik/logs --exclude=config/crowdsec/db "$@"
            |CUR=$(docker inspect -f '{{.Config.Image}}' pangolin 2>/dev/null || true)
            |if [ -n "$CUR" ] && [ "$CUR" != $$image ] && [ -d config/db ]; then
            |  docker compose stop pangolin >/dev/null 2>&1
            |  cp -a config/db "$B/db"
            |  echo "DBSNAP $CUR"
            |fi
            |$${clusterDumpStep(node)}
            """.trimMargin(), 600,
        )
        out.lines()
            .firstOrNull { it.startsWith("DBSNAP ") }
            ?.let {
                log.info(domain, "saved Pangolin database before upgrading from ${it.removePrefix("DBSNAP ")}", node.id)
            }
    }

    /** Restores [backup]: removes files only the new stack had, puts back the old ones, restarts. */
    private suspend fun rollback(ssh: SshSession, newFiles: List<RenderedFile>) {
        val d = shQuote(dir)
        ssh.runScriptOrThrow(
            "rollback",
            $$"""
            |cd $$d
            |B=$$d/$$ROLLBACK_DIR
            |[ -f "$B/files.tgz" ] || { echo "no backup to restore" >&2; exit 1; }
            |tar -tzf "$B/files.tgz" > "$B/list"
            |for p in $${newFiles.joinToString(" ") { shQuote(it.path) }}; do grep -qxF "$p" "$B/list" || rm -f "$p"; done
            |tar -xzf "$B/files.tgz"
            |if [ -d "$B/db" ]; then
            |  docker compose stop pangolin >/dev/null 2>&1 || true
            |  rm -rf config/db && cp -a "$B/db" config/db
            |fi
            |$${clusterRestoreStep()}
            |$${SshSession.ownInstallDir(dir)}
            |docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20
            |ok=0
            |for i in $(seq 1 120); do
            |  if curl -fsS -o /dev/null http://127.0.0.1:3000/api/v1/ 2>/dev/null; then ok=1; break; fi
            |  sleep 2
            |done
            |[ "$ok" = 1 ] || { echo "Pangolin did not become healthy after rollback" >&2; docker compose ps >&2; exit 1; }
            """.trimMargin(), 900,
        )
    }

    private fun homegap.state.NodeState.toSecrets(): NodeSecrets? {
        if (setupToken == null || serverSecret == null || adminEmail == null || adminPassword == null) return null
        return NodeSecrets(setupToken, serverSecret, adminEmail, adminPassword, facts["crowdsecKey"].orEmpty())
    }

    private fun placeholderSecrets() = NodeSecrets("<generated>", "<generated>", NodeTemplates.adminEmail(ctx.config), "<generated>", "<generated>")

    private fun sharedSecrets(shared: ClusterPangolinState, crowdsecKey: String) =
        NodeSecrets(shared.setupToken, shared.serverSecret, shared.adminEmail, shared.adminPassword, crowdsecKey, cluster = true)

    /**
     * In a cluster the server secret and admin are shared: kept in the controller's state and
     * copied to every node, so they can be recovered from any node if the state is lost. The
     * CrowdSec key stays per node.
     */
    private suspend fun ensureClusterSecrets(node: NodeConfig, ssh: SshSession): NodeSecrets {
        val st = ctx.state.node(node.id)
        val known = ctx.state.snapshot().clusterPangolin
        st.facts["crowdsecKey"]?.takeIf { known != null && it.isNotEmpty() }?.let { return sharedSecrets(known!!, it) }
        val remote = remoteSecrets(ssh)
            ?.let { runCatching { LENIENT_JSON.decodeFromString<NodeSecrets>(it) }.getOrNull() }
        val shared = known ?: run {
            val recovered = remote?.takeIf { it.cluster }?.let {
                ClusterPangolinState(it.serverSecret, it.setupToken, it.adminEmail, it.adminPassword, bootstrapped = true)
            }
            val candidate = recovered ?: ClusterPangolinState(
                serverSecret = Crypto.token(48),
                setupToken = Crypto.setupToken(),
                adminEmail = NodeTemplates.adminEmail(ctx.config),
                adminPassword = Crypto.password(),
            )
            // Another node's assessment may have stored them first.
            val kept = ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin ?: candidate) }.clusterPangolin!!
            if (kept === candidate) {
                if (recovered != null) log.info(domain, "recovered the cluster's Pangolin secrets from the node", node.id)
                else log.change(domain, "generated the cluster's Pangolin secrets (admin, server secret, setup token)")
            }
            kept
        }
        val crowdsec = st.facts["crowdsecKey"]?.takeIf { it.isNotEmpty() }
            ?: remote?.crowdsecBouncerKey?.takeIf { it.isNotEmpty() }
            ?: Crypto.token(40)
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("crowdsecKey" to crowdsec)) }
        return sharedSecrets(shared, crowdsec)
    }

    /** Reads the secrets file of the node. Returns null if the node has none. Throws if the read fails, so a failed read never causes new secrets. */
    private suspend fun remoteSecrets(ssh: SshSession): String? {
        val q = shQuote("$dir/${NodeTemplates.SECRETS}")
        val r = ssh.runScript("[ -s $q ] || exit 0; cat $q", 60)
        val text = r.stdout.trim()
        check(r.ok && (text.isEmpty() || text.startsWith("{"))) { "could not read $dir/${NodeTemplates.SECRETS} on the node" }
        return text.ifEmpty { null }
    }

    /** Node-held secrets are authoritative; the local cache is refilled from them when lost. */
    private suspend fun ensureSecrets(node: NodeConfig, ssh: SshSession): NodeSecrets {
        if (clustered) return ensureClusterSecrets(node, ssh)
        ctx.state.node(node.id).toSecrets()?.takeIf { it.crowdsecBouncerKey.isNotEmpty() }?.let { return it }
        val remote = remoteSecrets(ssh)
        val secrets = if (remote != null) {
            log.info(domain, "recovered node secrets from the node", node.id)
            LENIENT_JSON.decodeFromString<NodeSecrets>(remote).let {
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

    fun renderAll(node: NodeConfig, desired: NodeDesired, secrets: NodeSecrets, certs: CertMaterial?, slot: Int): List<RenderedFile> {
        val link = ctx.config.cluster?.let { c ->
            val db = if (ctx.dryRun || cluster == null) ClusterSecrets("<generated>", "<generated>", "<generated>", "<generated>", "<generated>", "<generated>") else cluster.secrets()
            ClusterLink(Mesh.nodeAddress(c.mesh, slot), db)
        }
        val files = NodeTemplates.staticFiles(ctx.config, node, secrets, desired.rawPorts, slot, link).toMutableList()
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

    /** Steps 1-4: everything the node needs before its stack (and its cluster member). */
    private suspend fun provisionHost(node: NodeConfig, ssh: SshSession, desired: NodeDesired) {
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

        // 4. Kernel network tuning (best effort: some virtualization types lock these knobs).
        // Before the stack, so new container namespaces inherit the host's congestion control.
        runTuningStep(node, ssh, tuningScript())
    }

    private suspend fun provisionStack(node: NodeConfig, ssh: SshSession, files: List<RenderedFile>) {
        // 5 + 6. Stack files and secrets (restrictive modes), then 7. bring up and wait for health.
        val out = ssh.runScriptOrThrow("stack", stackScript(files), 1200)
        out.lines().filter { it.startsWith("CHANGED ") }.forEach { log.change(domain, "updated ${it.removePrefix("CHANGED ")}", node.id) }

        // What the edge namespace (Gerbil + Traefik) actually uses.
        runTuningStep(node, ssh, EDGE_TCP_SCRIPT)
    }

    private suspend fun runTuningStep(node: NodeConfig, ssh: SshSession, script: String) {
        val t = ssh.runScriptOrThrow("tuning", script, 120)
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
            |mkdir -p ${shQuote("$dir/config")}
            |cat > $src <<'HOMEGAP_SYSCTL'
            |${NodeTemplates.sysctlConf().trimEnd()}
            |HOMEGAP_SYSCTL
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

    private fun firewallScript(node: NodeConfig, desired: NodeDesired): String =
        FirewallRules.script(FirewallRules.specs(node, desired.rawPorts, ctx.config.cluster?.mesh?.port))

    private fun stackScript(files: List<RenderedFile>): String = buildString {
        val d = shQuote(dir)
        appendLine("install -d -m 0755 $d $d/config $d/config/traefik $d/config/traefik/dynamic $d/config/letsencrypt $d/config/traefik/logs $d/config/certificates $d/${MaintenancePage.DIR}")
        appendLine("install -d -m 0700 $d/secrets $d/config/traefik/certs")
        // Traefik leaves its ACME store empty until it issues a certificate (nodes use the
        // wildcard instead), and Pangolin's certificate sync warns about an empty file every few
        // seconds. An empty JSON object is valid for both.
        val acme = shQuote("$dir/config/letsencrypt/acme.json")
        appendLine("[ -s $acme ] || { printf '{}\\n' > $acme; chmod 600 $acme; }")
        appendLine("RESTART=\"\"")
        for (f in files) {
            val path = "$dir/${f.path}"
            appendLine(writeFile(path, f.content, f.mode, onChange = buildString {
                append("echo \"CHANGED ${f.path}\"")
                f.restarts?.let { append($$"; RESTART=\"$RESTART $$it\"") }
            }))
        }
        appendLine(SshSession.ownInstallDir(dir))
        appendLine("cd $d")
        // A Pangolin that started before its database could be reached (a node reboot, while the
        // cluster member was still coming up) gives up on its migrations and never listens.
        // `up` leaves a running container alone, so it is started again here.
        appendLine(
            "if [ \"\$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' pangolin 2>/dev/null)\" = unhealthy ]; then " +
                "docker compose restart pangolin >/dev/null 2>&1 || true; echo \"CHANGED restarted Pangolin (it was running but not answering)\"; fi",
        )
        appendLine("docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20")
        appendLine($$"for s in $(echo $RESTART | tr ' ' '\\n' | sort -u); do docker compose restart \"$s\" >/dev/null 2>&1 || true; done")
        appendLine(
            $$"""
            |ok=0
            |for i in $(seq 1 120); do
            |  if curl -fsS -o /dev/null http://127.0.0.1:3000/api/v1/ 2>/dev/null; then ok=1; break; fi
            |  sleep 2
            |done
            |if [ "$ok" != 1 ]; then echo "Pangolin did not become healthy (its log is on the Diagnostics page)" >&2; docker compose ps >&2; exit 1; fi
            """.trimMargin()
        )
    }

    private fun writeFile(path: String, content: String, mode: String, onChange: String): String {
        val b64 = Base64.encode(content.encodeToByteArray())
        val q = shQuote(path)
        return """
            |(umask 077; printf '%s' '$b64' | base64 -d > $q.homegap-new)
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
            appendLine($$"for s in pangolin gerbil traefik; do st=$(docker inspect -f '{{.State.Status}}' $s 2>/dev/null || echo missing); [ \"$st\" = running ] || echo \"DRIFT container $s $st\"; done")
        }
        val r = ssh.runScript(script, 120)
        if (!r.ok) return listOf("check failed: ${r.stderr.trim().take(200)}")
        return r.stdout.lines().filter { it.startsWith("DRIFT ") }.map { it.removePrefix("DRIFT ") }
    }

    // ---------------------------------------------------------------- headless bootstrap

    private suspend fun connectApi(node: NodeConfig, ssh: SshSession, slot: Int, keyId: String?, key: String?): ReadyNode? {
        if (keyId == null || key == null) return null
        return runCatching {
            val port = ssh.forward(3003, LOCAL_PORT_BASE + slot * 10)
            ReadyNode(node, PangolinClient(ctx.localHttp, "http://127.0.0.1:$port/v1", "$keyId.$key"))
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }.getOrNull()
    }

    /** The orchestrator's API key: per node, or the cluster's one key (it lives in the shared database). */
    private fun cachedKey(nodeId: String): Pair<String, String>? =
        if (clustered) ctx.state.snapshot().clusterPangolin?.let { c -> c.apiKeyId?.let { id -> c.apiKey?.let { id to it } } }
        else ctx.state.node(nodeId).let { s -> s.apiKeyId?.let { id -> s.apiKey?.let { id to it } } }

    private suspend fun storeKey(nodeId: String, keyId: String, key: String) {
        if (clustered) ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin?.copy(apiKeyId = keyId, apiKey = key, bootstrapped = true)) }
        else ctx.state.updateNode(nodeId) { it.copy(apiKeyId = keyId, apiKey = key) }
    }

    private suspend fun bootstrap(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets): ReadyNode {
        val apiPort = ssh.forward(3003, LOCAL_PORT_BASE + slot * 10)
        val integrationUrl = "http://127.0.0.1:$apiPort/v1"
        fastPath(node, ssh, slot, secrets, integrationUrl, warn = !clustered)?.let { return it }
        if (!clustered) return mint(node, ssh, slot, secrets, integrationUrl)
        // One node at a time sets up the cluster's admin, org and key; the others then reuse them.
        return clusterKeyLock.withLock {
            fastPath(node, ssh, slot, secrets, integrationUrl, warn = true) ?: mint(node, ssh, slot, secrets, integrationUrl)
        }
    }

    /** The cached key still works. */
    private suspend fun fastPath(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, integrationUrl: String, warn: Boolean): ReadyNode? {
        val (keyId, key) = cachedKey(node.id) ?: return null
        val api = PangolinClient(ctx.localHttp, integrationUrl, "$keyId.$key")
        // Only Pangolin refusing the key means a new one is needed: a Pangolin that is still
        // starting is retried (minting costs an admin login and retires the key every node uses).
        var attempt = 0
        while (true) {
            val failure = try {
                api.getOrg(ctx.config.pangolin.orgId)
                null
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                e
            } ?: break
            val rejected = failure is PangolinApiException && failure.status in setOf(401, 403)
            if (rejected || ++attempt >= KEY_CHECK_ATTEMPTS) {
                // A Pangolin that does not answer (a database failover) cannot mint a key either.
                val down = failure !is PangolinApiException || failure.status >= 500
                if (!rejected && down) throw IllegalStateException("Pangolin does not answer (${failure.message})")
                if (warn) log.warn(domain, "cached API key ${if (rejected) "rejected" else "not usable"} (${failure.message}); minting a new one", node.id)
                return null
            }
            delay(2.seconds)
        }
        ensureKeyActions(node, ssh, slot, secrets, keyId)
        maybeActivateLicense(node, ssh, slot, secrets)
        return ReadyNode(node, api, dashboardUrl(ssh, slot))
    }

    private suspend fun dashboardUrl(ssh: SshSession, slot: Int) = "http://127.0.0.1:${ssh.forward(3000, LOCAL_PORT_BASE + slot * 10 + 5)}/api/v1"

    /**
     * Grants the key any actions added since it was minted (one login per change). [justMinted]:
     * the key was created with the current actions, so they are only recorded.
     */
    private suspend fun ensureKeyActions(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, keyId: String, justMinted: Boolean = false) {
        // The cluster's one key is updated by one node at a time. The lock is not reentrant and may
        // already be held (by bootstrap, or another node): then a later pass does it.
        if (clustered && !justMinted && ctx.state.snapshot().clusterPangolin?.keyActions != KEY_ACTIONS_HASH) {
            if (!clusterKeyLock.tryLock()) return
            try {
                if (ctx.state.snapshot().clusterPangolin?.keyActions != KEY_ACTIONS_HASH) updateKeyActions(node, ssh, slot, secrets, keyId, false)
            } finally {
                clusterKeyLock.unlock()
            }
            return
        }
        updateKeyActions(node, ssh, slot, secrets, keyId, justMinted)
    }

    private suspend fun updateKeyActions(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, keyId: String, justMinted: Boolean) {
        val want = KEY_ACTIONS_HASH
        val have = if (clustered) ctx.state.snapshot().clusterPangolin?.keyActions else ctx.state.node(node.id).facts[KEY_ACTIONS_FACT]
        if (have == want) return
        try {
            if (!justMinted) {
                val boot = dashboardClient(ssh, slot).also { it.login(secrets.adminEmail, secrets.adminPassword) }
                boot.setApiKeyActions(keyId, PangolinBootstrapClient.ORCHESTRATOR_ACTIONS)
                log.change(domain, "updated the orchestrator API key's permissions", node.id.takeIf { !clustered })
            }
            if (clustered) ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin?.copy(keyActions = want)) }
            else ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (KEY_ACTIONS_FACT to want)) }
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            log.warn(domain, "could not update the API key's permissions: ${e.message}", node.id)
        }
    }

    private suspend fun mint(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, integrationUrl: String): ReadyNode {
        val org = ctx.config.pangolin.orgId

        val dashPort = ssh.forward(3000, LOCAL_PORT_BASE + slot * 10 + 5)
        val boot = PangolinBootstrapClient(ctx.localHttp, "http://127.0.0.1:$dashPort/api/v1")
        waitFor("Pangolin API") {
            runCatching { boot.initialSetupComplete(); true }
                .onFailure {
                    if (it is CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                .getOrDefault(false)
        }

        if (!boot.initialSetupComplete()) {
            boot.setServerAdmin(secrets.adminEmail, secrets.adminPassword, secrets.setupToken)
            log.change(domain, "created server admin ${secrets.adminEmail}", node.id)
        }
        try {
            boot.login(secrets.adminEmail, secrets.adminPassword)
        } catch (e: PangolinApiException) {
            throw IllegalStateException(
                "cannot log in as ${secrets.adminEmail} (${e.message}). The node has an admin the controller does not know; " +
                    if (clustered) "restore the cluster's Pangolin secrets from any node's $dir/${NodeTemplates.SECRETS}."
                    else "restore $dir/${NodeTemplates.SECRETS} or wipe the node's $dir/config/db to re-bootstrap."
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
            boot.listRootApiKeys()
                .filter { (id, name) -> name == "homegap-orchestrator" && id != keyId }
                .forEach { (id, _) -> boot.deleteRootApiKey(id) }
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
        log.change(domain, if (clustered) "minted the cluster's orchestrator API key" else "minted orchestrator API key", node.id)
        storeKey(node.id, keyId, key)
        // Minted with the current actions: record them without another call.
        ensureKeyActions(node, ssh, slot, secrets, keyId, justMinted = true)
        maybeActivateLicense(node, ssh, slot, secrets, boot)
        return ReadyNode(node, PangolinClient(ctx.localHttp, integrationUrl, "$keyId.$key"), dashboardUrl(ssh, slot))
    }

    private suspend fun dashboardClient(ssh: SshSession, slot: Int) =
        PangolinBootstrapClient(ctx.localHttp, "http://127.0.0.1:${ssh.forward(3000, LOCAL_PORT_BASE + slot * 10 + 5)}/api/v1")

    /**
     * A cluster has one license (it is stored in the shared database): activated through the first
     * node that can, then every other running Pangolin is told to re-read it.
     */
    private suspend fun maybeActivateClusterLicense(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, existing: PangolinBootstrapClient?) {
        val shared = ctx.state.snapshot().clusterPangolin ?: return
        if (shared.licenseActivated) {
            if (node.id !in shared.licenseRecheck) return
            try {
                val boot = existing ?: dashboardClient(ssh, slot).also { it.login(secrets.adminEmail, secrets.adminPassword) }
                boot.recheckLicense()
                ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin?.let { it.copy(licenseRecheck = it.licenseRecheck - node.id) }) }
                log.change(domain, "picked up the cluster's EE license", node.id)
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                log.warn(domain, "license recheck failed: ${e.message}", node.id)
            }
            return
        }
        val ref = ctx.config.pangolin.licenseKey() ?: return
        val key = ref.resolve() ?: run {
            if (!licenseWarned) log.warn(domain, "license key ${ref.describe()} not set; EE wildcard resources will be refused")
            licenseWarned = true
            return
        }
        licenseWarned = false
        val keyHash = Crypto.fnv64(key)
        if (shared.licenseRejected == keyHash) return
        suspend fun activated() {
            val others = ctx.config.nodes.map { it.id }.toSet() - node.id
            ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin?.copy(licenseActivated = true, licenseRecheck = others)) }
        }
        try {
            val boot = existing ?: dashboardClient(ssh, slot).also { it.login(secrets.adminEmail, secrets.adminPassword) }
            boot.activateLicense(key)
            activated()
            log.change(domain, "activated the cluster's Pangolin EE license", node.id)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val msg = e.message.orEmpty()
            when {
                msg.contains("License key already exists") -> activated()
                msg.contains("different instance", ignoreCase = true) -> {
                    ctx.state.update { s -> s.copy(clusterPangolin = s.clusterPangolin?.copy(licenseRejected = keyHash)) }
                    log.error(domain, "EE license rejected: the key is already bound to another Pangolin instance (one key covers the whole cluster).")
                }
                else -> log.error(domain, "license activation failed: $msg", node.id)
            }
        }
    }

    private suspend fun maybeActivateLicense(node: NodeConfig, ssh: SshSession, slot: Int, secrets: NodeSecrets, existing: PangolinBootstrapClient? = null) {
        if (clustered) return maybeActivateClusterLicense(node, ssh, slot, secrets, existing)
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
            val boot = existing ?: PangolinBootstrapClient(ctx.localHttp, "http://127.0.0.1:${ssh.forward(3000, LOCAL_PORT_BASE + slot * 10 + 5)}/api/v1").also {
                it.login(secrets.adminEmail, secrets.adminPassword)
            }
            boot.activateLicense(key)
            ctx.state.updateNode(node.id) { it.copy(licenseActivated = true) }
            log.change(domain, "activated Pangolin EE license", node.id)
        } catch (e: Exception) {
            if (e is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
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

    // ---------------------------------------------------------------- cluster

    /**
     * Pangolin needs the node's cluster member (its database replica and HAProxy) before it can
     * start. The member is set up by the cluster step of later passes, so a background job waits.
     */
    private suspend fun awaitClusterMember(node: NodeConfig, wait: Boolean) {
        val rec = cluster ?: return
        if (rec.memberReady(node.id)) return
        if (!wait) throw IllegalStateException("this node's cluster database member is not ready yet")
        log.info(domain, "waiting for this node's cluster database member", node.id)
        val deadline = nowMillis() + CLUSTER_MEMBER_WAIT.inWholeMilliseconds
        while (!rec.memberReady(node.id)) {
            if (nowMillis() > deadline) throw IllegalStateException("this node's cluster database member did not become ready in $CLUSTER_MEMBER_WAIT")
            delay(5.seconds)
        }
    }

    /**
     * The first Pangolin on an empty cluster database migrates it and creates the admin: only one
     * node may do that, so until the cluster's Pangolin is set up, nodes start one at a time.
     */
    private suspend fun <T> firstClusterStart(node: NodeConfig, block: suspend () -> T): T {
        if (!clustered || ctx.state.snapshot().clusterPangolin?.bootstrapped == true) return block()
        if (firstStartLock.isLocked) log.info(domain, "waiting for another node to set up the cluster's Pangolin", node.id)
        return firstStartLock.withLock { block() }
    }

    /**
     * A Pangolin upgrade migrates the shared database when the first node starts the new version:
     * that node saves a dump first, so a failed upgrade can restore it. Later nodes find the
     * database already migrated and save nothing.
     */
    private fun clusterDumpStep(node: NodeConfig): String {
        if (!clustered || cluster == null) return ":"
        val image = ctx.config.pangolin.pangolinImage(true)
        val first = ctx.state.snapshot().nodes.none { (id, s) -> id != node.id && s.facts[PANGOLIN_IMAGE_FACT] == image }
        if (!first) return ":"
        return $$"""
            |if [ -n "$CUR" ] && [ "$CUR" != $${shQuote(image)} ]; then
            |  $${pgEnvFile()}
            |  docker run --rm --network $${ClusterTemplates.NETWORK} --env-file "$B/pg.env" -v "$B:/backup" --entrypoint pg_dump $${shQuote(ctx.config.cluster!!.image)} \
            |    -h $${ClusterTemplates.PROXY_HOST} -p $${ClusterTemplates.PROXY_POSTGRES_PORT} -U $${ClusterTemplates.PANGOLIN_USER} -Fc -f /backup/cluster.dump $${ClusterTemplates.PANGOLIN_DB}
            |  rm -f "$B/pg.env"
            |  echo "DBSNAP $CUR"
            |fi
        """.trimMargin()
    }

    private fun clusterRestoreStep(): String {
        if (!clustered || cluster == null) return ":"
        return $$"""
            |if [ -f "$B/cluster.dump" ]; then
            |  docker compose stop pangolin >/dev/null 2>&1 || true
            |  $${pgEnvFile()}
            |  docker run --rm --network $${ClusterTemplates.NETWORK} --env-file "$B/pg.env" -v "$B:/backup:ro" --entrypoint pg_restore $${shQuote(ctx.config.cluster!!.image)} \
            |    -h $${ClusterTemplates.PROXY_HOST} -p $${ClusterTemplates.PROXY_POSTGRES_PORT} -U $${ClusterTemplates.PANGOLIN_USER} --clean --if-exists --single-transaction -d $${ClusterTemplates.PANGOLIN_DB} /backup/cluster.dump
            |  rm -f "$B/pg.env"
            |  echo "restored the cluster database from before the upgrade"
            |fi
        """.trimMargin()
    }

    /** The database password in a private file (never in a command line). */
    private fun pgEnvFile(): String =
        "(umask 077; printf 'PGPASSWORD=%s\\n' ${shQuote(cluster!!.secrets().pangolin)} > \"\$B/pg.env\")"

    /** Post-provision checks; returns the ones that failed. */
    private suspend fun verify(node: NodeConfig, ssh: SshSession): List<String> {
        val r = ssh.runScript(
            """
            |cd ${shQuote(dir)}
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "CHECK gerbil-udp ok" || echo "CHECK gerbil-udp missing"
            |(ss -lun 2>/dev/null | grep -q ':21820 ') && echo "CHECK gerbil-clients-udp ok" || echo "CHECK gerbil-clients-udp missing"
            |curl -fsS -o /dev/null http://127.0.0.1:3003/v1/ && echo "CHECK integration-api ok" || echo "CHECK integration-api missing"
            """.trimMargin(), 60,
        )
        val missing = r.stdout.lines().filter { it.startsWith("CHECK ") && it.endsWith("missing") }.map { it.removePrefix("CHECK ") }
        missing.forEach { log.warn(domain, "verify: $it", node.id) }
        return missing
    }

    private suspend fun waitFor(what: String, attempts: Int = 60, check: suspend () -> Boolean) {
        repeat(attempts) {
            if (check()) return
            delay(2.seconds)
        }
        throw IllegalStateException("$what not reachable")
    }

    /**
     * Stops the node stack and removes it with its data and secrets (decommission). A node that
     * is added again starts as a new node.
     */
    suspend fun decommission(node: NodeConfig) {
        check(dir.startsWith("/") && dir.trimEnd('/').count { it == '/' } >= 2) { "install_dir '$dir' is not safe to remove" }
        val ssh = ctx.ssh.session(node)
        ssh.runScriptOrThrow("decommission", "cd ${shQuote(dir)} 2>/dev/null && docker compose down --remove-orphans --volumes || true", 300)
        ssh.runScript(homegap.provision.ExitNodes.teardownScript(ctx.config.pangolin.installDir), 120)
        if (ctx.config.cluster != null) {
            val cluster = shQuote("$dir/cluster")
            ssh.runScript("cd $cluster 2>/dev/null && docker compose down --remove-orphans || true; rm -rf $cluster", 300)
            ssh.runScript(homegap.provision.Mesh.TEARDOWN, 60)
        }
        // The directory itself can be a mount point, so its contents go first.
        val q = shQuote(dir)
        ssh.runScriptOrThrow("decommission", "find $q -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null; rmdir $q 2>/dev/null; [ -z \"\$(ls -A $q 2>/dev/null)\" ]", 120)
        ctx.ssh.drop(node.id)
    }

    companion object {
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true }
        const val LOCAL_PORT_BASE = 47100
        /** Previous stack kept on the node during an update, relative to install_dir. */
        const val ROLLBACK_DIR = ".homegap-rollback"
        val SUPPORTED_UBUNTU = setOf("22.04", "24.04", "26.04", "26.10")
        /** The Pangolin image a node last provisioned successfully. */
        const val PANGOLIN_IMAGE_FACT = "pangolinImage"
        private const val KEY_ACTIONS_FACT = "keyActions"
        /** How long a cached key is retried while Pangolin starts (2 s apart). */
        private const val KEY_CHECK_ATTEMPTS = 10
        private val KEY_ACTIONS_HASH = Crypto.fnv64(PangolinBootstrapClient.ORCHESTRATOR_ACTIONS.sorted().joinToString(","))
        /** The node's running stack is the cluster's (not a standalone Pangolin). */
        const val CLUSTER_STACK_FACT = "clusterStack"
        private val CLUSTER_MEMBER_WAIT = 20.minutes
        /** Serialises the first start of the cluster's Pangolin (an empty database is migrated once). */
        private val firstStartLock = Mutex()
        /** Serialises minting the cluster's one API key (minting retires every other). */
        private val clusterKeyLock = Mutex()
        /** The cluster's missing license key is reported once, not on every pass. */
        @kotlin.concurrent.Volatile private var licenseWarned = false

        /** Congestion control and max send buffer inside Gerbil's namespace (Traefik shares it). */
        val EDGE_TCP_SCRIPT = $$"""
            |PID=$(docker inspect -f '{{.State.Pid}}' gerbil 2>/dev/null || echo 0)
            |if [ "$PID" -gt 0 ] 2>/dev/null; then
            |  CC=$(nsenter -t "$PID" -n cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null || echo unknown)
            |  WMEM=$(nsenter -t "$PID" -n cat /proc/sys/net/ipv4/tcp_wmem 2>/dev/null | awk '{print int($3/1048576)"MiB"}')
            |  echo "FACT edge_tcp=$CC ${WMEM:-unknown}"
            |else
            |  echo "FACT edge_tcp=unknown"
            |fi
        """.trimMargin()

        val PREFLIGHT_SCRIPT = $$"""
            |. /etc/os-release
            |echo "ID=$ID"
            |echo "VERSION_ID=$VERSION_ID"
            |echo "CODENAME=${VERSION_CODENAME:-}"
            |echo "ARCH=$(uname -m)"
            |echo "KERNEL=$(uname -r)"
            |echo "MEM_KB=$(awk '/MemTotal/{print $2}' /proc/meminfo)"
            |echo "VCPU=$(nproc 2>/dev/null || echo 0)"
            |echo "DISK_FREE_GB=$(df -BG --output=avail / 2>/dev/null | tail -1 | tr -dc 0-9)"
            |echo "DOCKER=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)"
            |echo "SUDO=yes"
            |$${Apt.FUNCTION}
            |command -v curl >/dev/null || { hg_apt update -q && hg_apt install -yq curl ca-certificates; }
        """.trimMargin()

        val DOCKER_SCRIPT = $$"""
            |$${Apt.FUNCTION}
            |if ! type -P docker >/dev/null 2>&1; then
            |  hg_apt update -q || exit 1
            |  hg_apt install -yq ca-certificates curl || exit 1
            |  install -m 0755 -d /etc/apt/keyrings
            |  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
            |  chmod a+r /etc/apt/keyrings/docker.asc
            |  # Docker publishes new Ubuntu releases with some delay: fall back to the latest LTS.
            |  CODENAME=$(. /etc/os-release && echo $VERSION_CODENAME)
            |  REPO=
            |  for c in "$CODENAME" resolute noble; do
            |    if curl -fsSL -o /dev/null "https://download.docker.com/linux/ubuntu/dists/$c/Release"; then REPO=$c; break; fi
            |  done
            |  [ -n "$REPO" ] || { echo "no Docker repository for $CODENAME" >&2; exit 1; }
            |  [ "$REPO" = "$CODENAME" ] || echo "Docker has no packages for $CODENAME yet; using $REPO"
            |  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $REPO stable" > /etc/apt/sources.list.d/docker.list
            |  hg_apt update -q || exit 1
            |  # Container logs have no size limit by default, and a small disk fills.
            |  if [ ! -e /etc/docker/daemon.json ]; then
            |    install -m 0755 -d /etc/docker
            |    printf '%s\n' '{"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}}' > /etc/docker/daemon.json
            |  fi
            |  hg_apt install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin || exit 1
            |  systemctl enable --now docker
            |  echo "CHANGED docker installed"
            |fi
            |docker compose version >/dev/null 2>&1 || hg_apt install -yq docker-compose-plugin
        """.trimMargin()
    }
}
