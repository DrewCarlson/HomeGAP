package homegap.setup

import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the orchestrator logs in to a server during setup. */
sealed interface SetupLogin {
    val host: String
    val port: Int
    val user: String

    /** Password login (the initial VPS credentials). */
    class Password(override val host: String, override val port: Int, override val user: String, val password: String) : SetupLogin {
        override fun toString() = "$user@$host:$port (password)"
    }

    /** Key login (the node's management key, or a key the VPS was created with). */
    class Key(override val host: String, override val port: Int, override val user: String, val keyFile: String) : SetupLogin {
        override fun toString() = "$user@$host:$port (key)"
    }
}

/** How a login gains root: it is root, sudo without a password, or sudo with the login password. */
enum class Privilege(val wire: String) {
    Root("root"), NoPasswdSudo("nopasswd"), PasswordSudo("password"), None("none");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value } ?: None
    }
}

/** A connection could not be opened; [message] is readable (see [SetupShell.connectionError]). */
class SetupConnectionException(message: String) : Exception(message)

/**
 * SSH for the add-node wizard, which can log in with a password.
 *
 * A setup job holds one [Connection] open with the initial login and runs every command through it,
 * so a run costs a single login instead of one per command: servers with fail2ban, sshguard or
 * CrowdSec ban addresses that open many logins in a short time. Only the checks that must prove a
 * *new* login works ([run] with a [SetupLogin]) open a fresh connection.
 *
 * Passwords go to OpenSSH through `SSH_ASKPASS`: a small script that prints an environment variable
 * set only for that `ssh` child. They never appear in arguments, files or logs.
 */
class SetupShell(private val knownHostsFile: String, private val askpassScript: String) {
    init {
        Files.writeAtomic(askpassScript, ASKPASS, "700".toInt(8))
    }

    /** A held-open master connection; commands through it reuse its login. Close it with [close]. */
    class Connection internal constructor(val login: SetupLogin, internal val controlPath: String)

    /**
     * Opens a master connection for [login], or throws [SetupConnectionException] with a readable
     * reason. Like [homegap.clients.SshSession], `ssh -f` keeps inherited descriptors, so its output
     * goes to a log file rather than to capture pipes.
     */
    suspend fun connect(login: SetupLogin): Connection {
        Files.mkdirs(CONTROL_DIR)
        Files.chmodPath(CONTROL_DIR, "700".toInt(8))
        val controlPath = "$CONTROL_DIR/${Crypto.token(10)}.sock"
        val logFile = "$controlPath.log"
        Files.writeAtomic(logFile, "", "600".toInt(8))
        val (auth, env) = authFor(login)
        val argv = baseArgs(login) + auth +
            listOf("-M", "-f", "-N", "-o", "ControlPath=$controlPath", "-o", "ControlPersist=10m", target(login))
        try {
            val child = Subprocess.start(argv, logFile, env)
            val code = withContext(Dispatchers.IO) { child.process.waitFor(60.seconds) }
            if (code == null) {
                child.kill()
                child.awaitExit()
                throw SetupConnectionException(NO_ANSWER)
            }
            if (code != 0) {
                val log = Files.readTextOrNull(logFile).orEmpty()
                throw SetupConnectionException(connectionError(ProcessResult(255, "", log)) ?: "SSH failed (exit $code).")
            }
            return Connection(login, controlPath)
        } finally {
            Files.delete(logFile)
        }
    }

    /**
     * Runs [command] through [connection] (no new login). `ProxyCommand=false` is never used while the
     * master is up; if it is gone, ssh fails at once instead of opening a fresh connection without the
     * login's credentials (a failed login that a blocker would count).
     */
    suspend fun run(connection: Connection, command: String, stdin: String = "", timeout: Duration = 120.seconds): ProcessResult {
        val login = connection.login
        val argv = baseArgs(login) +
            listOf("-o", "ControlPath=${connection.controlPath}", "-o", "ControlMaster=no", "-o", "ProxyCommand=false", target(login), command)
        val r = Subprocess.run(argv, stdin, timeout)
        if (r.exitCode == 255 && !Files.exists(connection.controlPath)) {
            return r.copy(stderr = "Connection closed: the setup connection to the server was lost.")
        }
        return r
    }

