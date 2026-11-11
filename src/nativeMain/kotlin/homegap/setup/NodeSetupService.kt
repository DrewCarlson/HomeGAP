package homegap.setup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.*
import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.platform.Crypto
import homegap.platform.ProcessResult
import homegap.platform.nowMillis
import homegap.provision.FirewallRules
import homegap.reconcile.Controller
import homegap.reconcile.NodeReconciler
import homegap.routes.toConfig
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Takes a fresh VPS from its initial credentials to a node in the config: tests the login,
 * manages the node's SSH key, prepares a management user, hardens SSH and the firewall, and adds
 * the node (whose provisioning then starts on the next pass).
 *
 * Passwords live only in the request objects for the duration of a test or job; they are never
 * logged, stored or returned.
 */
@SingleIn(AppScope::class)
@Inject
class NodeSetupService(private val controller: Controller) {
    private val dataDir get() = controller.config.dataDir
    private val shell by lazy { SetupShell("$dataDir/known_hosts", "$dataDir/ssh-askpass.sh") }
    private val keys by lazy { NodeKeys("$dataDir/ssh") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val jobs = mutableMapOf<String, MutableStateFlow<SetupJobDto>>()

    // ------------------------------------------------------------------ connection test

    /** Logs in once with [dto] (a single connection, closed afterwards) and reports what it found. */
    suspend fun test(dto: SshLoginDto): ConnectionTestDto {
        val login = dto.toLogin()
        if (dto.resetHostKey) shell.forgetHostKey(login.host, login.port)
        val connection = try {
            shell.connect(login)
        } catch (e: SetupConnectionException) {
            return ConnectionTestDto(ok = false, error = e.message, hostKeyChanged = e.message == SetupShell.HOST_KEY_CHANGED)
        }
        try {
            val probe = shell.run(connection, "bash -s", SetupScripts.PROBE, 30.seconds)
            SetupShell.connectionError(probe)?.let { return ConnectionTestDto(ok = false, error = it) }
            if (!probe.ok) return ConnectionTestDto(ok = false, error = "Logged in, but the probe failed: ${probe.errorText()}")
            val facts = probe.facts()
            val privilege = privilegeOf(connection, facts["PRIV"])
            val os = facts["ID"].orEmpty()
            val version = facts["VERSION_ID"].orEmpty()
            val arch = facts["ARCH"].orEmpty()
            val supported = os == "ubuntu" && version in NodeReconciler.SUPPORTED_UBUNTU && arch in setOf("x86_64", "aarch64")
            val problem = when {
                !supported -> "Unsupported system: $os $version ($arch). HomeGAP provisions Ubuntu ${NodeReconciler.SUPPORTED_UBUNTU.joinToString("/")} on x86_64 or aarch64."
                privilege == Privilege.None -> "${login.user} cannot become root (no sudo, or the password is not accepted by sudo)."
                else -> null
            }
            return ConnectionTestDto(
                ok = problem == null,
                error = problem,
                hostKeyFingerprint = shell.recordedHostKey(login.host, login.port),
                os = os, osVersion = version, arch = arch, supported = supported,
                privilege = privilege.wire,
                dockerInstalled = facts["DOCKER"] == "yes",
                clientIp = facts["CLIENT"]?.takeIf { it.isNotBlank() },
            )
        } finally {
            withContext(NonCancellable) { shell.close(connection) }
        }
    }

    /** Sudo for a non-root login: without password, with the login password, or not at all. */
    private suspend fun privilegeOf(connection: SetupShell.Connection, probed: String?): Privilege = when (probed) {
        "root" -> Privilege.Root
        "nopasswd" -> Privilege.NoPasswdSudo
        "maybe" -> {
            val password = (connection.login as? SetupLogin.Password)?.password
            if (password != null && shell.run(connection, "sudo -S -k -p '' true", "$password\n", 30.seconds).ok) {
                Privilege.PasswordSudo
            } else {
                Privilege.None
            }
        }
        else -> Privilege.None
    }

    // ------------------------------------------------------------------------- keys

    suspend fun key(dto: SshKeyRequestDto): SshKeyDto {
        require(controller.config.node(dto.nodeId) == null) { "node ${dto.nodeId} already exists" }
        return when (dto.mode) {
            "generate" -> keys.generate(dto.nodeId)
            "import" -> keys.import(dto.nodeId, requireNotNull(dto.privateKey?.takeIf { it.isNotBlank() }) { "paste the private key" })
            "existing" -> keys.existing(requireNotNull(dto.path?.trim()?.takeIf { it.isNotEmpty() }) { "enter the key file path" })
            else -> throw IllegalArgumentException("mode must be generate, import or existing")
        }
    }

    // ------------------------------------------------------------------------- jobs

    fun job(id: String): SetupJobDto? = synchronized(lock) { jobs[id]?.value }

    private val _changes = MutableStateFlow(0L)

    /** Grows whenever a job changes; the event stream then sends [allJobs] that changed. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun allJobs(): List<SetupJobDto> = synchronized(lock) { jobs.values.map { it.value } }

    /** Validates [request] and starts the setup in the background; poll [job] for progress. */
    fun start(request: NodeSetupRequestDto): SetupJobDto {
        val plan = SetupPlan.of(request, controller)
        // A retry keeps the management user the failed run created.
        val kept = request.retryOf?.let { job(it) }
            ?.takeIf { it.nodeId == plan.node.id && it.state == "failed" }
            ?.steps?.filter { it.status == "ok" && it.id in KEPT_ON_RETRY }?.map { it.id }?.toSet()
            .orEmpty()
        val steps = plan.steps().map { step ->
            if (step.id in kept) step.copy(status = "ok", detail = "kept from the last run") else step
        }
        val job = SetupJobDto(Crypto.token(12), plan.node.id, "running", steps, startedAt = nowMillis())
        val state = MutableStateFlow(job)
        synchronized(lock) {
            check(jobs.values.none { it.value.nodeId == plan.node.id && it.value.state == "running" }) {
                "setup for ${plan.node.id} is already running"
            }
            jobs[job.id] = state
        }
        scope.launch { state.collect { _changes.value++ } }
        scope.launch { SetupRun(plan, state, kept).execute() }
        return job
    }

    private fun SshLoginDto.toLogin(): SetupLogin {
        require(host.isNotBlank()) { "host is required" }
        require(port in 1..65535) { "port must be 1-65535" }
        require(ConfigLoader.UNIX_USER.matches(user)) { "invalid user name" }
        val pw = password?.takeIf { it.isNotEmpty() }
        val key = keyFile?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            pw != null -> SetupLogin.Password(host.trim(), port, user, pw)
            key != null -> SetupLogin.Key(host.trim(), port, user, key)
            else -> throw IllegalArgumentException("enter a password or a key file for the initial login")
        }
    }

