package homegap.setup

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

/**
 * One-off SSH commands for the add-node wizard. Unlike [homegap.clients.SshSession] it never
 * shares a ControlMaster (each call is a fresh connection, which is what proves access still
 * works after hardening) and it can log in with a password.
 *
 * Passwords go to OpenSSH through `SSH_ASKPASS`: a small script that prints an environment variable
 * set only for that `ssh` child. They never appear in arguments, files or logs.
 */
class SetupShell(private val knownHostsFile: String, private val askpassScript: String) {
    init {
        Files.writeAtomic(askpassScript, ASKPASS, "700".toInt(8))
    }

    suspend fun run(login: SetupLogin, command: String, stdin: String = "", timeout: Duration = 120.seconds): ProcessResult {
        val (argv, env) = when (login) {
            is SetupLogin.Password -> PASSWORD_OPTIONS to mapOf(
                "SSH_ASKPASS" to askpassScript,
                "SSH_ASKPASS_REQUIRE" to "force",
                "DISPLAY" to ":0",
                SECRET_ENV to login.password,
            )
            is SetupLogin.Key -> listOf("-i", login.keyFile) + KEY_OPTIONS to emptyMap()
        }
        val full = listOf("ssh", "-p", login.port.toString()) + COMMON_OPTIONS + listOf("-o", "UserKnownHostsFile=$knownHostsFile") +
            argv + listOf("${login.user}@${login.host}", command)
        return Subprocess.run(full, stdin, timeout, env)
    }

    /**
     * Runs [script] as root. [privilege] is how this login gains root; with [Privilege.PasswordSudo]
     * the password is the first line on stdin, consumed by `sudo -S` before bash reads the script.
     */
    suspend fun runAsRoot(login: SetupLogin, privilege: Privilege, script: String, timeout: Duration = 600.seconds): ProcessResult {
        val body = "set -euo pipefail\n$script\n"
        return when (privilege) {
            Privilege.Root -> run(login, "bash -s", body, timeout)
            Privilege.NoPasswdSudo -> run(login, "sudo -n bash -s", body, timeout)
            Privilege.PasswordSudo -> {
                val password = (login as? SetupLogin.Password)?.password ?: error("sudo needs the login password")
                run(login, "sudo -S -k -p '' bash -s", "$password\n$body", timeout)
            }
            Privilege.None -> error("${login.user} cannot become root (not root, and sudo is unavailable)")
        }
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

        /** Prints the password from [SECRET_ENV] for ssh's prompt. */
        private val ASKPASS = $$"""
            |#!/bin/sh
            |printf '%s\n' "$HOMEGAP_SSH_SECRET"
            |""".trimMargin()

        private val COMMON_OPTIONS = listOf(
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15",
            "-o", "ServerAliveInterval=15",
            "-o", "ControlPath=none",
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
                "Permission denied" in err -> "Login rejected: wrong user, password or key."
                "Connection refused" in err -> "Nothing is listening for SSH on that port."
                "Could not resolve hostname" in err -> "The host name does not resolve."
                "timed out" in err || "No route to host" in err || "Network is unreachable" in err ->
                    "The server did not answer (wrong address, or a firewall in front of it)."
                else -> err.trim().lines().lastOrNull { it.isNotBlank() }?.take(300) ?: "SSH failed (exit 255)."
            }
        }

        const val HOST_KEY_CHANGED =
            "The server's host key differs from the one recorded for this address. If the server was rebuilt, forget the old key and retry."
    }
}