    /** Runs [command] over a fresh connection with [login]: proves that this login works right now. */
    suspend fun run(login: SetupLogin, command: String, stdin: String = "", timeout: Duration = 120.seconds): ProcessResult {
        val (auth, env) = authFor(login)
        val argv = baseArgs(login) + auth + listOf("-o", "ControlPath=none", target(login), command)
        return Subprocess.run(argv, stdin, timeout, env)
    }

    /** Closes [connection]'s master. */
    suspend fun close(connection: Connection) {
        val argv = baseArgs(connection.login) + listOf("-o", "ControlPath=${connection.controlPath}", "-O", "exit", target(connection.login))
        Subprocess.run(argv, timeout = 15.seconds)
        Files.delete(connection.controlPath)
    }

    /**
     * Runs [script] as root through [connection]. [privilege] is how its login gains root; with
     * [Privilege.PasswordSudo] the password is the first line on stdin, consumed by `sudo -S` before
     * bash reads the script.
     */
    suspend fun runAsRoot(connection: Connection, privilege: Privilege, script: String, timeout: Duration = 600.seconds): ProcessResult {
        val body = "set -euo pipefail\n$script\n"
        return when (privilege) {
            Privilege.Root -> run(connection, "bash -s", body, timeout)
            Privilege.NoPasswdSudo -> run(connection, "sudo -n bash -s", body, timeout)
            Privilege.PasswordSudo -> {
                val password = (connection.login as? SetupLogin.Password)?.password ?: error("sudo needs the login password")
                run(connection, "sudo -S -k -p '' bash -s", "$password\n$body", timeout)
            }
            Privilege.None -> error("${connection.login.user} cannot become root (not root, and sudo is unavailable)")
        }
    }

    private fun baseArgs(login: SetupLogin) =
        listOf("ssh", "-p", login.port.toString()) + COMMON_OPTIONS + listOf("-o", "UserKnownHostsFile=$knownHostsFile")

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

    /** SHA256 fingerprint of the host key recorded for [host]:[port] (after a successful login). */
    suspend fun recordedHostKey(host: String, port: Int): String? {
        val name = if (port == 22) host else "[$host]:$port"
        val r = Subprocess.run(listOf("ssh-keygen", "-l", "-F", name, "-f", knownHostsFile), timeout = 10.seconds)
        return Regex("SHA256:[A-Za-z0-9+/=]+").findAll(r.stdout).map { it.value }.firstOrNull()
    }

    /** Forgets the recorded host key for [host]:[port] (the server was rebuilt). */
    suspend fun forgetHostKey(host: String, port: Int) {
        val name = if (port == 22) host else "[$host]:$port"
        if (Files.exists(knownHostsFile)) Subprocess.run(listOf("ssh-keygen", "-R", name, "-f", knownHostsFile), timeout = 10.seconds)
    }

    companion object {
        private const val SECRET_ENV = "HOMEGAP_SSH_SECRET"

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

        /** A readable reason for a failed SSH connection, from ssh's stderr (null if it connected). */
        fun connectionError(r: ProcessResult): String? {
            if (r.exitCode != 255) return null
            val err = r.stderr
            return when {
                "REMOTE HOST IDENTIFICATION HAS CHANGED" in err || "Host key verification failed" in err ->
                    HOST_KEY_CHANGED
                "Permission denied" in err -> LOGIN_REJECTED
                "Connection refused" in err -> "Nothing is listening for SSH on that port."
                "Could not resolve hostname" in err -> "The host name does not resolve."
                "timed out" in err || "No route to host" in err || "Network is unreachable" in err -> NO_ANSWER
                "Connection closed" in err || "Connection reset" in err || "kex_exchange_identification" in err -> DROPPED
                err.isBlank() -> DROPPED
                else -> err.trim().lines().last { it.isNotBlank() }.take(300)
            }
        }

        const val LOGIN_REJECTED = "Login rejected: wrong user, password or key."

        /** Whether [error] (from [connectionError]) means packets were dropped or the connection was cut. */
        fun isDropped(error: String) = error == NO_ANSWER || error == DROPPED

        const val NO_ANSWER =
            "The server did not answer (wrong address, a firewall in front of it, or a login blocker on the server that banned this address)."

        const val DROPPED =
            "The server closed the connection before login (a login blocker such as fail2ban, sshguard or CrowdSec, or sshd refusing this address)."

        const val HOST_KEY_CHANGED =
            "The server's host key differs from the one recorded for this address. If the server was rebuilt, forget the old key and retry."
    }
}
