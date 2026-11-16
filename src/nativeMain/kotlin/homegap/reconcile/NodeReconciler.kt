package homegap.reconcile

import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.node.AgentSpec
import homegap.platform.Crypto
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.Apt
import homegap.provision.FirewallRules
import homegap.provision.MaintenancePage
import homegap.provision.Mesh
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.RenderedFile
import homegap.state.NodeState
import homegap.state.RunRecord
import homegap.state.UpdateState
import homegap.state.withRun
import kotlin.io.encoding.Base64

/**
 * Node lifecycle domain: turns a bare SSH-reachable Ubuntu VPS into a node of HomeGAP and keeps
 * it there. Every step is idempotent so the same pass provisions and repairs drift.
 */
class NodeReconciler(private val ctx: ReconcileContext) {
    private val log get() = ctx.log
    private val dir get() = ctx.config.nodeStack.installDir

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
        /** A paused node gets no provisioning. */
        val paused: Boolean,
    ) {
        val node: NodeConfig get() = desired.node
    }

    /**
     * Connects, renders the stack and decides whether the node needs provisioning. Changes nothing
     * on the node except a periodic drift check. Returns null when the node is unreachable or in
     * dry-run mode.
     *
     * @param force runs every provisioning step again, also when nothing changed.
     */
    suspend fun assess(desired: NodeDesired, force: Boolean): Assessment? {
        val node = desired.node
        val paused = ctx.nodePaused(node.id)
        val nodeState = ctx.state.node(node.id)

        if (ctx.dryRun) {
            reportDryRun(desired, nodeState)
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
                secrets = secrets(node),
                files = emptyList(),
                hash = nodeState.stackHash.orEmpty(),
                kind = Kind.NONE,
                reason = null,
                paused = true,
            )
        }

        val secrets = secrets(node)
        val files = renderAll(node, secrets)
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

    private fun reportDryRun(desired: NodeDesired, nodeState: NodeState) {
        val node = desired.node
        val known = nodeState.facts[CROWDSEC_KEY_FACT] ?: PLACEHOLDER
        val files = renderAll(node, NodeSecrets(known))
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

    /**
     * Provisions the node. Runs as a background job, so the passes continue to serve the other nodes.
     * A [Kind.UPDATE] first saves the stack that runs. It restores that stack if a step or a
     * check after provisioning fails.
     *
     * @return true when the node runs the new stack.
     */
    suspend fun provisionJob(a: Assessment, progress: (String) -> Unit = {}): Boolean {
        val node = a.node
        val ssh = a.ssh
        val update = a.kind == Kind.UPDATE
        val started = nowMillis()
        log.change(DOMAIN, "${if (update) "updating" else "provisioning"} (${a.reason})", node.id)
        // The rollout strip of the dashboard shows each phase with its number.
        val phases = buildList {
            add(PHASE_HOST)
            if (update) add(PHASE_BACKUP)
            add(PHASE_WRITE)
            add(PHASE_VERIFY)
        }
        fun phase(name: String) = progress("$name (${phases.indexOf(name) + 1} of ${phases.size})")
        var stackChanged = false
        val failure = runCatchingCancellable {
            phase(PHASE_HOST)
            provisionHost(node, ssh)
            if (update) {
                phase(PHASE_BACKUP)
                backup(ssh)
            }
            stackChanged = true
            phase(PHASE_WRITE)
            provisionStack(node, ssh, a.files)
            phase(PHASE_VERIFY)
            val missing = verify(node, ssh)
            check(missing.isEmpty()) { "verify failed: ${missing.joinToString(", ")}" }
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

    /**
     * Keeps a copy of the stack that runs: each managed file, but not the data. The logs, the key
     * of the agent and the data of CrowdSec stay in place.
     */
    private suspend fun backup(ssh: SshSession) {
        val installDir = shQuote(dir)
        ssh.runScriptOrThrow(
            step = "backup",
            script = $$"""
            |cd $$installDir
            |B=$$installDir/$$ROLLBACK_DIR
            |rm -rf "$B" && mkdir -p "$B" && chmod 0700 "$B"
            |set --
            |for p in compose.yml .env config; do [ -e "$p" ] && set -- "$@" "$p"; done
            |tar -czf "$B/files.tgz" --exclude=config/traefik/logs --exclude=config/crowdsec/db "$@"
            """.trimMargin(),
            timeoutSeconds = 600,
        )
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
            |$${SshSession.ownInstallDir(dir)}
            |docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20
            |$$WAIT_FOR_TRAEFIK
            """.trimMargin(),
            timeoutSeconds = 900,
        )
    }

    // ---------------------------------------------------------------- secrets

    /** The secrets of the node. The first call makes them, and the state keeps them. */
    private suspend fun secrets(node: NodeConfig): NodeSecrets {
        ctx.state.node(node.id).facts[CROWDSEC_KEY_FACT]?.let { return NodeSecrets(it) }
        val key = Crypto.token(CROWDSEC_KEY_LENGTH)
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (CROWDSEC_KEY_FACT to key)) }
        return NodeSecrets(key)
    }

    fun renderAll(node: NodeConfig, secrets: NodeSecrets): List<RenderedFile> =
        NodeTemplates.staticFiles(ctx.config, node, secrets)

    private fun hash(files: List<RenderedFile>): String =
        Crypto.fnv64(
            files.sortedBy { it.path }
                .joinToString("\u0000") { "${it.path}\u0001${it.mode}\u0001${it.content}" },
        )

    // ---------------------------------------------------------------- provisioning steps

    /** Steps 1-4: everything the node needs before its stack. */
    private suspend fun provisionHost(node: NodeConfig, ssh: SshSession) {
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
            ssh.runScriptOrThrow("firewall", firewallScript(node), timeoutSeconds = 300)
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

        // What the namespace of Traefik actually uses.
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
        return if (ctx.config.nodeStack.tcpTuning) {
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

    private fun firewallScript(node: NodeConfig): String =
        FirewallRules.script(FirewallRules.specs(node, ctx.config.mesh.port))

    private fun stackScript(files: List<RenderedFile>): String = buildString {
        val installDir = shQuote(dir)
        val publicDirs = listOf(
            "",
            "/config",
            "/config/traefik",
            "/config/traefik/dynamic",
            "/config/traefik/logs",
            "/${MaintenancePage.DIR}",
            "/${AgentSpec.DIR}",
        ).joinToString(" ") { "$installDir$it" }
        appendLine("install -d -m 0755 $publicDirs")
        appendLine("install -d -m 0700 $installDir/config/traefik/certs $installDir/agent-state")
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
        appendLine("docker compose up -d --remove-orphans --quiet-pull 2>&1 | tail -n 20")
        val restartService = $$"docker compose restart \"$s\" >/dev/null 2>&1 || true"
        appendLine($$"for s in $(echo $RESTART | tr ' ' '\\n' | sort -u); do $$restartService; done")
        appendLine(WAIT_FOR_TRAEFIK)
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
            appendLine("for s in $STACK_CONTAINERS; do $containerStatus; $reportStopped; done")
        }
        val result = ssh.runScript(script, timeoutSeconds = 120)
        if (!result.ok) return listOf("check failed: ${result.stderr.trim().take(ERROR_SUMMARY_LENGTH)}")
        return result.stdout.lines().filter { it.startsWith(DRIFT) }.map { it.removePrefix(DRIFT) }
    }

    /** Runs the checks after provisioning. Returns the checks that failed. */
    private suspend fun verify(node: NodeConfig, ssh: SshSession): List<String> {
        val running = $$"[ \"$(docker inspect -f '{{.State.Status}}' $s 2>/dev/null)\" = running ]"
        val result = ssh.runScript(
            script = $$"""
            |for s in $$STACK_CONTAINERS; do $$running && echo "CHECK $s ok" || echo "CHECK $s missing"; done
            |(ss -ltn 2>/dev/null | grep -q ':443 ') && echo "CHECK https ok" || echo "CHECK https missing"
            """.trimMargin(),
            timeoutSeconds = 60,
        )
        val missing = result.stdout.lines()
            .filter { it.startsWith(CHECK) && it.endsWith("missing") }
            .map { it.removePrefix(CHECK) }
        missing.forEach { log.warn(DOMAIN, "verify: $it", node.id) }
        return missing
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
        // The agent is gone with the stack. Its exit and the mesh are on the host itself.
        ssh.runScript(Mesh.EXIT_TEARDOWN, timeoutSeconds = 60)
        ssh.runScript(Mesh.TEARDOWN, timeoutSeconds = 60)
        // The directory itself can be a mount point, so its contents go first.
        val removeContents = "find $installDir -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true"
        val removeDir = "rmdir $installDir 2>/dev/null || true"
        val checkEmpty = $$"[ -z \"$(ls -A $$installDir 2>/dev/null)\" ]"
        ssh.runScriptOrThrow("decommission", "$removeContents; $removeDir; $checkEmpty", timeoutSeconds = 120)
        ctx.ssh.drop(node.id)
    }

    companion object {
        /** Previous stack kept on the node during an update, relative to install_dir. */
        const val ROLLBACK_DIR = ".homegap-rollback"
        val SUPPORTED_UBUNTU = setOf("22.04", "24.04", "26.04", "26.10")

        private const val DOMAIN = "nodes"
        private val SUPPORTED_ARCHITECTURES = setOf("x86_64", "aarch64")

        /** The value that a dry run shows in place of a secret. */
        private const val PLACEHOLDER = "<generated>"

        /** A fact in the state of a node: the key of its CrowdSec bouncer. */
        private const val CROWDSEC_KEY_FACT = "crowdsecKey"
        private const val CROWDSEC_KEY_LENGTH = 40

        /** The containers of the stack of a node that must run. */
        private const val STACK_CONTAINERS = "traefik homegap-${MaintenancePage.SERVICE} ${AgentSpec.SERVICE}"

        /** Waits until Traefik answers on its own port. The script fails if it does not. */
        private val WAIT_FOR_TRAEFIK = $$"""
            |ok=0
            |for i in $(seq 1 60); do
            |  if docker exec traefik wget -q -O /dev/null http://127.0.0.1:80/ping 2>/dev/null; then ok=1; break; fi
            |  sleep 2
            |done
            |if [ "$ok" != 1 ]; then echo "Traefik did not start (its log is on the Diagnostics page)" >&2; docker compose ps >&2; exit 1; fi
        """.trimMargin()

        // The kinds of run in the history of a node.
        private const val RUN_PROVISION = "provision"
        private const val RUN_UPDATE = "update"

        private const val ERROR_LENGTH = 2000
        private const val RUN_ERROR_LENGTH = 300
        private const val ERROR_SUMMARY_LENGTH = 200

        // The phases of a provisioning job, as the rollout strip of the dashboard shows them.
        private const val PHASE_HOST = "preparing the host"
        private const val PHASE_BACKUP = "backing up the stack"
        private const val PHASE_WRITE = "writing and starting the stack"
        private const val PHASE_VERIFY = "verifying"

        // Lines in the output of the scripts.
        private const val CHANGED = "CHANGED "
        private const val WARN = "WARN "
        private const val FACT = "FACT "
        private const val DRIFT = "DRIFT "
        private const val CHECK = "CHECK "

        /** Congestion control and max send buffer inside the namespace of Traefik. */
        val EDGE_TCP_SCRIPT = $$"""
            |PID=$(docker inspect -f '{{.State.Pid}}' traefik 2>/dev/null || echo 0)
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
