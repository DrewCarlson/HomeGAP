package homegap.setup

import homegap.clients.SshSession.Companion.shQuote
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the orchestrator logs in to a server during setup. */
sealed interface SetupLogin {
    val host: String
    val port: Int
    val user: String

    /** Password login (the initial VPS credentials). */
    class Password(
        override val host: String,
        override val port: Int,
        override val user: String,
        val password: String,
    ) : SetupLogin {
        override fun toString() = "$user@$host:$port (password)"
    }

    /** Key login (the management key of the node, or a key that the VPS got when it was made). */
    class Key(
        override val host: String,
        override val port: Int,
        override val user: String,
        val keyFile: String,
    ) : SetupLogin {
        override fun toString() = "$user@$host:$port (key)"
    }
}

/** How a login gets root: it is root, sudo without a password, or sudo with the login password. */
enum class Privilege(val wire: String) {
    Root("root"),
    NoPasswdSudo("nopasswd"),
    PasswordSudo("password"),
    None("none"),
}

/** A connection did not open. [message] is text for the operator (see [SetupShell.connectionError]). */
class SetupConnectionException(message: String) : Exception(message)

/**
 * SSH for the add-node wizard, which can log in with a password.
 *
 * A setup job holds one [Connection] open with the initial login and runs each command through it.
 * Thus a run uses one login, not one login for each command. A server with fail2ban, sshguard or
 * CrowdSec bans an address that opens many logins in a short time. An intrusion-prevention system
 * on the network of the orchestrator (UniFi Threat Management, Suricata/Snort) blocks a source that
 * opens many outbound SSH connections in a row, as a "scan". A check that needs a *new* login runs
 * the login inside the open connection ([runThrough]). Only the last check from outside ([run]
 * with a [SetupLogin]) opens a second connection.
 *
 * A password goes to OpenSSH through `SSH_ASKPASS`: a small script that prints an environment
 * variable, which only that `ssh` child has. A password is never in arguments, files or logs.
 */
class SetupShell(private val knownHostsFile: String, private val askpassScript: String) {
    init {
        Files.writeAtomic(askpassScript, ASKPASS, "700".toInt(8))
    }

    /** An open master connection. A command through it uses its login again. Close it with [close]. */
    class Connection internal constructor(val login: SetupLogin, internal val controlPath: String)

    /**
     * Opens a master connection for [login], or throws [SetupConnectionException] with a reason
     * for the operator. As in [homegap.clients.SshSession], `ssh -f` keeps the descriptors that it
     * inherits, so its output goes to a log file and not to capture pipes.
     */
    suspend fun connect(login: SetupLogin): Connection {
        Files.mkdirs(CONTROL_DIR)
        Files.chmodPath(CONTROL_DIR, "700".toInt(8))
        val controlPath = "$CONTROL_DIR/${Crypto.token(10)}.sock"
        val (auth, env) = authFor(login)
        val argv = buildList {
            addAll(baseArgs(login))
            addAll(auth)
            addAll(listOf("-M", "-f", "-N", "-o", "ControlPath=$controlPath", "-o", "ControlPersist=10m"))
            add(target(login))
        }
        // `ssh -f` returns once logged in; the master it leaves behind detaches from our pipes.
        val result = Subprocess.run(argv, timeout = 60.seconds, env = env)
        if (!result.ok) {
            throw SetupConnectionException(connectionError(result) ?: "SSH failed (exit ${result.exitCode}).")
        }
        return Connection(login, controlPath)
    }

    /**
     * Runs [command] through [connection], with no new login. ssh does not use `ProxyCommand=false`
     * while the master is there. If the master is gone, ssh fails immediately. It does not open a
     * new connection without the credentials of the login, which a blocker counts as a failed login.
     */
    suspend fun run(
        connection: Connection,
        command: String,
        stdin: String = "",
        timeout: Duration = 120.seconds,
    ): ProcessResult {
        val login = connection.login
        val argv = buildList {
            addAll(baseArgs(login))
            addAll(listOf("-o", "ControlPath=${connection.controlPath}", "-o", "ControlMaster=no"))
            addAll(listOf("-o", "ProxyCommand=false", target(login), command))
        }
        return Subprocess.run(argv, stdin, timeout).orConnectionLost(connection)
    }