    /** A validated request: what to do, and the node that will be added at the end. */
    private class SetupPlan(
        val request: NodeSetupRequestDto,
        val node: NodeConfig,
        val initial: SetupLogin,
        val target: String,
    ) {
        val hardenSsh get() = request.disableRootLogin || request.disablePasswordLogin

        fun steps() = listOf(
            SetupStepDto("connect", "Connect as ${initial.user}"),
            SetupStepDto("user", if (request.createUser != null) "Create user $target" else "Authorize the key for $target"),
            SetupStepDto("key", "Log in with the node key as $target"),
            SetupStepDto("firewall", "Configure the firewall", if (request.configureFirewall) "pending" else "skipped"),
            SetupStepDto("ssh", "Harden SSH", if (hardenSsh) "pending" else "skipped"),
            SetupStepDto("verify", "Reconnect from the orchestrator with the node key"),
            SetupStepDto("add", "Add ${node.id} and start provisioning"),
        )

        companion object {
            fun of(request: NodeSetupRequestDto, controller: Controller): SetupPlan {
                val config = controller.config
                require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
                val target = request.createUser?.trim()?.takeIf { it.isNotEmpty() } ?: request.login.user
                require(ConfigLoader.UNIX_USER.matches(target)) { "invalid user name '$target'" }
                require(!(request.disableRootLogin && target == "root")) {
                    "root login cannot be disabled while root is the management user; create a user"
                }
                val node = request.node.copy(
                    host = request.login.host.trim(),
                    sshUser = target,
                    sshPort = request.login.port,
                    sshKeyFile = request.keyFile,
                    sshAllowFrom = if (request.configureFirewall) request.sshAllowFrom else emptyList(),
                ).toConfig().copy(firewall = request.configureFirewall)
                require(config.node(node.id) == null) { "node ${node.id} already exists" }
                // Fail now, not after the server work, if the result would not be a valid config.
                ConfigLoader.validate(config.copy(nodes = config.nodes + node))
                val password = request.login.password?.takeIf { it.isNotEmpty() }
                val initial = if (password != null) {
                    SetupLogin.Password(node.host, node.sshPort, request.login.user, password)
                } else {
                    SetupLogin.Key(node.host, node.sshPort, request.login.user, request.login.keyFile?.trim()?.takeIf { it.isNotEmpty() } ?: request.keyFile)
                }
                return SetupPlan(request, node, initial, target)
            }
        }
    }

