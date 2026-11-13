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
import homegap.platform.runCatchingCancellable
import homegap.provision.Apt
import homegap.provision.ClusterLink
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ExitNodes
import homegap.provision.FirewallRules
import homegap.provision.MaintenancePage
import homegap.provision.Mesh
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.RenderedFile
import homegap.state.ClusterPangolinState
import homegap.state.NodeState
import homegap.state.RunRecord
import homegap.state.UpdateState
import homegap.state.withRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
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
    private val log get() = ctx.log
    private val dir get() = ctx.config.pangolin.installDir

    /** Each node runs one Pangolin of the cluster. All of them share the database of the cluster. */
    private val clustered get() = ctx.config.cluster != null

    /** What kind of provisioning a node needs. */
    enum class Kind {
        /** Nothing to do. */
        NONE,

        /** The first provisioning, or a retry of one that never succeeded. The node has no previous stack. */
        FRESH,

        /** The stack of a healthy node changed. The rollout updates one node at a time, with rollback. */
        UPDATE,

        /** The node gets the same stack again (drift, or a requested re-provision). */
        REPAIR,
    }

    /** The state of a node as this pass sees it, before the pass changes anything. */
    class Assessment(
        val desired: NodeDesired,
        val ssh: SshSession,
        val secrets: NodeSecrets,
        val files: List<RenderedFile>,
        val hash: String,
        val kind: Kind,
        val reason: String?,
        val paused: Boolean,
        /** The ready handle of a paused node. A paused node gets no provisioning and no bootstrap. */
        val pausedReady: ReadyNode? = null,
    ) {
        val node: NodeConfig get() = desired.node
    }

    /** What the bootstrap of one node works with. */
    private class NodeAccess(
        val node: NodeConfig,
        val ssh: SshSession,
        val slot: Int,
        val secrets: NodeSecrets,
    )

    /**
     * Connects, renders the stack and decides whether the node needs provisioning. Changes nothing
     * on the node except a periodic drift check. Returns null when the node is unreachable or in
     * dry-run mode.
     *
     * @param force runs every provisioning step again, also when nothing changed.
     */
    suspend fun assess(desired: NodeDesired, slot: Int, force: Boolean): Assessment? {
        val node = desired.node
        val paused = ctx.nodePaused(node.id)
        val nodeState = ctx.state.node(node.id)

        if (ctx.dryRun) {
            reportDryRun(desired, slot, nodeState)
            return null
        }

        val ssh = ctx.ssh.session(node)
        val unreachable = runCatchingCancellable { ssh.ensureMaster() }.exceptionOrNull()
        if (unreachable != null) {
            log.error(DOMAIN, "unreachable over SSH: ${unreachable.message}", node.id)
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts + ("reachable" to "false")) }
            return null
        }

        if (paused) {
            log.info(DOMAIN, "paused: skipping provisioning and drift repair", node.id)
            return Assessment(
                desired = desired,
                ssh = ssh,
                secrets = nodeState.toSecrets() ?: placeholderSecrets(),
                files = emptyList(),
                hash = nodeState.stackHash.orEmpty(),
                kind = Kind.NONE,
                reason = null,
                paused = true,
                pausedReady = connectApi(node, ssh, slot, cachedKey(node.id)),
            )
        }

        val secrets = ensureSecrets(node, ssh)
        val files = renderAll(node, desired, secrets, slot)
        val stackHash = hash(files)
        val checkInterval = ctx.config.reconcile.nodeCheck.inWholeMilliseconds
        val checkDue = nodeState.lastCheckAt == null || nowMillis() - nodeState.lastCheckAt > checkInterval
        val stackIsCurrent = nodeState.lastProvisionOk == true && nodeState.stackHash == stackHash
        val drift = if (!force && checkDue && stackIsCurrent) recordDrift(node, ssh, files) else emptyList()

        val needsProvision = force || !stackIsCurrent || drift.isNotEmpty()
        ctx.diff.add(
            domain = DOMAIN,
            target = node.id,
            key = "stack",
            action = if (needsProvision) "provision" else "none",
            desired = stackHash,
            actual = nodeState.stackHash,
        )
        val hadGoodStack = nodeState.lastProvisionOk == true && nodeState.stackHash != null
        val kind = when {
            !needsProvision -> Kind.NONE
            hadGoodStack && nodeState.stackHash != stackHash -> Kind.UPDATE
            hadGoodStack -> Kind.REPAIR
            else -> Kind.FRESH
        }
        val reason = when {
            !needsProvision -> null
            force -> "requested"
            nodeState.lastProvisionOk == null -> "new node"
            !nodeState.lastProvisionOk -> "previous attempt failed"
            nodeState.stackHash != stackHash -> "stack changed"
            else -> "drift"
        }
        return Assessment(
            desired = desired,
            ssh = ssh,
            secrets = secrets,
            files = files,
            hash = stackHash,
            kind = kind,
            reason = reason,
            paused = false,
        )
    }

    private fun reportDryRun(desired: NodeDesired, slot: Int, nodeState: NodeState) {
        val node = desired.node
        val knownSecrets = if (clustered) {
            ctx.state.snapshot().clusterPangolin?.let { sharedSecrets(it, PLACEHOLDER) }
        } else {
            nodeState.toSecrets()
        }
        val files = renderAll(node, desired, knownSecrets ?: placeholderSecrets(), slot)
        val stackHash = hash(files)
        val rawPorts = desired.rawPorts.joinToString { it.entryPoint }.ifEmpty { "none" }
        log.info(
            DOMAIN,
            "[dry-run] would ensure stack (${files.size} files, hash $stackHash) at $dir; raw ports: $rawPorts",
            node.id,
        )
        ctx.diff.add(
            domain = DOMAIN,
            target = node.id,
            key = "stack",
            action = if (nodeState.stackHash == stackHash) "none" else "provision",
            desired = stackHash,
            actual = nodeState.stackHash,
        )
    }

    private suspend fun recordDrift(node: NodeConfig, ssh: SshSession, files: List<RenderedFile>): List<String> {
        val drift = checkDrift(ssh, files)
        ctx.state.updateNode(node.id) { it.copy(lastCheckAt = nowMillis(), drift = drift) }
        if (drift.isNotEmpty()) {
            log.warn(DOMAIN, "drift detected: ${drift.joinToString("; ")}", node.id)
        }
        return drift
    }

    /** Returns the API handle of a node that needs no provisioning now, or that waits for its update. */
    suspend fun ready(a: Assessment, slot: Int): ReadyNode? {
        if (a.paused) return a.pausedReady
        // A node that does not run the stack of the cluster yet has no cluster Pangolin.
        // Do not mint the key of the cluster or activate its license on such a node.
        if (clustered && ctx.state.node(a.node.id).facts[CLUSTER_STACK_FACT] != "true") return null
        return runCatchingCancellable { bootstrap(NodeAccess(a.node, a.ssh, slot, a.secrets)) }
            .onFailure { e ->
                log.error(DOMAIN, "bootstrap failed: ${e.message}", a.node.id)
                ctx.state.updateNode(a.node.id) {
                    it.copy(lastProvisionOk = false, lastProvisionError = "bootstrap: ${e.message}")
                }
            }
            .getOrNull()
    }

    /**
     * Provisions the node. Runs as a background job, so the passes continue to serve the other nodes.
     * A [Kind.UPDATE] first saves the stack that runs. It restores that stack if a step, the
     * bootstrap, a check after provisioning or the reconnect of the tunnel fails.
     *
     * @param tunnelWasUp the tunnel of the node was online before the update, so it must reconnect.
     * @return true when the node runs the new stack.
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
        log.change(DOMAIN, "${if (update) "updating" else "provisioning"} (${a.reason})", node.id)
        // The rollout strip of the dashboard shows each phase with its number.
        val phases = buildList {
            add(PHASE_HOST)
            if (clustered) add(PHASE_CLUSTER)
            if (update) add(PHASE_BACKUP)
            add(PHASE_WRITE)
            add(PHASE_START)
            add(PHASE_VERIFY)
            if (update && tunnelWasUp) add(PHASE_TUNNEL)
        }
        fun phase(name: String) = progress("$name (${phases.indexOf(name) + 1} of ${phases.size})")
        var stackChanged = false
        val failure = runCatchingCancellable {
            phase(PHASE_HOST)
            provisionHost(node, ssh, a.desired)
            if (clustered) {
                phase(PHASE_CLUSTER)
                awaitClusterMember(node, waitForCluster)
            }
            val ready = firstClusterStart(node) {
                if (update) {
                    phase(PHASE_BACKUP)
                    backup(node, ssh)
                }
                stackChanged = true
                phase(PHASE_WRITE)
                provisionStack(node, ssh, a.files)
                phase(PHASE_START)
                bootstrap(NodeAccess(node, ssh, slot, a.secrets))
            }
            phase(PHASE_VERIFY)
            val missing = verify(node, ssh)
            check(!update || missing.isEmpty()) { "verify failed: ${missing.joinToString(", ")}" }
            if (update && tunnelWasUp) {
                phase(PHASE_TUNNEL)
                waitForTunnel(node, ready)
            }
            recordSuccess(a, started)
            if (update) log.change(DOMAIN, "update complete", node.id)
            pruneImages(node, ssh)
        }.exceptionOrNull() ?: return true

        val message = failure.message?.take(ERROR_LENGTH) ?: failure.toString()
        if (!update) {
            log.error(DOMAIN, "provisioning failed: $message", node.id)
            recordProvisionFailure(a, started, message)
            return false
        }
        log.error(DOMAIN, "update failed: $message", node.id)
        val restored = !stackChanged || restorePreviousStack(a, progress)
        recordUpdateFailure(a, started, message, restored)
        return false
    }

    private suspend fun recordSuccess(a: Assessment, started: Long) {
        val runKind = if (a.kind == Kind.UPDATE) RUN_UPDATE else RUN_PROVISION
        ctx.state.updateNode(a.node.id) {
            it.copy(
                stackHash = a.hash,
                lastProvisionAt = nowMillis(),
                lastProvisionOk = true,
                lastProvisionError = null,
                lastCheckAt = nowMillis(),
                drift = emptyList(),
                update = null,
                runs = it.runs.withRun(RunRecord(runKind, started, nowMillis(), ok = true, stack = a.hash)),
                facts = it.facts +
                    (PANGOLIN_IMAGE_FACT to ctx.config.pangolin.pangolinImage(clustered)) +
                    (CLUSTER_STACK_FACT to clustered.toString()),
            )
        }
    }

    private suspend fun recordProvisionFailure(a: Assessment, started: Long, message: String) {
        ctx.state.updateNode(a.node.id) {
            it.copy(
                lastProvisionAt = nowMillis(),
                lastProvisionOk = false,
                lastProvisionError = message,
                runs = it.runs.withRun(
                    RunRecord(
                        kind = RUN_PROVISION,
                        startedAt = started,
                        finishedAt = nowMillis(),
                        ok = false,
                        error = message.take(RUN_ERROR_LENGTH),
                        stack = a.hash,
                    ),
                ),
            )
        }
    }

    /** Returns true when the previous stack runs again. */
    private suspend fun restorePreviousStack(a: Assessment, progress: (String) -> Unit): Boolean =
        runCatchingCancellable {
            log.change(DOMAIN, "rolling back to the previous stack", a.node.id)
            progress("rolling back")
            rollback(a.ssh, a.files)
            log.change(DOMAIN, "rollback complete; previous stack is running", a.node.id)
        }
            .onFailure { log.error(DOMAIN, "rollback failed: ${it.message}", a.node.id) }
            .isSuccess

    private suspend fun recordUpdateFailure(a: Assessment, started: Long, message: String, restored: Boolean) {
        ctx.state.updateNode(a.node.id) {
            it.copy(
                lastProvisionAt = nowMillis(),
                lastProvisionOk = restored,
                lastProvisionError = if (restored) null else "update failed and rollback failed: $message",
                update = UpdateState(
                    targetHash = a.hash,
                    status = "failed",
                    error = message,
                    at = nowMillis(),
                    rolledBack = restored,
                ),
                runs = it.runs.withRun(
                    RunRecord(
                        kind = RUN_UPDATE,
                        startedAt = started,
                        finishedAt = nowMillis(),
                        ok = false,
                        error = message.take(RUN_ERROR_LENGTH),
                        stack = a.hash,
                        rolledBack = restored,
                    ),
                ),
            )
        }
    }

    /**
     * Removes the images that no container uses (the versions that this update replaced), when the
     * node runs its new stack. Keeps the images of stopped containers. A failure only leaves the
     * disk as it was.
     */
    private suspend fun pruneImages(node: NodeConfig, ssh: SshSession) {
        val output = runCatchingCancellable {
            ssh.runScript("docker image prune --all --force 2>/dev/null | tail -n 1", timeoutSeconds = 300)
                .stdout
                .trim()
        }.getOrElse { return }
        val reclaimed = output.substringAfter("Total reclaimed space:", "").trim()
        if (reclaimed.isNotEmpty() && reclaimed != "0B") {
            log.change(DOMAIN, "removed unused Docker images ($reclaimed)", node.id)
        }
    }

    /** Waits for the node's Newt site to reconnect after an update. */
    private suspend fun waitForTunnel(node: NodeConfig, ready: ReadyNode) {
        val siteId = ctx.state.node(node.id).siteId ?: return
        val org = ctx.config.pangolin.orgId
        waitFor("tunnel to reconnect (site $siteId)", attempts = 60) {
            runCatchingCancellable { ready.api.listSites(org).firstOrNull { it.siteId == siteId }?.online == true }
                .getOrDefault(false)
        }
    }

    /**
     * Keeps a copy of the stack that runs: each managed file, but not the data. The database of
     * Pangolin, the certificates, the logs and the data of CrowdSec stay in place. If the Pangolin
     * image changes, the copy includes the database, because a newer Pangolin migrates it on start.
     */
    private suspend fun backup(node: NodeConfig, ssh: SshSession) {
        val installDir = shQuote(dir)
        val image = shQuote(ctx.config.pangolin.pangolinImage(clustered))
        val output = ssh.runScriptOrThrow(
            step = "backup",
            script = $$"""
            |cd $$installDir
            |B=$$installDir/$$ROLLBACK_DIR
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
            """.trimMargin(),
            timeoutSeconds = 600,
        )
        val previousImage = output.lines()
            .firstOrNull { it.startsWith(DB_SNAPSHOT) }
            ?.removePrefix(DB_SNAPSHOT)
            ?: return
        log.info(DOMAIN, "saved Pangolin database before upgrading from $previousImage", node.id)
    }

    /**
     * Restores the copy from [backup]. Removes the files that only the new stack has, puts back the
     * old files and starts the stack again.
     */
    private suspend fun rollback(ssh: SshSession, newFiles: List<RenderedFile>) {
        val installDir = shQuote(dir)
        ssh.runScriptOrThrow(
            step = "rollback",
            script = $$"""
            |cd $$installDir
            |B=$$installDir/$$ROLLBACK_DIR
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
            """.trimMargin(),
            timeoutSeconds = 900,
        )
    }

    // ---------------------------------------------------------------- secrets

    private fun NodeState.toSecrets(): NodeSecrets? {
        if (setupToken == null || serverSecret == null || adminEmail == null || adminPassword == null) return null
        return NodeSecrets(
            setupToken = setupToken,
            serverSecret = serverSecret,
            adminEmail = adminEmail,
            adminPassword = adminPassword,
            crowdsecBouncerKey = facts[CROWDSEC_KEY_FACT].orEmpty(),
        )
    }

    private fun placeholderSecrets() = NodeSecrets(
        setupToken = PLACEHOLDER,
        serverSecret = PLACEHOLDER,
        adminEmail = NodeTemplates.adminEmail(ctx.config),
        adminPassword = PLACEHOLDER,
        crowdsecBouncerKey = PLACEHOLDER,
    )

    private fun placeholderClusterSecrets() = ClusterSecrets(
        postgres = PLACEHOLDER,
        replication = PLACEHOLDER,
        valkey = PLACEHOLDER,
        rest = PLACEHOLDER,
        pangolin = PLACEHOLDER,
        rewind = PLACEHOLDER,
        etcd = PLACEHOLDER,
        sentinel = PLACEHOLDER,
    )

    private fun sharedSecrets(shared: ClusterPangolinState, crowdsecKey: String) = NodeSecrets(
        setupToken = shared.setupToken,
        serverSecret = shared.serverSecret,
        adminEmail = shared.adminEmail,
        adminPassword = shared.adminPassword,
        crowdsecBouncerKey = crowdsecKey,
        cluster = true,
    )

    /**
     * In a cluster, all nodes share the server secret and the admin. The state of the controller
     * keeps them, and each node has a copy. If the controller loses its state, it recovers them from
     * a node. Each node keeps its own CrowdSec key.
     */
    private suspend fun ensureClusterSecrets(node: NodeConfig, ssh: SshSession): NodeSecrets {
        val knownKey = ctx.state.node(node.id).facts[CROWDSEC_KEY_FACT]
        val known = ctx.state.snapshot().clusterPangolin
        if (known != null && !knownKey.isNullOrEmpty()) return sharedSecrets(known, knownKey)
        val remote = remoteSecrets(ssh)
            ?.let { runCatching { LENIENT_JSON.decodeFromString<NodeSecrets>(it) }.getOrNull() }
        val shared = known ?: storeClusterSecrets(node, remote)
        val crowdsecKey = knownKey?.takeIf { it.isNotEmpty() }
            ?: remote?.crowdsecBouncerKey?.takeIf { it.isNotEmpty() }
            ?: Crypto.token(CROWDSEC_KEY_LENGTH)
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (CROWDSEC_KEY_FACT to crowdsecKey)) }
        return sharedSecrets(shared, crowdsecKey)
    }

    /**
     * Stores the secrets of the cluster: the ones from the file of the node ([remote]), or new ones.
     * Returns the secrets that the state keeps.
     */
    private suspend fun storeClusterSecrets(node: NodeConfig, remote: NodeSecrets?): ClusterPangolinState {
        val recovered = remote?.takeIf { it.cluster }?.let {
            ClusterPangolinState(
                serverSecret = it.serverSecret,
                setupToken = it.setupToken,
                adminEmail = it.adminEmail,
                adminPassword = it.adminPassword,
                bootstrapped = true,
            )
        }
        val candidate = recovered ?: ClusterPangolinState(
            serverSecret = Crypto.token(SERVER_SECRET_LENGTH),
            setupToken = Crypto.setupToken(),
            adminEmail = NodeTemplates.adminEmail(ctx.config),
            adminPassword = Crypto.password(),
        )
        // The assessment of a different node can store its secrets first. Then those stay.
        val kept = ctx.state
            .update { current -> current.copy(clusterPangolin = current.clusterPangolin ?: candidate) }
            .clusterPangolin!!
        if (kept === candidate) {
            if (recovered != null) {
                log.info(DOMAIN, "recovered the cluster's Pangolin secrets from the node", node.id)
            } else {
                log.change(DOMAIN, "generated the cluster's Pangolin secrets (admin, server secret, setup token)")
            }
        }
        return kept
    }

    /**
     * Reads the secrets file of the node. Returns null if the node has none. Throws if the read
     * fails, so a failed read never causes new secrets.
     */
    private suspend fun remoteSecrets(ssh: SshSession): String? {
        val path = "$dir/${NodeTemplates.SECRETS}"
        val quotedPath = shQuote(path)
        val result = ssh.runScript("[ -s $quotedPath ] || exit 0; cat $quotedPath", timeoutSeconds = 60)
        val text = result.stdout.trim()
        check(result.ok && (text.isEmpty() || text.startsWith("{"))) { "could not read $path on the node" }
        return text.ifEmpty { null }
    }

    /** The secrets on the node are authoritative. If the local cache loses them, the node fills it again. */
    private suspend fun ensureSecrets(node: NodeConfig, ssh: SshSession): NodeSecrets {
        if (clustered) return ensureClusterSecrets(node, ssh)
        ctx.state.node(node.id).toSecrets()?.takeIf { it.crowdsecBouncerKey.isNotEmpty() }?.let { return it }
        val remote = remoteSecrets(ssh)
        val secrets = if (remote != null) {
            log.info(DOMAIN, "recovered node secrets from the node", node.id)
            LENIENT_JSON.decodeFromString<NodeSecrets>(remote).let {
                if (it.crowdsecBouncerKey.isEmpty()) {
                    it.copy(crowdsecBouncerKey = Crypto.token(CROWDSEC_KEY_LENGTH))
                } else {
                    it
                }
            }
        } else {
            log.change(DOMAIN, "generated node secrets (admin, server secret, setup token)", node.id)
            NodeSecrets(
                setupToken = Crypto.setupToken(),
                serverSecret = Crypto.token(SERVER_SECRET_LENGTH),
                adminEmail = NodeTemplates.adminEmail(ctx.config),
                adminPassword = Crypto.password(),
                crowdsecBouncerKey = Crypto.token(CROWDSEC_KEY_LENGTH),
            )
        }
        ctx.state.updateNode(node.id) {
            it.copy(
                setupToken = secrets.setupToken,
                serverSecret = secrets.serverSecret,
                adminEmail = secrets.adminEmail,
                adminPassword = secrets.adminPassword,
                facts = it.facts + (CROWDSEC_KEY_FACT to secrets.crowdsecBouncerKey),
            )
        }
        return secrets
    }

    fun renderAll(node: NodeConfig, desired: NodeDesired, secrets: NodeSecrets, slot: Int): List<RenderedFile> {
        val link = ctx.config.cluster?.let { clusterConfig ->
            val database = if (ctx.dryRun || cluster == null) placeholderClusterSecrets() else cluster.secrets()
            ClusterLink(Mesh.nodeAddress(clusterConfig.mesh, slot), database)
        }
        return NodeTemplates.staticFiles(
            config = ctx.config,
            node = node,
            secrets = secrets,
            rawPorts = desired.rawPorts,
            slot = slot,
            cluster = link,
        )
    }

    private fun hash(files: List<RenderedFile>): String =
        Crypto.fnv64(
            files.sortedBy { it.path }
                .joinToString("\u0000") { "${it.path}\u0001${it.mode}\u0001${it.content}" },
        )

    // ---------------------------------------------------------------- provisioning steps

    /** Steps 1-4: everything the node needs before its stack (and its cluster member). */
    private suspend fun provisionHost(node: NodeConfig, ssh: SshSession, desired: NodeDesired) {
        // 1. Preflight
        val facts = preflight(node, ssh)
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + facts + ("reachable" to "true")) }

        // 2. Docker
        if (node.installDocker) ssh.runScriptOrThrow("docker", DOCKER_SCRIPT, timeoutSeconds = 1200)
        ssh.runScriptOrThrow(
            step = "docker-check",
            script = "docker version --format '{{.Server.Version}}' && docker compose version --short",
            timeoutSeconds = 60,
        )

        // 3. Firewall
        if (node.firewall) {
            ssh.runScriptOrThrow("firewall", firewallScript(node, desired), timeoutSeconds = 300)
        }

        // 4. Kernel network tuning (best effort: some virtualization types lock these knobs).
        // Before the stack, so new container namespaces inherit the host's congestion control.
        runTuningStep(node, ssh, tuningScript())
    }

    private suspend fun provisionStack(node: NodeConfig, ssh: SshSession, files: List<RenderedFile>) {
        // 5 + 6. Stack files and secrets (restrictive modes), then 7. bring up and wait for health.
        val output = ssh.runScriptOrThrow("stack", stackScript(files), timeoutSeconds = 1200)
        output.lines()
            .filter { it.startsWith(CHANGED) }
            .forEach { log.change(DOMAIN, "updated ${it.removePrefix(CHANGED)}", node.id) }

        // What the edge namespace (Gerbil + Traefik) actually uses.
        runTuningStep(node, ssh, EDGE_TCP_SCRIPT)
    }

    private suspend fun runTuningStep(node: NodeConfig, ssh: SshSession, script: String) {
        val lines = ssh.runScriptOrThrow("tuning", script, timeoutSeconds = 120).lines()
        lines.forEach { line ->
            when {
                line.startsWith(CHANGED) -> log.change(DOMAIN, line.removePrefix(CHANGED), node.id)
                line.startsWith(WARN) -> log.warn(DOMAIN, line.removePrefix(WARN), node.id)
            }
        }
        val tuningFacts = lines
            .filter { it.startsWith(FACT) }
            .associate { it.removePrefix(FACT).substringBefore('=') to it.substringAfter('=') }
        if (tuningFacts.isNotEmpty()) {
            ctx.state.updateNode(node.id) { it.copy(facts = it.facts + tuningFacts) }
        }
    }

    private fun tuningScript(): String {
        val src = shQuote("$dir/${NodeTemplates.SYSCTL}")
        val dst = "/etc/sysctl.d/99-homegap.conf"
        return if (ctx.config.pangolin.tcpTuning) {
            """
            |modprobe tcp_bbr 2>/dev/null || true
            |mkdir -p ${shQuote("$dir/config")}
            |cat > $src <<'HOMEGAP_SYSCTL'
            |${NodeTemplates.sysctlConf().trimEnd()}
            |HOMEGAP_SYSCTL
            |if ! cmp -s $src $dst 2>/dev/null; then install -m 0644 $src $dst; echo "CHANGED installed TCP tuning ($dst)"; fi
            |sysctl -p $dst >/dev/null 2>&1 || echo "WARN some TCP tuning sysctls could not be applied (container/VPS kernel restrictions?)"
            |echo "FACT tcp_cc=$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
            |echo "FACT qdisc=$(sysctl -n net.core.default_qdisc 2>/dev/null || echo unknown)"
            """.trimMargin()
        } else {
            """
            |if [ -f $dst ]; then rm -f $dst; echo "CHANGED removed TCP tuning (live values stay until reboot)"; fi
            |echo "FACT tcp_cc=$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || echo unknown)"
            |echo "FACT qdisc=$(sysctl -n net.core.default_qdisc 2>/dev/null || echo unknown)"
            """.trimMargin()
        }
    }

    private suspend fun preflight(node: NodeConfig, ssh: SshSession): Map<String, String> {
        val output = ssh.runScriptOrThrow("preflight", PREFLIGHT_SCRIPT, timeoutSeconds = 120)
        val facts = output.lines()
            .mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }
            .toMap()
        val os = facts["ID"] ?: "unknown"
        val version = facts["VERSION_ID"] ?: "unknown"
        val arch = facts["ARCH"] ?: "unknown"
        check(os == "ubuntu" && version in SUPPORTED_UBUNTU) {
            "unsupported OS $os $version (need Ubuntu ${SUPPORTED_UBUNTU.joinToString("/")})"
        }
        check(arch in SUPPORTED_ARCHITECTURES) { "unsupported architecture $arch" }
        check(node.os.isBlank() || node.os == "ubuntu-$version") {
            "config declares os=${node.os} but host runs ubuntu-$version"
        }
        check(facts["SUDO"] != "no") { "user ${node.sshUser} cannot run sudo non-interactively" }
        return facts.mapKeys { (name, _) -> name.lowercase() } + ("os" to "ubuntu-$version")
    }

    private fun firewallScript(node: NodeConfig, desired: NodeDesired): String =
        FirewallRules.script(FirewallRules.specs(node, desired.rawPorts, ctx.config.cluster?.mesh?.port))

    private fun stackScript(files: List<RenderedFile>): String = buildString {
        val installDir = shQuote(dir)
        val publicDirs = listOf(
            "",
            "/config",
            "/config/traefik",
            "/config/traefik/dynamic",
            "/config/letsencrypt",
            "/config/traefik/logs",
            "/config/certificates",
            "/${MaintenancePage.DIR}",
        ).joinToString(" ") { "$installDir$it" }
        appendLine("install -d -m 0755 $publicDirs")
        appendLine("install -d -m 0700 $installDir/secrets $installDir/config/traefik/certs")
        // A node orders no certificate, so this ACME store is always empty. Pangolin's certificate
        // sync reads the file and accepts an empty JSON object.
        val acme = shQuote("$dir/config/letsencrypt/acme.json")
        appendLine("printf '{}\\n' > $acme; chmod 600 $acme")
        appendLine("RESTART=\"\"")
        for (file in files) {
            val onChange = buildString {
                append("echo \"CHANGED ${file.path}\"")
                file.restarts?.let { append($$"; RESTART=\"$RESTART $$it\"") }
            }
            appendLine(writeFile("$dir/${file.path}", file.content, file.mode, onChange))
        }
        appendLine(SshSession.ownInstallDir(dir))
        appendLine("cd $installDir")
        // After a node reboot, Pangolin can start before the cluster member has the database ready.
        // Then Pangolin stops its migrations and never listens. `up` does not touch a container
        // that runs, so the script restarts Pangolin here.
        val health = "docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' pangolin 2>/dev/null"
        val restartPangolin = "docker compose restart pangolin >/dev/null 2>&1 || true"
        val reportRestart = "echo \"CHANGED restarted Pangolin (it was running but not answering)\""
        appendLine($$"if [ \"$($$health)\" = unhealthy ]; then $$restartPangolin; $$reportRestart; fi")
        appendLine("docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20")
        val restartService = $$"docker compose restart \"$s\" >/dev/null 2>&1 || true"
        appendLine($$"for s in $(echo $RESTART | tr ' ' '\\n' | sort -u); do $$restartService; done")
        appendLine(
            $$"""
            |ok=0
            |for i in $(seq 1 120); do
            |  if curl -fsS -o /dev/null http://127.0.0.1:3000/api/v1/ 2>/dev/null; then ok=1; break; fi
            |  sleep 2
            |done
            |if [ "$ok" != 1 ]; then echo "Pangolin did not become healthy (its log is on the Diagnostics page)" >&2; docker compose ps >&2; exit 1; fi
            """.trimMargin(),
        )
    }

    /** Returns the shell commands that write [content] to [path] and run [onChange] if the file changes. */
    private fun writeFile(path: String, content: String, mode: String, onChange: String): String {
        val encoded = Base64.encode(content.encodeToByteArray())
        val target = shQuote(path)
        val staged = "$target.homegap-new"
        return """
            |(umask 077; printf '%s' '$encoded' | base64 -d > $staged)
            |if ! cmp -s $staged $target 2>/dev/null; then chmod $mode $staged; mv -f $staged $target; $onChange; else rm -f $staged; chmod $mode $target; fi
        """.trimMargin()
    }

    private suspend fun checkDrift(ssh: SshSession, files: List<RenderedFile>): List<String> {
        val containerStatus = $$"st=$(docker inspect -f '{{.State.Status}}' $s 2>/dev/null || echo missing)"
        val reportStopped = $$"[ \"$st\" = running ] || echo \"DRIFT container $s $st\""
        val script = buildString {
            for ((path, content) in files) {
                val decode = "printf '%s' '${Base64.encode(content.encodeToByteArray())}' | base64 -d"
                val target = shQuote("$dir/$path")
                appendLine("$decode | cmp -s - $target 2>/dev/null || echo \"DRIFT file $path\"")
            }
            appendLine("cd ${shQuote(dir)} 2>/dev/null || { echo 'DRIFT install dir missing'; exit 0; }")
            appendLine("for s in pangolin gerbil traefik; do $containerStatus; $reportStopped; done")
        }
        val result = ssh.runScript(script, timeoutSeconds = 120)
        if (!result.ok) return listOf("check failed: ${result.stderr.trim().take(ERROR_SUMMARY_LENGTH)}")
        return result.stdout.lines().filter { it.startsWith(DRIFT) }.map { it.removePrefix(DRIFT) }
    }

    // ---------------------------------------------------------------- headless bootstrap

    /** Returns the handle of a paused node. Returns null if the state has no key or the port forward fails. */
    private suspend fun connectApi(
        node: NodeConfig,
        ssh: SshSession,
        slot: Int,
        apiKey: Pair<String, String>?,
    ): ReadyNode? {
        val (keyId, key) = apiKey ?: return null
        return runCatchingCancellable {
            ReadyNode(node, PangolinClient(ctx.localHttp, integrationApiUrl(ssh, slot), "$keyId.$key"))
        }.getOrNull()
    }

    private suspend fun integrationApiUrl(ssh: SshSession, slot: Int): String {
        val port = ssh.forward(INTEGRATION_API_PORT, LOCAL_PORT_BASE + slot * PORTS_PER_SLOT)
        return "http://127.0.0.1:$port/v1"
    }

    private suspend fun dashboardApiUrl(ssh: SshSession, slot: Int): String {
        val port = ssh.forward(DASHBOARD_PORT, LOCAL_PORT_BASE + slot * PORTS_PER_SLOT + DASHBOARD_PORT_OFFSET)
        return "http://127.0.0.1:$port/api/v1"
    }

    private suspend fun dashboardClient(ssh: SshSession, slot: Int) =
        PangolinBootstrapClient(ctx.localHttp, dashboardApiUrl(ssh, slot))

    /** Returns a dashboard client with the session of the server admin. */
    private suspend fun adminClient(access: NodeAccess): PangolinBootstrapClient =
        dashboardClient(access.ssh, access.slot)
            .also { it.login(access.secrets.adminEmail, access.secrets.adminPassword) }

    /** The API key of the orchestrator: one for each node, or the one key of the cluster in the shared database. */
    private fun cachedKey(nodeId: String): Pair<String, String>? {
        if (clustered) {
            val shared = ctx.state.snapshot().clusterPangolin ?: return null
            return keyPair(shared.apiKeyId, shared.apiKey)
        }
        val nodeState = ctx.state.node(nodeId)
        return keyPair(nodeState.apiKeyId, nodeState.apiKey)
    }

    private fun keyPair(keyId: String?, key: String?): Pair<String, String>? =
        if (keyId != null && key != null) keyId to key else null

    private fun clusterKeyActions(): String? = ctx.state.snapshot().clusterPangolin?.keyActions

    /** Applies [change] to the Pangolin state of the cluster. Does nothing if the cluster has no such state. */
    private suspend fun updateClusterPangolin(change: (ClusterPangolinState) -> ClusterPangolinState) {
        ctx.state.update { current -> current.copy(clusterPangolin = current.clusterPangolin?.let(change)) }
    }

    private suspend fun storeKey(nodeId: String, keyId: String, key: String) {
        if (clustered) {
            updateClusterPangolin { it.copy(apiKeyId = keyId, apiKey = key, bootstrapped = true) }
        } else {
            ctx.state.updateNode(nodeId) { it.copy(apiKeyId = keyId, apiKey = key) }
        }
    }

    private suspend fun bootstrap(access: NodeAccess): ReadyNode {
        val integrationUrl = integrationApiUrl(access.ssh, access.slot)
        fastPath(access, integrationUrl, warn = !clustered)?.let { return it }
        if (!clustered) return mint(access, integrationUrl)
        // One node at a time creates the admin, the org and the key of the cluster. The other
        // nodes then use them.
        return clusterKeyLock.withLock {
            fastPath(access, integrationUrl, warn = true) ?: mint(access, integrationUrl)
        }
    }

    /** Returns the node with its cached key if Pangolin accepts the key. Returns null if the node needs a new key. */
    private suspend fun fastPath(access: NodeAccess, integrationUrl: String, warn: Boolean): ReadyNode? {
        val node = access.node
        val (keyId, key) = cachedKey(node.id) ?: return null
        val api = PangolinClient(ctx.localHttp, integrationUrl, "$keyId.$key")
        // Only a refusal from Pangolin means that the key needs a replacement. A Pangolin that
        // still starts gets more attempts, because a mint costs an admin login and retires the key
        // that every node uses.
        var attempt = 0
        while (true) {
            val failure = runCatchingCancellable { api.getOrg(ctx.config.pangolin.orgId) }.exceptionOrNull() ?: break
            val rejected = failure is PangolinApiException && failure.status in KEY_REJECTED_STATUSES
            if (rejected || ++attempt >= KEY_CHECK_ATTEMPTS) {
                // A Pangolin that does not answer (a database failover) cannot mint a key either.
                val down = failure !is PangolinApiException || failure.status >= HTTP_SERVER_ERROR
                check(rejected || !down) { "Pangolin does not answer (${failure.message})" }
                if (warn) {
                    val problem = if (rejected) "rejected" else "not usable"
                    log.warn(DOMAIN, "cached API key $problem (${failure.message}); minting a new one", node.id)
                }
                return null
            }
            delay(RETRY_DELAY)
        }
        ensureKeyActions(access, keyId)
        maybeActivateLicense(access)
        return ReadyNode(node, api, dashboardApiUrl(access.ssh, access.slot))
    }

    /**
     * Grants the key the actions that are new since its mint (one login for each change). If
     * [justMinted] is true, the key already has the current actions, and the function only records
     * them.
     */
    private suspend fun ensureKeyActions(access: NodeAccess, keyId: String, justMinted: Boolean = false) {
        // One node at a time updates the one key of the cluster. The lock is not reentrant. If the
        // bootstrap or a different node holds it, a later pass does the update.
        if (clustered && !justMinted && clusterKeyActions() != KEY_ACTIONS_HASH) {
            if (!clusterKeyLock.tryLock()) return
            try {
                if (clusterKeyActions() != KEY_ACTIONS_HASH) {
                    updateKeyActions(access, keyId, justMinted = false)
                }
            } finally {
                clusterKeyLock.unlock()
            }
            return
        }
        updateKeyActions(access, keyId, justMinted)
    }

    private suspend fun updateKeyActions(access: NodeAccess, keyId: String, justMinted: Boolean) {
        val node = access.node
        val recorded = if (clustered) clusterKeyActions() else ctx.state.node(node.id).facts[KEY_ACTIONS_FACT]
        if (recorded == KEY_ACTIONS_HASH) return
        runCatchingCancellable {
            if (!justMinted) {
                adminClient(access).setApiKeyActions(keyId, PangolinBootstrapClient.ORCHESTRATOR_ACTIONS)
                log.change(DOMAIN, "updated the orchestrator API key's permissions", node.id.takeIf { !clustered })
            }
            if (clustered) {
                updateClusterPangolin { it.copy(keyActions = KEY_ACTIONS_HASH) }
            } else {
                ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (KEY_ACTIONS_FACT to KEY_ACTIONS_HASH)) }
            }
        }.onFailure { log.warn(DOMAIN, "could not update the API key's permissions: ${it.message}", node.id) }
    }

    /** Creates the admin and the org if Pangolin has none, then mints a new API key for the orchestrator. */
    private suspend fun mint(access: NodeAccess, integrationUrl: String): ReadyNode {
        val node = access.node
        val secrets = access.secrets
        val org = ctx.config.pangolin.orgId

        val boot = dashboardClient(access.ssh, access.slot)
        waitFor("Pangolin API") {
            runCatchingCancellable { boot.initialSetupComplete() }.isSuccess
        }

        if (!boot.initialSetupComplete()) {
            boot.setServerAdmin(secrets.adminEmail, secrets.adminPassword, secrets.setupToken)
            log.change(DOMAIN, "created server admin ${secrets.adminEmail}", node.id)
        }
        try {
            boot.login(secrets.adminEmail, secrets.adminPassword)
        } catch (e: PangolinApiException) {
            val remedy = if (clustered) {
                "restore the cluster's Pangolin secrets from any node's $dir/${NodeTemplates.SECRETS}."
            } else {
                "restore $dir/${NodeTemplates.SECRETS} or wipe the node's $dir/config/db to re-bootstrap."
            }
            throw IllegalStateException(
                "cannot log in as ${secrets.adminEmail} (${e.message}). " +
                    "The node has an admin the controller does not know; $remedy",
            )
        }
        if (!boot.orgExists(org)) {
            boot.createOrg(org, ctx.config.pangolin.orgName)
            log.change(DOMAIN, "created org $org", node.id)
        }
        val (keyId, key) = boot.createRootApiKey(API_KEY_NAME)
        boot.setApiKeyActions(keyId, PangolinBootstrapClient.ORCHESTRATOR_ACTIONS)
        // Retire keys minted by earlier controller instances.
        runCatchingCancellable {
            boot.listRootApiKeys()
                .filter { (id, name) -> name == API_KEY_NAME && id != keyId }
                .forEach { (id, _) -> boot.deleteRootApiKey(id) }
        }
        val minted = if (clustered) "minted the cluster's orchestrator API key" else "minted orchestrator API key"
        log.change(DOMAIN, minted, node.id)
        storeKey(node.id, keyId, key)
        // Minted with the current actions: record them without another call.
        ensureKeyActions(access, keyId, justMinted = true)
        maybeActivateLicense(access, boot)
        return ReadyNode(
            node = node,
            api = PangolinClient(ctx.localHttp, integrationUrl, "$keyId.$key"),
            dashboardUrl = dashboardApiUrl(access.ssh, access.slot),
        )
    }

    /**
     * A cluster has one license, and the shared database stores it. The first node that can
     * activates it. Then each other Pangolin that runs reads it again.
     */
    private suspend fun maybeActivateClusterLicense(access: NodeAccess, existing: PangolinBootstrapClient?) {
        val node = access.node
        val shared = ctx.state.snapshot().clusterPangolin ?: return
        if (shared.licenseActivated) {
            if (node.id in shared.licenseRecheck) recheckClusterLicense(access, existing)
            return
        }
        val ref = ctx.config.pangolin.licenseKey() ?: return
        val key = ref.resolve() ?: run {
            if (!licenseWarned) {
                log.warn(DOMAIN, "license key ${ref.describe()} not set; EE wildcard resources will be refused")
            }
            licenseWarned = true
            return
        }
        licenseWarned = false
        val keyHash = Crypto.fnv64(key)
        if (shared.licenseRejected == keyHash) return
        runCatchingCancellable {
            val boot = existing ?: adminClient(access)
            boot.activateLicense(key)
            recordClusterLicense(node)
            log.change(DOMAIN, "activated the cluster's Pangolin EE license", node.id)
        }.onFailure { e ->
            val message = e.message.orEmpty()
            when {
                message.contains(LICENSE_ALREADY_STORED) -> recordClusterLicense(node)
                message.contains(LICENSE_OTHER_INSTANCE, ignoreCase = true) -> {
                    updateClusterPangolin { it.copy(licenseRejected = keyHash) }
                    log.error(
                        DOMAIN,
                        "EE license rejected: the key is already bound to another Pangolin instance " +
                            "(one key covers the whole cluster).",
                    )
                }
                else -> log.error(DOMAIN, "license activation failed: $message", node.id)
            }
        }
    }

    /** Tells the Pangolin of the node to read the license that a different node activated. */
    private suspend fun recheckClusterLicense(access: NodeAccess, existing: PangolinBootstrapClient?) {
        val node = access.node
        runCatchingCancellable {
            val boot = existing ?: adminClient(access)
            boot.recheckLicense()
            updateClusterPangolin { it.copy(licenseRecheck = it.licenseRecheck - node.id) }
            log.change(DOMAIN, "picked up the cluster's EE license", node.id)
        }.onFailure { log.warn(DOMAIN, "license recheck failed: ${it.message}", node.id) }
    }

    /** Records that the cluster has its license. Each other node must read it again. */
    private suspend fun recordClusterLicense(activatedOn: NodeConfig) {
        val others = ctx.config.nodes.map { it.id }.toSet() - activatedOn.id
        updateClusterPangolin { it.copy(licenseActivated = true, licenseRecheck = others) }
    }

    private suspend fun maybeActivateLicense(access: NodeAccess, existing: PangolinBootstrapClient? = null) {
        if (clustered) return maybeActivateClusterLicense(access, existing)
        val node = access.node
        val ref = node.licenseKey(ctx.config.pangolin) ?: return
        if (ctx.state.node(node.id).licenseActivated) return
        val key = ref.resolve() ?: run {
            log.warn(DOMAIN, "license key ${ref.describe()} not set; EE wildcard resources will be refused", node.id)
            return
        }
        // The license server already rejected this key for this node. A key that is bound to a
        // host stays rejected, so a pass does not try it again. A different key or a new
        // provisioning clears this.
        val keyHash = Crypto.fnv64(key)
        if (ctx.state.node(node.id).facts[LICENSE_REJECTED_FACT] == keyHash) {
            log.warn(
                DOMAIN,
                "EE license was rejected for this node (see earlier error); wildcard resources unavailable",
                node.id,
            )
            return
        }
        runCatchingCancellable {
            val boot = existing ?: adminClient(access)
            boot.activateLicense(key)
            ctx.state.updateNode(node.id) { it.copy(licenseActivated = true) }
            log.change(DOMAIN, "activated Pangolin EE license", node.id)
        }.onFailure { e ->
            val message = e.message.orEmpty()
            when {
                // Only the local "this instance already stores the key" case means success.
                message.contains(LICENSE_ALREADY_STORED) -> {
                    ctx.state.updateNode(node.id) { it.copy(licenseActivated = true) }
                }
                message.contains(LICENSE_OTHER_INSTANCE, ignoreCase = true) -> {
                    ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (LICENSE_REJECTED_FACT to keyHash)) }
                    log.error(
                        DOMAIN,
                        "EE license rejected: the key is already bound to another Pangolin instance. " +
                            "Personal keys are per host; give this node its own key via nodes[].license_key_env.",
                        node.id,
                    )
                }
                else -> log.error(DOMAIN, "license activation failed: $message", node.id)
            }
        }
    }

    // ---------------------------------------------------------------- cluster

    /**
     * Pangolin needs the node's cluster member (its database replica and HAProxy) before it can
     * start. The cluster step of later passes prepares the member, so a background job waits.
     */
    private suspend fun awaitClusterMember(node: NodeConfig, wait: Boolean) {
        val clusterReconciler = cluster ?: return
        if (clusterReconciler.memberReady(node.id)) return
        check(wait) { "this node's cluster database member is not ready yet" }
        log.info(DOMAIN, "waiting for this node's cluster database member", node.id)
        val deadline = nowMillis() + CLUSTER_MEMBER_WAIT.inWholeMilliseconds
        while (!clusterReconciler.memberReady(node.id)) {
            check(nowMillis() <= deadline) {
                "this node's cluster database member did not become ready in $CLUSTER_MEMBER_WAIT"
            }
            delay(MEMBER_POLL_DELAY)
        }
    }

    /**
     * The first Pangolin on an empty cluster database migrates it and creates the admin. Only one
     * node can do that. Until the cluster has its Pangolin, the nodes start one at a time.
     */
    private suspend fun <T> firstClusterStart(node: NodeConfig, block: suspend () -> T): T {
        if (!clustered || ctx.state.snapshot().clusterPangolin?.bootstrapped == true) return block()
        if (firstStartLock.isLocked) {
            log.info(DOMAIN, "waiting for another node to set up the cluster's Pangolin", node.id)
        }
        return firstStartLock.withLock { block() }
    }

    /**
     * A Pangolin upgrade migrates the shared database when the first node starts the new version.
     * That node saves a dump first, so a failed upgrade can restore it. Later nodes find the
     * migrated database and save nothing.
     */
    private fun clusterDumpStep(node: NodeConfig): String {
        if (!clustered || cluster == null) return NO_OP
        val image = ctx.config.pangolin.pangolinImage(cluster = true)
        val first = ctx.state.snapshot().nodes.none { (id, nodeState) ->
            id != node.id && nodeState.facts[PANGOLIN_IMAGE_FACT] == image
        }
        if (!first) return NO_OP
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

    /** Restores the dump from [clusterDumpStep], if the backup has one. */
    private fun clusterRestoreStep(): String {
        if (!clustered || cluster == null) return NO_OP
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
        $$"(umask 077; printf 'PGPASSWORD=%s\\n' $${shQuote(cluster!!.secrets().pangolin)} > \"$B/pg.env\")"

    /** Runs the checks after provisioning. Returns the checks that failed. */
    private suspend fun verify(node: NodeConfig, ssh: SshSession): List<String> {
        val result = ssh.runScript(
            script = """
            |cd ${shQuote(dir)}
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "CHECK gerbil-udp ok" || echo "CHECK gerbil-udp missing"
            |(ss -lun 2>/dev/null | grep -q ':21820 ') && echo "CHECK gerbil-clients-udp ok" || echo "CHECK gerbil-clients-udp missing"
            |curl -fsS -o /dev/null http://127.0.0.1:3003/v1/ && echo "CHECK integration-api ok" || echo "CHECK integration-api missing"
            """.trimMargin(),
            timeoutSeconds = 60,
        )
        val missing = result.stdout.lines()
            .filter { it.startsWith(CHECK) && it.endsWith("missing") }
            .map { it.removePrefix(CHECK) }
        missing.forEach { log.warn(DOMAIN, "verify: $it", node.id) }
        return missing
    }

    private suspend fun waitFor(what: String, attempts: Int = 60, check: suspend () -> Boolean) {
        repeat(attempts) {
            if (check()) return
            delay(RETRY_DELAY)
        }
        throw IllegalStateException("$what not reachable")
    }

    /**
     * Stops the node stack and removes it with its data and secrets (decommission). If you add the
     * node again, it starts as a new node.
     */
    suspend fun decommission(node: NodeConfig) {
        val depth = dir.trimEnd('/').count { it == '/' }
        check(dir.startsWith("/") && depth >= 2) { "install_dir '$dir' is not safe to remove" }
        val ssh = ctx.ssh.session(node)
        val installDir = shQuote(dir)
        ssh.runScriptOrThrow(
            step = "decommission",
            script = "cd $installDir 2>/dev/null && docker compose down --remove-orphans --volumes || true",
            timeoutSeconds = 300,
        )
        ssh.runScript(ExitNodes.teardownScript(dir), timeoutSeconds = 120)
        if (clustered) {
            val clusterDir = shQuote("$dir/cluster")
            ssh.runScript(
                "cd $clusterDir 2>/dev/null && docker compose down --remove-orphans || true; rm -rf $clusterDir",
                timeoutSeconds = 300,
            )
            ssh.runScript(Mesh.TEARDOWN, timeoutSeconds = 60)
        }
        // The directory itself can be a mount point, so its contents go first.
        val removeContents = "find $installDir -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true"
        val removeDir = "rmdir $installDir 2>/dev/null || true"
        val checkEmpty = $$"[ -z \"$(ls -A $$installDir 2>/dev/null)\" ]"
        ssh.runScriptOrThrow("decommission", "$removeContents; $removeDir; $checkEmpty", timeoutSeconds = 120)
        ctx.ssh.drop(node.id)
    }

    companion object {
        /** The first local port for the forwards to the nodes. */
        const val LOCAL_PORT_BASE = 47100

        /** Previous stack kept on the node during an update, relative to install_dir. */
        const val ROLLBACK_DIR = ".homegap-rollback"
        val SUPPORTED_UBUNTU = setOf("22.04", "24.04", "26.04", "26.10")

        /** The Pangolin image a node last provisioned successfully. */
        const val PANGOLIN_IMAGE_FACT = "pangolinImage"

        /** The node's running stack is the cluster's (not a standalone Pangolin). */
        const val CLUSTER_STACK_FACT = "clusterStack"

        private const val DOMAIN = "nodes"
        private val LENIENT_JSON = Json { ignoreUnknownKeys = true }
        private val SUPPORTED_ARCHITECTURES = setOf("x86_64", "aarch64")

        /** The value that a dry run shows in place of a secret. */
        private const val PLACEHOLDER = "<generated>"

        /** The shell command that does nothing. */
        private const val NO_OP = ":"

        // Facts in the state of a node.
        private const val KEY_ACTIONS_FACT = "keyActions"
        private const val CROWDSEC_KEY_FACT = "crowdsecKey"
        private const val LICENSE_REJECTED_FACT = "licenseRejected"

        private const val SERVER_SECRET_LENGTH = 48
        private const val CROWDSEC_KEY_LENGTH = 40

        // Each node has a block of local ports for the forwards to its Pangolin.
        private const val PORTS_PER_SLOT = 10
        private const val DASHBOARD_PORT_OFFSET = 5
        private const val INTEGRATION_API_PORT = 3003
        private const val DASHBOARD_PORT = 3000

        private const val API_KEY_NAME = "homegap-orchestrator"

        /** The number of times the bootstrap tries a cached key while Pangolin starts, with [RETRY_DELAY] between. */
        private const val KEY_CHECK_ATTEMPTS = 10
        private val KEY_REJECTED_STATUSES = setOf(401, 403)
        private const val HTTP_SERVER_ERROR = 500
        private val RETRY_DELAY = 2.seconds
        private val KEY_ACTIONS_HASH =
            Crypto.fnv64(PangolinBootstrapClient.ORCHESTRATOR_ACTIONS.sorted().joinToString(","))

        private val CLUSTER_MEMBER_WAIT = 20.minutes
        private val MEMBER_POLL_DELAY = 5.seconds

        // Parts of the error text of Pangolin for a license key.
        private const val LICENSE_ALREADY_STORED = "License key already exists"
        private const val LICENSE_OTHER_INSTANCE = "different instance"

        // The kinds of run in the history of a node.
        private const val RUN_PROVISION = "provision"
        private const val RUN_UPDATE = "update"

        private const val ERROR_LENGTH = 2000
        private const val RUN_ERROR_LENGTH = 300
        private const val ERROR_SUMMARY_LENGTH = 200

        // The phases of a provisioning job, as the rollout strip of the dashboard shows them.
        private const val PHASE_HOST = "preparing the host"
        private const val PHASE_CLUSTER = "waiting for the cluster"
        private const val PHASE_BACKUP = "backing up the stack"
        private const val PHASE_WRITE = "writing the stack"
        private const val PHASE_START = "starting the stack"
        private const val PHASE_VERIFY = "verifying"
        private const val PHASE_TUNNEL = "waiting for the tunnel"

        // Lines in the output of the scripts.
        private const val CHANGED = "CHANGED "
        private const val WARN = "WARN "
        private const val FACT = "FACT "
        private const val DRIFT = "DRIFT "
        private const val CHECK = "CHECK "
        private const val DB_SNAPSHOT = "DBSNAP "

        /** Lets one node at a time start the first Pangolin of the cluster, which migrates the empty database. */
        private val firstStartLock = Mutex()

        /** Lets one node at a time mint the one API key of the cluster. A mint retires each other key. */
        private val clusterKeyLock = Mutex()

        /** The log shows the missing license key of the cluster once, not in each pass. */
        @Volatile
        private var licenseWarned = false

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