    /** Runs [command] over a new connection with [login]. It proves that this login works now. */
    suspend fun run(
        login: SetupLogin,
        command: String,
        stdin: String = "",
        timeout: Duration = 120.seconds,
    ): ProcessResult {
        val (auth, env) = authFor(login)
        val argv = buildList {
            addAll(baseArgs(login))
            addAll(auth)
            addAll(listOf("-o", "ControlPath=none", target(login), command))
        }
        return Subprocess.run(argv, stdin, timeout, env)
    }

    /**
     * Runs [command] with a new [login] *inside* [via]. The new SSH session gets to the sshd of the
     * server at [address]:[port] through a stdio forward (`-W`) of the open connection. It proves
     * the key, the settings of sshd and sudo for that login, with no other connection from the
     * orchestrator. An intrusion-prevention system on the network of the orchestrator, or a login
     * blocker on the server, counts each connection as one more login in a short time.
     *
     * The forward arrives over loopback, so it does not go through the firewall of the server. A
     * last check of the firewall still needs [run] with a [SetupLogin].
     */
    suspend fun runThrough(
        via: Connection,
        address: String,
        port: Int,
        login: SetupLogin.Key,
        command: String,
        timeout: Duration = 60.seconds,
    ): ProcessResult {
        val hop = if (':' in address) "[$address]:$port" else "$address:$port"
        val proxyArgs = baseArgs(via.login) + listOf(
            "-o", "ControlPath=${via.controlPath}", "-o", "ControlMaster=no", "-o", "ProxyCommand=none",
            "-W", hop, target(via.login),
        )
        val proxy = proxyArgs.joinToString(" ") { shQuote(it) }.replace("%", "%%")
        val (auth, env) = authFor(login)
        // Host keys are checked against the server's own name, the same key a direct login sees.
        val argv = buildList {
            addAll(baseArgs(login))
            addAll(auth)
            addAll(listOf("-o", "ControlPath=none", "-o", "ProxyCommand=$proxy", target(login), command))
        }
        return Subprocess.run(argv, "", timeout, env).orConnectionLost(via)
    }

    /** ssh exits with 255 when the master of [connection] is gone. Then the error text says so. */
    private fun ProcessResult.orConnectionLost(connection: Connection): ProcessResult =
        if (exitCode == SSH_FAILED && !Files.exists(connection.controlPath)) copy(stderr = CONNECTION_LOST) else this

    /** Closes the master of [connection]. */
    suspend fun close(connection: Connection) {
        val login = connection.login
        val argv = baseArgs(login) + listOf("-o", "ControlPath=${connection.controlPath}", "-O", "exit", target(login))
        Subprocess.run(argv, timeout = 15.seconds)
        Files.delete(connection.controlPath)
    }

    /**
     * Runs [script] as root through [connection]. [privilege] is how its login gets root. With
     * [Privilege.PasswordSudo], the password is the first line on stdin. `sudo -S` reads it before
     * bash reads the script.
     */
    suspend fun runAsRoot(
        connection: Connection,
        privilege: Privilege,
        script: String,
        timeout: Duration = 600.seconds,
    ): ProcessResult {
        val body = "set -euo pipefail\n$script\n"
        return when (privilege) {
            Privilege.Root -> run(connection, "bash -s", body, timeout)
            Privilege.NoPasswdSudo -> run(connection, "sudo -n bash -s", body, timeout)
            Privilege.PasswordSudo -> {
                val password = (connection.login as? SetupLogin.Password)?.password
                    ?: error("sudo needs the login password")
                run(connection, "sudo -S -k -p '' bash -s", "$password\n$body", timeout)
            }
            Privilege.None -> error("${connection.login.user} cannot become root (not root, and sudo is unavailable)")
        }
    }

