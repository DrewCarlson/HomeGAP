package homegap.setup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.*
import homegap.clients.SshSession
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

    suspend fun test(dto: SshLoginDto): ConnectionTestDto {
        val login = dto.toLogin()
        if (dto.resetHostKey) shell.forgetHostKey(login.host, login.port)
        val probe = shell.run(login, "bash -s", SetupScripts.PROBE, 30.seconds)
        SetupShell.connectionError(probe)?.let {
            return ConnectionTestDto(ok = false, error = it, hostKeyChanged = it == SetupShell.HOST_KEY_CHANGED)
        }
        if (!probe.ok) return ConnectionTestDto(ok = false, error = "Logged in, but the probe failed: ${probe.errorText()}")
        val facts = probe.facts()
        val privilege = privilegeOf(login, facts["PRIV"])
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
    }

    /** Sudo for a non-root login: without password, with the login password, or not at all. */
    private suspend fun privilegeOf(login: SetupLogin, probed: String?): Privilege = when (probed) {
        "root" -> Privilege.Root
        "nopasswd" -> Privilege.NoPasswdSudo
        "maybe" -> if (login is SetupLogin.Password && shell.run(login, "sudo -S -k -p '' true", "${login.password}\n", 30.seconds).ok) {
            Privilege.PasswordSudo
        } else {
            Privilege.None
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

    /** One execution of a [SetupPlan], reporting into [state]. */
    private inner class SetupRun(private val plan: SetupPlan, private val state: MutableStateFlow<SetupJobDto>) {
        private val node = plan.node
        private val keyLogin = SetupLogin.Key(node.host, node.sshPort, plan.target, node.sshKeyFile)
        private val verifyCommand = if (plan.target == "root") "true" else "sudo -n true"
        private var master: SshSession? = null

        suspend fun execute() {
            log("setup started (${plan.initial})")
            try {
                val admin = step("connect") { connect() }
                step("user") { prepareUser(admin) }
                step("key") { verifyKeyLogin("key login failed") }
                if (plan.hardenSsh) step("ssh") { hardenSsh() }
                if (plan.request.configureFirewall) step("firewall") { firewall() }
                step("add") { controller.addNode(node) }
                state.update { it.copy(state = "succeeded", finishedAt = nowMillis()) }
                log("setup finished; provisioning starts on the next pass")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                state.update { it.copy(state = "failed", error = e.message ?: e.toString(), finishedAt = nowMillis()) }
                controller.log.error("setup", e.message ?: e.toString(), node.id)
            } finally {
                master?.let { withContext(NonCancellable) { runCatching { it.close() } } }
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

        /** Logs in with the initial credentials; on a re-run after hardening, falls back to the node key. */
        private suspend fun connect(): Pair<SetupLogin, Privilege> {
            val probe = shell.run(plan.initial, "bash -s", SetupScripts.PROBE, 30.seconds)
            val error = SetupShell.connectionError(probe)
            if (error != null) {
                if (shell.run(keyLogin, verifyCommand, timeout = 30.seconds).ok) {
                    val privilege = if (plan.target == "root") Privilege.Root else Privilege.NoPasswdSudo
                    setDetail("connect", "The initial login is no longer accepted (an earlier run hardened SSH); continuing with the node key.")
                    return keyLogin to privilege
                }
                throw IllegalStateException(error)
            }
            check(probe.ok) { "probe failed: ${probe.errorText()}" }
            val facts = probe.facts()
            val os = "${facts["ID"]} ${facts["VERSION_ID"]}"
            check(facts["ID"] == "ubuntu" && facts["VERSION_ID"] in NodeReconciler.SUPPORTED_UBUNTU) { "unsupported system: $os" }
            val privilege = privilegeOf(plan.initial, facts["PRIV"])
            check(privilege != Privilege.None) { "${plan.initial.user} cannot become root" }
            val access = when (privilege) {
                Privilege.Root -> "logged in as root"
                Privilege.NoPasswdSudo -> "passwordless sudo"
                else -> "sudo with password"
            }
            setDetail("connect", "$os (${facts["ARCH"]}); $access")
            return plan.initial to privilege
        }

        private suspend fun prepareUser(admin: Pair<SetupLogin, Privilege>): String {
            val publicKey = keys.existing(node.sshKeyFile).publicKey
            val script = SetupScripts.prepareUser(plan.target, publicKey, plan.request.createUser != null, plan.request.dockerGroup)
            val r = shell.runAsRoot(admin.first, admin.second, script)
            SetupShell.connectionError(r)?.let { throw IllegalStateException(it) }
            check(r.ok) { r.errorText() }
            return r.changes().ifEmpty { "already set up" }
        }

        /** A fresh connection with the node key, as the orchestrator will use it. */
        private suspend fun verifyKeyLogin(what: String) {
            val r = shell.run(keyLogin, verifyCommand, timeout = 30.seconds)
            SetupShell.connectionError(r)?.let { throw IllegalStateException("$what: $it") }
            check(r.ok) { "$what: ${plan.target} cannot use passwordless sudo (${r.errorText()})" }
        }

        /** The management connection used while changing sshd and the firewall (kept open across both). */
        private suspend fun master(): SshSession = master ?: SshSession(
            NodeConfig(id = "setup-${node.id}", host = node.host, sshUser = plan.target, sshPort = node.sshPort, sshKeyFile = node.sshKeyFile),
            "/tmp/homegap-setup",
            "$dataDir/known_hosts",
        ).also {
            it.ensureMaster()
            master = it
        }

        private suspend fun hardenSsh(): String {
            val session = master()
            val out = session.runScriptOrThrow("harden", SetupScripts.hardenSsh(plan.request.disableRootLogin, plan.request.disablePasswordLogin), 120)
            try {
                verifyKeyLogin("after hardening SSH, key login failed")
                passwordRefused()
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                session.runScript(SetupScripts.REVERT_SSH, 60)
                throw IllegalStateException("${e.message} (SSH settings reverted)")
            }
            session.runScriptOrThrow("disarm", SetupScripts.disarm("ssh"), 30)
            return out.changes()
        }

        /** With password login disabled, the initial password must now be refused. */
        private suspend fun passwordRefused() {
            val initial = plan.initial as? SetupLogin.Password ?: return
            if (!plan.request.disablePasswordLogin && !(plan.request.disableRootLogin && initial.user == "root")) return
            val r = shell.run(initial, "true", timeout = 30.seconds)
            check(!r.ok) { "the initial password login is still accepted" }
        }

        private suspend fun firewall(): String {
            val session = master()
            val out = session.runScriptOrThrow("firewall", SetupScripts.firewall(FirewallRules.specs(node)), 300)
            try {
                verifyKeyLogin("with the firewall on, SSH from the orchestrator failed; check the allowed sources")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                session.runScript(SetupScripts.REVERT_UFW, 60)
                throw IllegalStateException("${e.message} (firewall turned off again)")
            }
            session.runScriptOrThrow("disarm", SetupScripts.disarm("ufw"), 30)
            val ssh = if (node.sshAllowFrom.isEmpty()) "SSH from anywhere" else "SSH from ${node.sshAllowFrom.joinToString()}"
            return (listOf("$ssh; web, WireGuard and raw ports open") + out.changes().lines()).filter { it.isNotBlank() }.joinToString("; ")
        }

        private fun setDetail(id: String, detail: String) {
            state.update { job -> job.copy(steps = job.steps.map { if (it.id == id) it.copy(detail = detail) else it }) }
        }

        private fun log(message: String) = controller.log.change("setup", message, node.id)
    }
}

private fun ProcessResult.facts(): Map<String, String> =
    stdout.lines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()

private fun ProcessResult.changes(): String = stdout.changes()

/** The `CHANGED …` lines a setup script printed, joined for a step's detail. */
private fun String.changes(): String =
    lines().filter { it.startsWith("CHANGED ") }.joinToString("; ") { it.removePrefix("CHANGED ") }

private fun ProcessResult.errorText(): String = stderr.ifBlank { stdout }.trim().takeLast(500).ifEmpty { "exit $exitCode" }