    /** The held-open connection a run works through, and how its login gains root. */
    private class Admin(val connection: SetupShell.Connection, val privilege: Privilege)

    /**
     * One execution of a [SetupPlan], reporting into [state]. Everything runs through one held-open
     * connection ([Admin]), and the checks that need a new login run it inside that connection. The
     * only other connection is the final check from outside, so a run opens two connections in all:
     * a burst of SSH logins gets the orchestrator cut off by intrusion-prevention systems on its own
     * network and by login blockers on the server.
     *
     * The firewall and the SSH hardening stay armed (their revert timers running) until that final
     * check passes, and are both reverted when it fails.
     */
    private inner class SetupRun(
        private val plan: SetupPlan,
        private val state: MutableStateFlow<SetupJobDto>,
        private val kept: Set<String> = emptySet(),
    ) {
        private val node = plan.node
        private val keyLogin = SetupLogin.Key(node.host, node.sshPort, plan.target, node.sshKeyFile)
        private val verifyCommand = if (plan.target == "root") "true" else "sudo -n true"
        private var connection: SetupShell.Connection? = null

        /** The orchestrator's address as the server sees it (from the probe). */
        private var clientIp: String? = null

        /** Where the server's sshd took the setup connection (from the probe); new logins inside it go there. */
        private var serverAddr: String? = null
        private var serverPort: Int = node.sshPort

        /** Changes whose revert timer is running, by marker name, with how to undo them now. */
        private val armed = linkedMapOf<String, String>()

        suspend fun execute() {
            log("setup started (${plan.initial})")
            try {
                val admin = step("connect") { connect() }
                if ("user" !in kept) {
                    step("user") { prepareUser(admin) }
                }
                step("key") { verifyInside(admin, "key login failed") }
                if (plan.request.configureFirewall) step("firewall") { firewall(admin) }
                if (plan.hardenSsh) step("ssh") { hardenSsh(admin) }
                step("verify") { verifyFromOutside(admin) }
                step("add") { controller.addNode(node) }
                state.update { it.copy(state = "succeeded", finishedAt = nowMillis()) }
                log("setup finished; provisioning starts on the next pass")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                state.update { it.copy(state = "failed", error = e.message ?: e.toString(), finishedAt = nowMillis()) }
                controller.log.error("setup", e.message ?: e.toString(), node.id)
            } finally {
                connection?.let { withContext(NonCancellable) { runCatching { shell.close(it) } } }
            }
        }

        /** Runs one step; a String result becomes its detail (otherwise a detail set while running is kept). */
        private suspend fun <T> step(id: String, block: suspend () -> T): T {
            setStep(id, "running", null)
            try {
                val result = block()
                val detail = (result as? String)?.takeIf { it.isNotBlank() } ?: state.value.steps.first { it.id == id }.detail
                setStep(id, "ok", detail)
                return result
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                setStep(id, "failed", e.message)
                throw e
            }
        }

        private fun setStep(id: String, status: String, detail: String?) {
            state.update { job -> job.copy(steps = job.steps.map { if (it.id == id) it.copy(status = status, detail = detail) else it }) }
        }

        /**
         * Opens the run's connection with the initial login. On a re-run after an earlier run disabled
         * that login, continues with the node key instead.
         */
        private suspend fun connect(): Admin {
            var note: String? = null
            val opened = try {
                shell.connect(plan.initial)
            } catch (e: SetupConnectionException) {
                if (e.message != SetupShell.LOGIN_REJECTED) throw IllegalStateException(e.message)
                note = "the initial login is no longer accepted (an earlier run hardened SSH), so setup continues with the node key"
                try {
                    shell.connect(keyLogin)
                } catch (_: SetupConnectionException) {
                    throw IllegalStateException(e.message)
                }
            }
            connection = opened
            val probe = shell.run(opened, "bash -s", SetupScripts.PROBE, 30.seconds)
            SetupShell.connectionError(probe)?.let { throw IllegalStateException(explain(it)) }
            check(probe.ok) { "probe failed: ${probe.errorText()}" }
            val facts = probe.facts()
            val os = "${facts["ID"]} ${facts["VERSION_ID"]}"
            check(facts["ID"] == "ubuntu" && facts["VERSION_ID"] in NodeReconciler.SUPPORTED_UBUNTU) { "unsupported system: $os" }
            clientIp = facts["CLIENT"]?.takeIf { SetupScripts.isIpAddress(it) }
            serverAddr = facts["SERVER_ADDR"]?.takeIf { SetupScripts.isIpAddress(it) }
            facts["SERVER_PORT"]?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { serverPort = it }
            val privilege = privilegeOf(opened, facts["PRIV"])
            check(privilege != Privilege.None) { "${opened.login.user} cannot become root" }
            val access = when (privilege) {
                Privilege.Root -> "logged in as root"
                Privilege.NoPasswdSudo -> "passwordless sudo"
                else -> "sudo with password"
            }
            setDetail("connect", listOfNotNull("$os (${facts["ARCH"]}); $access", note).joinToString("; "))
            return Admin(opened, privilege)
        }

        /** Exempts the orchestrator from login blockers first, then prepares the management user. */
        private suspend fun prepareUser(admin: Admin): String {
            val exempt = clientIp?.let { root(admin, "exempting the orchestrator from login blockers", SetupScripts.exemptFromBlockers(it), 120.seconds) }
            val publicKey = keys.existing(node.sshKeyFile).publicKey
            val script = SetupScripts.prepareUser(plan.target, publicKey, plan.request.createUser != null, plan.request.dockerGroup)
            val out = root(admin, "preparing $target", script)
            return listOfNotNull(out.changes(), exempt?.changes()).filter { it.isNotBlank() }.joinToString("; ").ifEmpty { "already set up" }
        }

        private val target get() = plan.target

        /**
         * A new login with the node key, carried inside the setup connection: proves the key, sshd's
         * current settings and sudo without another connection from the orchestrator. Falls back to
         * a direct login only if the probe could not tell where sshd took the setup connection.
         */
        private suspend fun verifyInside(admin: Admin, what: String) {
            val addr = serverAddr
            val r = if (addr != null) {
                shell.runThrough(admin.connection, addr, serverPort, keyLogin, verifyCommand, 60.seconds)
            } else {
                shell.run(keyLogin, verifyCommand, timeout = 60.seconds)
            }
            checkKeyLogin(r, what)
        }

        private fun checkKeyLogin(r: ProcessResult, what: String) {
            SetupShell.connectionError(r)?.let { throw IllegalStateException("$what: ${explain(it)}") }
            check(r.ok) { "$what: ${plan.target} cannot use passwordless sudo (${r.errorText()})" }
        }

        /**
         * The one direct login with the node key, as the orchestrator will connect from now on: it
         * crosses the new firewall and the hardened sshd. On success the armed changes are kept; on
         * failure they are all reverted.
         */
        private suspend fun verifyFromOutside(admin: Admin): String? {
            val r = shell.run(keyLogin, verifyCommand, timeout = 60.seconds)
            val limited = "ufw" in armed && node.sshAllowFrom.isNotEmpty() && SetupShell.connectionError(r)?.let(SetupShell::isDropped) == true
            try {
                // The new firewall is the likeliest cause when it only lets listed sources reach SSH.
                if (limited) {
                    throw IllegalStateException(
                        "with SSH limited to ${node.sshAllowFrom.joinToString()}, the orchestrator" +
                            "${clientIp?.let { " ($it)" }.orEmpty()} could not log in; add its address to the allowed sources",
                    )
                }
                checkKeyLogin(r, "key login from the orchestrator failed")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (armed.isEmpty()) throw e
                val undone = revertArmed(admin)
                throw IllegalStateException("${e.message} ($undone)")
            }
            if (armed.isEmpty()) return null
            root(admin, "keeping the changes", SetupScripts.confirm(armed.keys.toList()), 30.seconds)
            val kept = armed.keys.joinToString(" and ") { if (it == "ufw") "firewall" else "SSH settings" }
            armed.clear()
            return "$kept kept"
        }

        /** Undoes every armed change now (best effort; their timers would do it shortly anyway). */
        private suspend fun revertArmed(admin: Admin): String {
            val names = armed.keys.reversed().map { if (it == "ufw") "firewall turned off again" else "SSH settings reverted" }
            armed.entries.reversed().forEach { (_, revert) ->
                withContext(NonCancellable) { runCatching { shell.runAsRoot(admin.connection, admin.privilege, revert, 60.seconds) } }
            }
            armed.clear()
            return names.joinToString(", ")
        }

        /** Runs [script] as root through the run's connection; returns its output or throws. */
        private suspend fun root(admin: Admin, what: String, script: String, timeout: Duration = 600.seconds): String {
            val r = shell.runAsRoot(admin.connection, admin.privilege, script, timeout)
            SetupShell.connectionError(r)?.let { throw IllegalStateException("$what: ${explain(it)}") }
            check(r.ok) { "$what: ${r.errorText()}" }
            return r.stdout
        }

        /**
         * Hardens sshd (armed), then proves a new key login still works, inside the setup connection.
         * Whether password and root login are really off is checked inside the script with
         * `sshd -T -C` for the users involved and the orchestrator's address, rather than by
         * attempting a login that should fail (which a login blocker would count against the
         * orchestrator). The final check from outside then keeps or reverts it.
         */
        private suspend fun hardenSsh(admin: Admin): String {
            val users = listOf(plan.initial.user, plan.target, "root").distinct()
            val script = SetupScripts.hardenSsh(plan.request.disableRootLogin, plan.request.disablePasswordLogin, users, clientIp)
            val out = root(admin, "hardening SSH", script, 120.seconds)
            armed["ssh"] = SetupScripts.REVERT_SSH
            try {
                verifyInside(admin, "after hardening SSH, key login failed")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                throw IllegalStateException("${e.message} (${revertArmed(admin)})")
            }
            return out.changes()
        }

        /** Applies the firewall and turns it on (armed); the final check from outside keeps or reverts it. */
        private suspend fun firewall(admin: Admin): String {
            val out = root(admin, "configuring the firewall", SetupScripts.firewall(FirewallRules.specs(node)), 300.seconds)
            armed["ufw"] = SetupScripts.REVERT_UFW
            val ssh = if (node.sshAllowFrom.isEmpty()) "SSH from anywhere" else "SSH from ${node.sshAllowFrom.joinToString()}"
            return (listOf("$ssh; web, WireGuard and raw ports open") + out.changes().lines()).filter { it.isNotBlank() }.joinToString("; ")
        }

        /**
         * Adds a hint to a dropped connection once this run had connected: the server answered a
         * moment ago, so it has most likely started blocking the orchestrator's address.
         */
        private fun explain(error: String): String =
            if (connection != null && SetupShell.isDropped(error)) "$error $BLOCKED_HINT" else error

        private fun setDetail(id: String, detail: String) {
            state.update { job -> job.copy(steps = job.steps.map { if (it.id == id) it.copy(detail = detail) else it }) }
        }

        private fun log(message: String) = controller.log.change("setup", message, node.id)
    }

    private companion object {
        const val BLOCKED_HINT =
            "It answered earlier in this run, so something is now cutting off new SSH connections from the orchestrator. " +
                "Most often that is an intrusion-prevention system on the orchestrator's own network (UniFi Threat " +
                "Management, Suricata or Snort on pfSense/OPNsense, Firewalla) treating several SSH logins in a row as an " +
                "outbound SSH scan; otherwise a login blocker on the server (fail2ban, sshguard, CrowdSec) or a provider " +
                "firewall. Allow the orchestrator to reach this server there, or wait a few minutes for the block to " +
                "expire, then retry."
    }
}

private fun ProcessResult.facts(): Map<String, String> =
    stdout.lines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()

private fun ProcessResult.changes(): String = stdout.changes()

/** The `CHANGED …` lines a setup script printed, joined for a step's detail. */
private fun String.changes(): String =
    lines().filter { it.startsWith("CHANGED ") }.joinToString("; ") { it.removePrefix("CHANGED ") }

private fun ProcessResult.errorText(): String = stderr.ifBlank { stdout }.trim().takeLast(500).ifEmpty { "exit $exitCode" }

/** Steps whose result stays on the server after a failed run, so a retry needn't run them. */
private val KEPT_ON_RETRY = setOf("user")
