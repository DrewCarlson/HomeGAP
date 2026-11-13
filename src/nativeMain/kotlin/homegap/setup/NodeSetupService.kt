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
import homegap.platform.runCatchingCancellable
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
 * Makes a node in the config from a fresh VPS and its initial credentials. The service tests the
 * login, manages the SSH key of the node and prepares a management user. Then it hardens SSH,
 * configures the firewall and adds the node. The next pass provisions the node.
 *
 * A password is only in the request object during a test or a job. The service does not log,
 * store or return a password.
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

    /** Logs in once with [dto] and reports what it found. The test uses one connection and closes it. */
    suspend fun test(dto: SshLoginDto): ConnectionTestDto {
        val login = dto.toLogin()
        if (dto.resetHostKey) {
            shell.forgetHostKey(login.host, login.port)
        }
        hostKeyAnswer(dto, login)?.let { return it }
        val connection = try {
            shell.connect(login)
        } catch (e: SetupConnectionException) {
            return ConnectionTestDto(
                ok = false,
                error = e.message,
                hostKeyChanged = e.message == SetupShell.HOST_KEY_CHANGED,
            )
        }
        try {
            return probe(connection)
        } finally {
            withContext(NonCancellable) { shell.close(connection) }
        }
    }

    /**
     * The password goes only to a host key that the operator confirmed. For a server with no
     * recorded key, the first test returns the fingerprint and sends no password.
     *
     * Returns the answer that ends the test, or null if the test can log in.
     */
    private suspend fun hostKeyAnswer(dto: SshLoginDto, login: SetupLogin): ConnectionTestDto? {
        if (login !is SetupLogin.Password || shell.recordedHostKey(login.host, login.port) != null) return null
        // A confirmed fingerprint is compared with the key of the first test: no second scan.
        val scanned = shell.scannedHostKey(login)?.takeIf { it == dto.hostKeyFingerprint }
            ?: shell.scanHostKey(login)
            ?: return ConnectionTestDto(
                ok = false,
                error = "HomeGAP cannot read the host key of ${login.host}. Check the address and the port.",
            )
        if (dto.hostKeyFingerprint != scanned) {
            return ConnectionTestDto(ok = false, hostKeyFingerprint = scanned, hostKeyUnconfirmed = true)
        }
        shell.trustScannedHostKey(login)
        return null
    }

    /** Reads the facts of the server through [connection] and tells if HomeGAP can provision it. */
    private suspend fun probe(connection: SetupShell.Connection): ConnectionTestDto {
        val login = connection.login
        val probe = shell.run(connection, "bash -s", SetupScripts.PROBE, PROBE_TIMEOUT)
        SetupShell.connectionError(probe)?.let { return ConnectionTestDto(ok = false, error = it) }
        if (!probe.ok) {
            return ConnectionTestDto(ok = false, error = "Logged in, but the probe failed: ${probe.errorText()}")
        }
        val facts = probe.facts()
        val privilege = privilegeOf(connection, facts["PRIV"])
        val os = facts["ID"].orEmpty()
        val version = facts["VERSION_ID"].orEmpty()
        val arch = facts["ARCH"].orEmpty()
        val supported = os == "ubuntu" && version in NodeReconciler.SUPPORTED_UBUNTU && arch in SUPPORTED_ARCH
        val problem = when {
            !supported ->
                "Unsupported system: $os $version ($arch). HomeGAP provisions Ubuntu " +
                    "${NodeReconciler.SUPPORTED_UBUNTU.joinToString("/")} on x86_64 or aarch64."
            privilege == Privilege.None ->
                "${login.user} cannot become root (no sudo, or the password is not accepted by sudo)."
            else -> null
        }
        return ConnectionTestDto(
            ok = problem == null,
            error = problem,
            hostKeyFingerprint = shell.recordedHostKey(login.host, login.port),
            os = os,
            osVersion = version,
            arch = arch,
            supported = supported,
            privilege = privilege.wire,
            dockerInstalled = facts["DOCKER"] == "yes",
            clientIp = facts["CLIENT"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Sudo for a login that is not root: without a password, with the login password, or not at all. */
    private suspend fun privilegeOf(connection: SetupShell.Connection, probed: String?): Privilege = when (probed) {
        "root" -> Privilege.Root
        "nopasswd" -> Privilege.NoPasswdSudo
        "maybe" -> {
            val password = (connection.login as? SetupLogin.Password)?.password
            if (password != null && shell.run(connection, "sudo -S -k -p '' true", "$password\n", PROBE_TIMEOUT).ok) {
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
            "import" -> {
                val privateKey = requireNotNull(dto.privateKey?.takeIf { it.isNotBlank() }) { "paste the private key" }
                keys.import(dto.nodeId, privateKey)
            }
            "existing" -> {
                val path = requireNotNull(dto.path?.trim()?.takeIf { it.isNotEmpty() }) { "enter the key file path" }
                keys.existing(path)
            }
            else -> throw IllegalArgumentException("mode must be generate, import or existing")
        }
    }

    // ------------------------------------------------------------------------- jobs

    fun job(id: String): SetupJobDto? = synchronized(lock) { jobs[id]?.value }

    private val _changes = MutableStateFlow(0L)

    /** Grows when a job changes. The event stream then sends the jobs of [allJobs] that changed. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun allJobs(): List<SetupJobDto> = synchronized(lock) { jobs.values.map { it.value } }

    /** Checks [request] and starts the setup in the background. [job] gives the progress. */
    fun start(request: NodeSetupRequestDto): SetupJobDto {
        val plan = SetupPlan.of(request, controller)
        // A retry keeps the management user the failed run created.
        val kept = request.retryOf
            ?.let { job(it) }
            ?.takeIf { it.nodeId == plan.node.id && it.state == "failed" }
            ?.steps
            ?.filter { it.status == "ok" && it.id in KEPT_ON_RETRY }
            ?.map { it.id }
            ?.toSet()
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
        val loginPassword = password?.takeIf { it.isNotEmpty() }
        val loginKey = keyFile?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            loginPassword != null -> SetupLogin.Password(host.trim(), port, user, loginPassword)
            loginKey != null -> SetupLogin.Key(host.trim(), port, user, loginKey)
            else -> throw IllegalArgumentException("enter a password or a key file for the initial login")
        }
    }

    /** A request that passed the checks: the work to do, and the node that the last step adds. */
    private class SetupPlan(
        val request: NodeSetupRequestDto,
        val node: NodeConfig,
        val initial: SetupLogin,
        val target: String,
    ) {
        /** The initial login user whose SSH login ends, when the request asks and it is not the management user. */
        val lockedUser: String? get() = request.login.user.takeIf { request.disableLoginUser && it != "root" && it != target }

        val hardenSsh get() = request.disableRootLogin || request.disablePasswordLogin || lockedUser != null

        fun steps(): List<SetupStepDto> {
            val userTitle = if (request.createUser != null) "Create user $target" else "Authorize the key for $target"
            val firewallStatus = if (request.configureFirewall) "pending" else "skipped"
            val sshStatus = if (hardenSsh) "pending" else "skipped"
            return listOf(
                SetupStepDto("connect", "Connect as ${initial.user}"),
                SetupStepDto("user", userTitle),
                SetupStepDto("key", "Log in with the node key as $target"),
                SetupStepDto("firewall", "Configure the firewall", firewallStatus),
                SetupStepDto("ssh", "Harden SSH", sshStatus),
                SetupStepDto("verify", "Reconnect from the orchestrator with the node key"),
                SetupStepDto("add", "Add ${node.id} and start provisioning"),
            )
        }

        companion object {
            fun of(request: NodeSetupRequestDto, controller: Controller): SetupPlan {
                val config = controller.config
                require(config.dashboard.allowConfigEdits) {
                    "config edits are disabled (dashboard.allow_config_edits=false)"
                }
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
                return SetupPlan(request, node, initialLogin(request, node), target)
            }

            private fun initialLogin(request: NodeSetupRequestDto, node: NodeConfig): SetupLogin {
                val user = request.login.user
                val password = request.login.password?.takeIf { it.isNotEmpty() }
                if (password != null) return SetupLogin.Password(node.host, node.sshPort, user, password)
                val keyFile = request.login.keyFile?.trim()?.takeIf { it.isNotEmpty() } ?: request.keyFile
                return SetupLogin.Key(node.host, node.sshPort, user, keyFile)
            }
        }
    }

    /** The open connection that a run works through, and how its login gets root. */
    private class Admin(val connection: SetupShell.Connection, val privilege: Privilege)

    /**
     * One run of a [SetupPlan]. It reports into [state]. All work goes through one open connection
     * ([Admin]). A check that needs a new login runs the login inside that connection. The only
     * other connection is the last check from outside, so a run opens two connections in all. Many
     * SSH logins in a short time make an intrusion-prevention system on the network of the
     * orchestrator, or a login blocker on the server, block the orchestrator.
     *
     * The firewall and the SSH changes stay armed (their revert timers run) until that last check
     * passes. If the check fails, the run reverts the two.
     */
    private inner class SetupRun(
        private val plan: SetupPlan,
        private val state: MutableStateFlow<SetupJobDto>,
        private val kept: Set<String> = emptySet(),
    ) {
        private val node = plan.node
        private val target = plan.target
        private val keyLogin = SetupLogin.Key(node.host, node.sshPort, plan.target, node.sshKeyFile)
        private val verifyCommand = if (plan.target == "root") "true" else "sudo -n true"
        private var connection: SetupShell.Connection? = null

        /** The address of the orchestrator as the server sees it (from the probe). */
        private var clientIp: String? = null

        /** Where the sshd of the server got the setup connection (from the probe). A new login inside it goes there. */
        private var serverAddr: String? = null
        private var serverPort: Int = node.sshPort

        /** The changes that have a revert timer, by marker name. Each value is the script that reverts it now. */
        private val armed = linkedMapOf<String, String>()

        suspend fun execute() {
            log("setup started (${plan.initial})")
            try {
                runCatchingCancellable { runSteps() }.onFailure { fail(it) }
            } finally {
                connection?.let { withContext(NonCancellable) { runCatchingCancellable { shell.close(it) } } }
            }
        }

        private suspend fun runSteps() {
            val admin = step("connect") { connect() }
            if ("user" !in kept) {
                step("user") { prepareUser(admin) }
            }
            step("key") { verifyInside(admin, "key login failed") }
            if (plan.request.configureFirewall) {
                step("firewall") { firewall(admin) }
            }
            if (plan.hardenSsh) {
                step("ssh") { hardenSsh(admin) }
            }
            step("verify") { verifyFromOutside(admin) }
            step("add") { controller.addNode(node) }
            state.update { it.copy(state = "succeeded", finishedAt = nowMillis()) }
            log("setup finished; provisioning starts on the next pass")
        }

        private fun fail(e: Throwable) {
            val now = nowMillis()
            val error = e.message ?: e.toString()
            // A block of new connections ends by itself. A retry before that fails the same way.
            val retryAt = if (blocked(e.message.orEmpty())) now + RETRY_HOLD_MS else null
            state.update { it.copy(state = "failed", error = error, finishedAt = now, retryAt = retryAt) }
            controller.log.error("setup", error, node.id)
        }

        /**
         * Runs one step. A String result is the detail of the step. For each other result, the
         * detail that the step set during its run stays.
         */
        private suspend fun <T> step(id: String, block: suspend () -> T): T {
            setStep(id, "running", null)
            return runCatchingCancellable {
                val result = block()
                val detail = (result as? String)?.takeIf { it.isNotBlank() }
                    ?: state.value.steps.first { it.id == id }.detail
                setStep(id, "ok", detail)
                result
            }.getOrElse { e ->
                setStep(id, "failed", e.message)
                throw e
            }
        }

        private fun setStep(id: String, status: String, detail: String?) {
            updateStep(id) { it.copy(status = status, detail = detail) }
        }

        private fun setDetail(id: String, detail: String) {
            updateStep(id) { it.copy(detail = detail) }
        }

        private fun updateStep(id: String, change: (SetupStepDto) -> SetupStepDto) {
            state.update { job -> job.copy(steps = job.steps.map { if (it.id == id) change(it) else it }) }
        }

        /** Opens the connection of the run and reads the facts of the server. */
        private suspend fun connect(): Admin {
            val (opened, note) = openConnection()
            connection = opened
            val probe = shell.run(opened, "bash -s", SetupScripts.PROBE, PROBE_TIMEOUT)
            SetupShell.connectionError(probe)?.let { throw IllegalStateException(explain(it)) }
            check(probe.ok) { "probe failed: ${probe.errorText()}" }
            val facts = probe.facts()
            val os = "${facts["ID"]} ${facts["VERSION_ID"]}"
            check(facts["ID"] == "ubuntu" && facts["VERSION_ID"] in NodeReconciler.SUPPORTED_UBUNTU) {
                "unsupported system: $os"
            }
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

        /**
         * Opens a connection with the initial login. If an earlier run disabled that login, the
         * run continues with the node key. Then the second value is a note for the step.
         */
        private suspend fun openConnection(): Pair<SetupShell.Connection, String?> =
            try {
                shell.connect(plan.initial) to null
            } catch (e: SetupConnectionException) {
                if (e.message != SetupShell.LOGIN_REJECTED) throw IllegalStateException(e.message)
                try {
                    shell.connect(keyLogin) to NODE_KEY_NOTE
                } catch (_: SetupConnectionException) {
                    throw IllegalStateException(e.message)
                }
            }

        /** Exempts the orchestrator from login blockers first. Then it prepares the management user. */
        private suspend fun prepareUser(admin: Admin): String {
            val exempt = clientIp?.let {
                root(
                    admin,
                    "exempting the orchestrator from login blockers",
                    SetupScripts.exemptFromBlockers(it),
                    BLOCKERS_TIMEOUT,
                )
            }
            val publicKey = keys.existing(node.sshKeyFile).publicKey
            val script = SetupScripts.prepareUser(
                user = plan.target,
                publicKey = publicKey,
                create = plan.request.createUser != null,
                dockerGroup = plan.request.dockerGroup,
            )
            val output = root(admin, "preparing $target", script)
            return listOfNotNull(output.changes(), exempt?.changes())
                .filter { it.isNotBlank() }
                .joinToString("; ")
                .ifEmpty { "already set up" }
        }

        /**
         * Does a new login with the node key inside the setup connection. The login proves the key,
         * the current settings of sshd and sudo, with no other connection from the orchestrator.
         * If the probe did not tell where sshd got the setup connection, the login is direct.
         */
        private suspend fun verifyInside(admin: Admin, what: String) {
            val address = serverAddr
            val result = if (address != null) {
                shell.runThrough(admin.connection, address, serverPort, keyLogin, verifyCommand, KEY_LOGIN_TIMEOUT)
            } else {
                shell.run(keyLogin, verifyCommand, timeout = KEY_LOGIN_TIMEOUT)
            }
            checkKeyLogin(result, what)
        }

        private fun checkKeyLogin(result: ProcessResult, what: String, finding: BlockFinding? = null) {
            SetupShell.connectionError(result)?.let { error ->
                val reason = if (finding != null) "$error ${finding.text}" else explain(error)
                throw IllegalStateException("$what: $reason")
            }
            check(result.ok) { "$what: ${plan.target} cannot use passwordless sudo (${result.errorText()})" }
        }

        /**
         * Does the one direct login with the node key, as the orchestrator connects from now on.
         * The login goes through the new firewall and the hardened sshd. If it passes, the armed
         * changes stay. If it fails, the run reverts each of them.
         */
        private suspend fun verifyFromOutside(admin: Admin): String? {
            val started = nowMillis()
            var result = shell.run(keyLogin, verifyCommand, timeout = KEY_LOGIN_TIMEOUT)
            var finding: BlockFinding? = null
            if (SetupShell.connectionError(result)?.let(SetupShell::isDropped) == true) {
                finding = blockFinding(admin, started)
                // The server held a ban and HomeGAP removed it: the login gets one more try.
                if (finding?.lifted == true) {
                    result = shell.run(keyLogin, verifyCommand, timeout = KEY_LOGIN_TIMEOUT)
                }
            }
            val limited = UFW in armed &&
                node.sshAllowFrom.isNotEmpty() &&
                SetupShell.connectionError(result)?.let(SetupShell::isDropped) == true
            runCatchingCancellable {
                // The new firewall is the likeliest cause when it only lets listed sources reach SSH.
                check(!limited) {
                    val address = clientIp?.let { " ($it)" }.orEmpty()
                    "with SSH limited to ${node.sshAllowFrom.joinToString()}, the orchestrator" +
                        "$address could not log in; add its address to the allowed sources"
                }
                checkKeyLogin(result, "key login from the orchestrator failed", finding)
            }.onFailure { e ->
                if (armed.isEmpty()) throw e
                val undone = revertArmed(admin)
                throw IllegalStateException("${e.message} ($undone)")
            }
            if (armed.isEmpty()) return null
            root(admin, "keeping the changes", SetupScripts.confirm(armed.keys.toList()), CONFIRM_TIMEOUT)
            val keptChanges = armed.keys.joinToString(" and ") { if (it == UFW) "firewall" else "SSH settings" }
            armed.clear()
            return "$keptChanges kept"
        }

        /**
         * Asks the server, through the setup connection, what it holds against the orchestrator.
         * Returns null if the server cannot tell: the address of the orchestrator is not known, or
         * the script failed.
         */
        private suspend fun blockFinding(admin: Admin, since: Long): BlockFinding? {
            val ip = clientIp ?: return null
            val seconds = (nowMillis() - since) / 1000 + BLOCK_REPORT_MARGIN_SECONDS
            val result = runCatchingCancellable {
                val script = SetupScripts.blockReport(ip, seconds)
                shell.runAsRoot(admin.connection, admin.privilege, script, BLOCK_REPORT_TIMEOUT)
            }.getOrNull()
            return result?.takeIf { it.ok }?.let { BlockFinding.of(ip, it.stdout) }
        }

        /** Reverts each armed change now. An error does not stop it, because the timer reverts the change soon. */
        private suspend fun revertArmed(admin: Admin): String {
            val names = armed.keys.reversed().map {
                if (it == UFW) "firewall turned off again" else "SSH settings reverted"
            }
            armed.values.reversed().forEach { revert ->
                withContext(NonCancellable) {
                    runCatchingCancellable {
                        shell.runAsRoot(admin.connection, admin.privilege, revert, REVERT_TIMEOUT)
                    }
                }
            }
            armed.clear()
            return names.joinToString(", ")
        }

        /** Runs [script] as root through the connection of the run. Returns its output, or throws. */
        private suspend fun root(
            admin: Admin,
            what: String,
            script: String,
            timeout: Duration = SCRIPT_TIMEOUT,
        ): String {
            val result = shell.runAsRoot(admin.connection, admin.privilege, script, timeout)
            SetupShell.connectionError(result)?.let { throw IllegalStateException("$what: ${explain(it)}") }
            check(result.ok) { "$what: ${result.errorText()}" }
            return result.stdout
        }

        /**
         * Hardens sshd (armed). Then a new key login inside the setup connection proves that the
         * key still works. The script checks with `sshd -T -C` that password login and root login
         * are off, for the users and for the address of the orchestrator. A login that must fail is
         * not a good check, because a login blocker counts it against the orchestrator. The last
         * check from outside keeps or reverts the change.
         */
        private suspend fun hardenSsh(admin: Admin): String {
            val script = SetupScripts.hardenSsh(
                disableRootLogin = plan.request.disableRootLogin,
                disablePasswordLogin = plan.request.disablePasswordLogin,
                users = listOf(plan.initial.user, plan.target, "root").distinct(),
                clientAddr = clientIp,
                denyUsers = listOfNotNull(plan.lockedUser),
                // With a login other than root, root login is a bonus: a problem with it is noted only.
                softRootLogin = plan.initial.user != "root",
            )
            val output = root(admin, "hardening SSH", script, HARDEN_TIMEOUT)
            if (output.lines().any { it.startsWith("CHANGED ") }) {
                armed[SSH] = SetupScripts.REVERT_SSH
            }
            runCatchingCancellable { verifyInside(admin, "after hardening SSH, key login failed") }
                .onFailure { e -> throw IllegalStateException("${e.message} (${revertArmed(admin)})") }
            return (listOf(output.changes()) + output.notes()).filter { it.isNotBlank() }.joinToString("; ")
        }

        /** Applies the firewall and enables it (armed). The last check from outside keeps or reverts it. */
        private suspend fun firewall(admin: Admin): String {
            val script = SetupScripts.firewall(FirewallRules.specs(node))
            val output = root(admin, "configuring the firewall", script, FIREWALL_TIMEOUT)
            armed[UFW] = SetupScripts.REVERT_UFW
            val ssh = when {
                node.sshAllowFrom.isEmpty() -> "SSH from anywhere"
                else -> "SSH from ${node.sshAllowFrom.joinToString()}"
            }
            return (listOf("$ssh; web, WireGuard and raw ports open") + output.changes().lines())
                .filter { it.isNotBlank() }
                .joinToString("; ")
        }

        /**
         * Adds a hint to the error of a dropped connection, if this run had a connection before.
         * The server gave an answer a moment ago, so the probable cause is a new block of the
         * address of the orchestrator.
         */
        private fun explain(error: String): String =
            if (connection != null && SetupShell.isDropped(error)) "$error $BLOCKED_HINT" else error

        private fun log(message: String) = controller.log.change("setup", message, node.id)
    }

    private companion object {
        const val RETRY_HOLD_MS = 3 * 60_000L
        const val BLOCK_REPORT_MARGIN_SECONDS = 5

        val PROBE_TIMEOUT = 30.seconds
        val KEY_LOGIN_TIMEOUT = 60.seconds
        val BLOCKERS_TIMEOUT = 120.seconds
        val HARDEN_TIMEOUT = 120.seconds
        val FIREWALL_TIMEOUT = 300.seconds
        val CONFIRM_TIMEOUT = 30.seconds
        val BLOCK_REPORT_TIMEOUT = 30.seconds
        val REVERT_TIMEOUT = 60.seconds
        val SCRIPT_TIMEOUT = 600.seconds

        val SUPPORTED_ARCH = setOf("x86_64", "aarch64")

        // The marker names of the armed changes.
        const val UFW = "ufw"
        const val SSH = "ssh"

        const val NODE_KEY_NOTE =
            "the initial login is no longer accepted (an earlier run hardened SSH), " +
                "so setup continues with the node key"

        /** True if the failure text [message] is a connection that got no answer. */
        fun blocked(message: String) =
            listOf(SetupShell.STALLED, SetupShell.NO_ANSWER, SetupShell.DROPPED).any { it in message }

        const val BLOCKED_HINT =
            "It answered earlier in this run, so something is now cutting off new SSH connections from the " +
                "orchestrator. Most often that is an intrusion-prevention system on the orchestrator's own " +
                "network (UniFi Threat Management, Suricata or Snort on pfSense/OPNsense, Firewalla) treating " +
                "several SSH logins in a row as an outbound SSH scan; otherwise a login blocker on the server " +
                "(fail2ban, sshguard, CrowdSec) or a provider firewall. Allow the orchestrator to reach this " +
                "server there, or wait a few minutes for the block to expire, then retry."
    }
}

/** What the server holds against the orchestrator when a login from it got no answer. */
internal class BlockFinding(val text: String, val lifted: Boolean) {
    companion object {
        /** Reads the output of [SetupScripts.blockReport] for the orchestrator at [ip]. */
        fun of(ip: String, output: String): BlockFinding {
            val lines = output.lines()
            val banned = lines.filter { it.startsWith("BANNED=") }.map { it.substringAfter("=") }
            val open = lines.firstOrNull { it.startsWith("OPEN=") }?.substringAfter("=")?.toIntOrNull() ?: 0
            return when {
                banned.isNotEmpty() -> BlockFinding(
                    "On the server, ${banned.joinToString(" and ")} had banned the orchestrator ($ip). " +
                        "HomeGAP removed the ban.",
                    lifted = true,
                )
                "REFUSED=yes" in lines -> BlockFinding(
                    "The sshd of the server refused the orchestrator ($ip) for a short time, " +
                        "as a penalty for earlier failed logins. Wait a few minutes, then retry.",
                    lifted = false,
                )
                else -> BlockFinding(noBanText(ip, open), lifted = false)
            }
        }

        private fun noBanText(ip: String, open: Int): String {
            val heldConnections = when {
                open > 1 ->
                    "The server still holds $open SSH connections from it, " +
                        "so the packets of the new connection stop on the way. "
                else -> ""
            }
            return "The server holds no ban for the orchestrator ($ip), and its sshd refused nothing. " +
                heldConnections +
                "The block is in front of the server. Most often that is an intrusion-prevention system " +
                "on the network of the orchestrator (UniFi Threat Management, Suricata or Snort on " +
                "pfSense/OPNsense, Firewalla), which takes several SSH logins in a row for an outbound SSH scan. " +
                "It can also be a firewall of the provider. Allow the orchestrator to reach this server there, " +
                "or wait a few minutes, then retry."
        }
    }
}

private fun ProcessResult.facts(): Map<String, String> =
    stdout.lines()
        .mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
        }
        .toMap()

/** The `NOTE …` lines that a setup script printed: facts that are not failures. */
private fun String.notes(): List<String> =
    lines().filter { it.startsWith("NOTE ") }.map { it.removePrefix("NOTE ") }

/** The `CHANGED …` lines that a setup script printed, in one text for the detail of a step. */
private fun String.changes(): String =
    lines().filter { it.startsWith("CHANGED ") }.joinToString("; ") { it.removePrefix("CHANGED ") }

private fun ProcessResult.errorText(): String =
    stderr.ifBlank { stdout }
        .trim()
        .takeLast(ERROR_TEXT_LENGTH)
        .ifEmpty { "exit $exitCode" }

private const val ERROR_TEXT_LENGTH = 500

/** The steps whose result stays on the server after a failed run. A retry does not run them again. */
private val KEPT_ON_RETRY = setOf("user")