    /**
     * A password goes only to a host key that is in the known hosts file, so a password login
     * never accepts a new key by itself. ssh uses the first value of an option, so the strict
     * value comes before [COMMON_OPTIONS].
     */
    private fun baseArgs(login: SetupLogin): List<String> = buildList {
        addAll(listOf("ssh", "-p", login.port.toString()))
        if (login is SetupLogin.Password) {
            addAll(listOf("-o", "StrictHostKeyChecking=yes"))
        }
        addAll(COMMON_OPTIONS)
        addAll(listOf("-o", "UserKnownHostsFile=$knownHostsFile"))
    }

    private val scannedHostsFile = "$knownHostsFile.scanned"

    private fun hostName(host: String, port: Int) = if (port == 22) host else "[$host]:$port"

    /**
     * Reads the host key of the server of [login] into a file of its own and returns its SHA256
     * fingerprint, or null if the server gives no key. The scan sends no credential.
     */
    suspend fun scanHostKey(login: SetupLogin): String? {
        Files.delete(scannedHostsFile)
        // One key type for each connection: a server that counts each connection without a
        // login as an attack sees one, not one for each type.
        val key = HOST_KEY_TYPES.firstNotNullOfOrNull { type ->
            val scan = Subprocess.run(
                listOf("ssh-keyscan", "-T", "10", "-p", login.port.toString(), "-t", type, login.host),
                timeout = 30.seconds,
            )
            scan.stdout.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        } ?: return null
        Files.writeAtomic(scannedHostsFile, key + "\n", "600".toInt(8))
        return scannedHostKey(login)
    }

    /** SHA256 fingerprint of the key that the last [scanHostKey] read for the server of [login]. */
    suspend fun scannedHostKey(login: SetupLogin): String? {
        if (!Files.exists(scannedHostsFile)) return null
        val name = hostName(login.host, login.port)
        val argv = listOf("ssh-keygen", "-l", "-F", name, "-f", scannedHostsFile)
        return FINGERPRINT.find(Subprocess.run(argv, timeout = 10.seconds).stdout)?.value
    }

    /** Puts the key of the last [scanHostKey] for the server of [login] into the known hosts file. */
    suspend fun trustScannedHostKey(login: SetupLogin) {
        val name = hostName(login.host, login.port)
        val found = Subprocess.run(listOf("ssh-keygen", "-F", name, "-f", scannedHostsFile), timeout = 10.seconds)
        val lines = found.stdout.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        check(lines.isNotEmpty()) { "The host key of ${login.host} is not there. Test the connection again." }
        forgetHostKey(login.host, login.port)
        val known = Files.readTextOrNull(knownHostsFile).orEmpty()
        val separator = if (known.isEmpty() || known.endsWith("\n")) "" else "\n"
        Files.writeAtomic(knownHostsFile, known + separator + lines.joinToString("\n") + "\n", "600".toInt(8))
        Files.delete(scannedHostsFile)
    }

    private fun target(login: SetupLogin) = "${login.user}@${login.host}"

    private fun authFor(login: SetupLogin): Pair<List<String>, Map<String, String>> = when (login) {
        is SetupLogin.Password -> PASSWORD_OPTIONS to mapOf(
            "SSH_ASKPASS" to askpassScript,
            "SSH_ASKPASS_REQUIRE" to "force",
            "DISPLAY" to ":0",
            SECRET_ENV to login.password,
        )
        is SetupLogin.Key -> listOf("-i", login.keyFile) + KEY_OPTIONS to emptyMap()
    }

    /** SHA256 fingerprint of the host key in the known hosts file for [host]:[port]. A good login puts it there. */
    suspend fun recordedHostKey(host: String, port: Int): String? {
        val argv = listOf("ssh-keygen", "-l", "-F", hostName(host, port), "-f", knownHostsFile)
        return FINGERPRINT.find(Subprocess.run(argv, timeout = 10.seconds).stdout)?.value
    }

