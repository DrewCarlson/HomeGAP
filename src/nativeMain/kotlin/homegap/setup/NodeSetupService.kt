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

    /** Validates [request] and starts the setup in the background; poll [job] for progress. */
    fun start(request: NodeSetupRequestDto): SetupJobDto {
        val plan = SetupPlan.of(request, controller)
        val job = SetupJobDto(Crypto.token(12), plan.node.id, "running", plan.steps(), startedAt = nowMillis())
        val state = MutableStateFlow(job)
        synchronized(lock) {
            check(jobs.values.none { it.value.nodeId == plan.node.id && it.value.state == "running" }) {
                "setup for ${plan.node.id} is already running"
            }
            jobs[job.id] = state
        }
        scope.launch { SetupRun(plan, state).execute() }
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
            SetupStepDto("ssh", "Harden SSH", if (hardenSsh) "pending" else "skipped"),
            SetupStepDto("firewall", "Configure the firewall", if (request.configureFirewall) "pending" else "skipped"),
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
     * connection ([Admin]); only the checks that must prove a new login works open their own.
     */
    private inner class SetupRun(private val plan: SetupPlan, private val state: MutableStateFlow<SetupJobDto>) {
        private val node = plan.node
        private val keyLogin = SetupLogin.Key(node.host, node.sshPort, plan.target, node.sshKeyFile)
        private val verifyCommand = if (plan.target == "root") "true" else "sudo -n true"
        private var connection: SetupShell.Connection? = null

        /** The orchestrator's address as the server sees it (from the probe). */
        private var clientIp: String? = null

        suspend fun execute() {
            log("setup started (${plan.initial})")
            try {
                val admin = step("connect") { connect() }
                step("user") { prepareUser(admin) }
                step("key") { verifyKeyLogin("key login failed") }
                if (plan.hardenSsh) step("ssh") { hardenSsh(admin) }
                if (plan.request.configureFirewall) step("firewall") { firewall(admin) }
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

        /** A fresh connection with the node key, as the orchestrator will use it. */
        private suspend fun verifyKeyLogin(what: String) {
            val r = shell.run(keyLogin, verifyCommand, timeout = 30.seconds)
            SetupShell.connectionError(r)?.let { throw IllegalStateException("$what: ${explain(it)}") }
            check(r.ok) { "$what: ${plan.target} cannot use passwordless sudo (${r.errorText()})" }
        }

        /** Runs [script] as root through the run's connection; returns its output or throws. */
        private suspend fun root(admin: Admin, what: String, script: String, timeout: Duration = 600.seconds): String {
            val r = shell.runAsRoot(admin.connection, admin.privilege, script, timeout)
            SetupShell.connectionError(r)?.let { throw IllegalStateException("$what: ${explain(it)}") }
            check(r.ok) { "$what: ${r.errorText()}" }
            return r.stdout
        }

        /**
         * Hardens sshd, then proves a fresh key login still works. Whether password and root login are
         * really off is checked inside the script with `sshd -T -C` for the users involved and the
         * orchestrator's address, rather than by attempting a login that should fail (which a login
         * blocker would count against the orchestrator).
         */
        private suspend fun hardenSsh(admin: Admin): String {
            val users = listOf(plan.initial.user, plan.target, "root").distinct()
            val script = SetupScripts.hardenSsh(plan.request.disableRootLogin, plan.request.disablePasswordLogin, users, clientIp)
            val out = root(admin, "hardening SSH", script, 120.seconds)
            try {
                verifyKeyLogin("after hardening SSH, key login failed")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                shell.runAsRoot(admin.connection, admin.privilege, SetupScripts.REVERT_SSH, 60.seconds)
                throw IllegalStateException("${e.message} (SSH settings reverted)")
            }
            root(admin, "confirming the SSH settings", SetupScripts.disarm("ssh"), 30.seconds)
            return out.changes()
        }

        private suspend fun firewall(admin: Admin): String {
            val out = root(admin, "configuring the firewall", SetupScripts.firewall(FirewallRules.specs(node)), 300.seconds)
            try {
                verifyKeyLogin("with the firewall on, SSH from the orchestrator failed; check the allowed sources")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                shell.runAsRoot(admin.connection, admin.privilege, SetupScripts.REVERT_UFW, 60.seconds)
                throw IllegalStateException("${e.message} (firewall turned off again)")
            }
            root(admin, "confirming the firewall", SetupScripts.disarm("ufw"), 30.seconds)
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
            "It answered earlier in this run, so it has most likely started blocking the orchestrator's address " +
                "(fail2ban, sshguard, CrowdSec or a provider firewall). Lift the block from the provider's console " +
                "or another network, then retry; setup then exempts the orchestrator."
    }
}

private fun ProcessResult.facts(): Map<String, String> =
    stdout.lines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()

private fun ProcessResult.changes(): String = stdout.changes()

/** The `CHANGED …` lines a setup script printed, joined for a step's detail. */
private fun String.changes(): String =
    lines().filter { it.startsWith("CHANGED ") }.joinToString("; ") { it.removePrefix("CHANGED ") }

private fun ProcessResult.errorText(): String = stderr.ifBlank { stdout }.trim().takeLast(500).ifEmpty { "exit $exitCode" }