    /** Removes the host key for [host]:[port] from the known hosts file (the server was rebuilt). */
    suspend fun forgetHostKey(host: String, port: Int) {
        if (!Files.exists(knownHostsFile)) return
        Subprocess.run(listOf("ssh-keygen", "-R", hostName(host, port), "-f", knownHostsFile), timeout = 10.seconds)
    }

    companion object {
        private const val SECRET_ENV = "HOMEGAP_SSH_SECRET"

        /** The exit code of ssh for an error of its own, not of the remote command. */
        private const val SSH_FAILED = 255
        private const val ERROR_TEXT_LENGTH = 300
        private const val CONNECTION_LOST = "Connection closed: the setup connection to the server was lost."
        private val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/=]+")

        /** In the order ssh prefers them. */
        private val HOST_KEY_TYPES = listOf("ed25519", "ecdsa", "rsa")

        /** Short, so socket paths stay under the platform's limit (about 104 bytes). */
        private const val CONTROL_DIR = "/tmp/homegap-setup"

        /** Prints the password from [SECRET_ENV] for ssh's prompt. */
        private val ASKPASS = $$"""
            |#!/bin/sh
            |printf '%s\n' "$HOMEGAP_SSH_SECRET"
            |""".trimMargin()

        private val COMMON_OPTIONS = listOf(
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15",
            "-o", "ServerAliveInterval=15",
            "-o", "LogLevel=ERROR",
        )
        private val PASSWORD_OPTIONS = listOf(
            "-o", "BatchMode=no",
            "-o", "PubkeyAuthentication=no",
            "-o", "PreferredAuthentications=password,keyboard-interactive",
            "-o", "NumberOfPasswordPrompts=1",
        )
        private val KEY_OPTIONS = listOf(
            "-o", "BatchMode=yes",
            "-o", "IdentitiesOnly=yes",
            "-o", "PasswordAuthentication=no",
        )

        /** The reason for a failed SSH connection, from the stderr of ssh. Null if ssh connected. */
        fun connectionError(result: ProcessResult): String? {
            val err = result.stderr
            // Killed by our timeout: ssh may exit 255 (SIGTERM) or report the signal (SIGKILL).
            if ("[homegap] process timed out" in err) return STALLED
            if (result.exitCode != SSH_FAILED) return null
            return when {
                "REMOTE HOST IDENTIFICATION HAS CHANGED" in err || "Host key verification failed" in err ->
                    HOST_KEY_CHANGED
                "Permission denied" in err -> LOGIN_REJECTED
                "Connection refused" in err -> "Nothing is listening for SSH on that port."
                "Could not resolve hostname" in err -> "The host name does not resolve."
                // Connected, then nothing more got through: ssh's own banner timeout.
                "during banner exchange" in err -> STALLED
                "timed out" in err || "No route to host" in err || "Network is unreachable" in err -> NO_ANSWER
                "Connection closed" in err || "Connection reset" in err || "kex_exchange_identification" in err ->
                    DROPPED
                err.isBlank() -> DROPPED
                else -> err.trim().lines().last { it.isNotBlank() }.take(ERROR_TEXT_LENGTH)
            }
        }

        const val LOGIN_REJECTED = "Login rejected: wrong user, password or key."

        /** True if [error] (from [connectionError]) is a loss of packets or a cut connection. */
        fun isDropped(error: String) = error == NO_ANSWER || error == DROPPED || error == STALLED

        const val STALLED =
            "The server accepted the connection, then the SSH session stalled: packets stopped getting through."

        const val NO_ANSWER =
            "The server did not answer (wrong address, a firewall in front of it, " +
                "or a login blocker on the server that banned this address)."

        const val DROPPED =
            "The server closed the connection before login " +
                "(a login blocker such as fail2ban, sshguard or CrowdSec, or sshd refusing this address)."

        const val HOST_KEY_CHANGED =
            "The server's host key differs from the one recorded for this address. " +
                "If the server was rebuilt, forget the old key and retry."
    }
}
