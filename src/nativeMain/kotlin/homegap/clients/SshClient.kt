package homegap.clients

import homegap.config.NodeConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.provision.ClusterTemplates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

class SshException(message: String) : Exception(message)

/**
 * A thin wrapper over the OpenSSH client binary. It keeps one ControlMaster for each node, so
 * repeated commands and port-forwards reuse one authenticated connection.
 */
class SshSession(
    val node: NodeConfig,
    private val controlDir: String,
    private val knownHostsFile: String,
    private val sshBinary: String = "ssh",
) {
    private val mutex = Mutex()
    private val controlPath = "$controlDir/${node.id}.sock"
    private val forwards = mutableMapOf<Int, Int>()

    private fun baseArgs(): List<String> = listOf(
        sshBinary,
        "-i", node.sshKeyFile,
        "-p", node.sshPort.toString(),
        "-o", "BatchMode=yes",
        "-o", "IdentitiesOnly=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "UserKnownHostsFile=$knownHostsFile",
        "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=3",
        "-o", "ControlPath=$controlPath",
        "-o", "LogLevel=ERROR",
    )

    private val target: String get() = "${node.sshUser}@${node.host}"

    private suspend fun ssh(
        args: List<String>,
        stdin: String = "",
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): ProcessResult =
        Subprocess.run(baseArgs() + args, stdin, timeoutSeconds.seconds)

    /**
     * The last time that the master connection was up. For [MASTER_TRUST_MS] after it,
     * [ensureMaster] does not check the connection again.
     */
    private var masterVerifiedAt = 0L

    private fun markMasterSuspect() {
        masterVerifiedAt = 0L
    }

    private suspend fun masterIsUp(): Boolean =
        ssh(listOf("-O", "check", target), timeoutSeconds = CONTROL_TIMEOUT_SECONDS).ok

    suspend fun ensureMaster() = mutex.withLock {
        // Every pass runs several commands per node. One `ssh -O check` fork per window is enough.
        if (nowMillis() - masterVerifiedAt < MASTER_TRUST_MS) return@withLock
        Files.mkdirs(controlDir)
        Files.chmodPath(controlDir, "700".toInt(8))
        if (masterIsUp()) {
            masterVerifiedAt = nowMillis()
            return@withLock
        }
        forwards.clear()
        // A socket from a previous process (for example a container restart) blocks a new master.
        Files.delete(controlPath)
        // `ssh -f` returns after the login. The master that stays detaches from our pipes.
        val login = ssh(
            listOf("-M", "-f", "-N", "-o", "ControlPersist=15m", "-o", "ExitOnForwardFailure=yes", target),
            timeoutSeconds = LOGIN_TIMEOUT_SECONDS,
        )
        if (!login.ok || !masterIsUp()) {
            val detail = login.stderr.trim().takeLast(LOGIN_ERROR_LENGTH).takeIf { it.isNotEmpty() }
                ?: NO_MESSAGE_FROM_SSH
            throw SshException("ssh to ${node.id} (${node.host}) failed (exit ${login.exitCode}): $detail")
        }
        masterVerifiedAt = nowMillis()
    }

    /**
     * The command line of an interactive shell on the node that runs [command], for a local
     * terminal. It goes through the connection that is already open, so the node sees no new login.
     */
    suspend fun interactive(command: String): List<String> {
        ensureMaster()
        return baseArgs() + listOf("-tt", target, command)
    }

    /**
     * Runs a bash script on the node as root (through `sudo -n` unless the login user is root).
     * Scripts start with [ownerPreamble]: `hg_own` hands HomeGAP's files to the login user, and
     * Docker then runs as that user.
     */
    suspend fun runScript(script: String, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): ProcessResult {
        ensureMaster()
        val wrapped = "set -euo pipefail\n${ownerPreamble(node.sshUser)}\n$script\n"
        val shell = if (node.sshUser == "root") "bash -s" else "sudo -n bash -s"
        val result = ssh(listOf(target, shell), wrapped, timeoutSeconds)
        // This exit code is ssh's own failure (connection lost): re-check the master next time.
        if (result.exitCode == SSH_FAILURE_EXIT) markMasterSuspect()
        return result
    }

    suspend fun runScriptOrThrow(
        step: String,
        script: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): String {
        val result = runScript(script, timeoutSeconds)
        if (!result.ok) {
            val output = result.stderr.ifBlank { result.stdout }.trim().takeLast(SCRIPT_ERROR_LENGTH)
            throw SshException("[$step] exit ${result.exitCode}: $output")
        }
        return result.stdout
    }

    /**
     * Forwards a local loopback port to `127.0.0.1:[remotePort]` on the node through the master.
     * Returns the local port.
     */
    suspend fun forward(remotePort: Int, localBase: Int): Int {
        ensureMaster()
        return mutex.withLock {
            forwards[remotePort]?.let { return@withLock it }
            var local = localBase
            repeat(FORWARD_ATTEMPTS) {
                val forward = "127.0.0.1:$local:127.0.0.1:$remotePort"
                val result = ssh(
                    listOf("-O", "forward", "-L", forward, target),
                    timeoutSeconds = CONTROL_TIMEOUT_SECONDS,
                )
                if (result.ok) {
                    forwards[remotePort] = local
                    return@withLock local
                }
                if (result.exitCode == SSH_FAILURE_EXIT && "forward" !in result.stderr) markMasterSuspect()
                local++
            }
            throw SshException("could not open a port-forward to ${node.id}:$remotePort")
        }
    }

    suspend fun close() = mutex.withLock {
        ssh(listOf("-O", "exit", target), timeoutSeconds = EXIT_TIMEOUT_SECONDS)
        forwards.clear()
        masterVerifiedAt = 0L
    }

    companion object {
        const val MASTER_TRUST_MS = 30_000L
        const val DEFAULT_TIMEOUT_SECONDS = 900

        private const val CONTROL_TIMEOUT_SECONDS = 20
        private const val LOGIN_TIMEOUT_SECONDS = 60
        private const val EXIT_TIMEOUT_SECONDS = 10
        private const val FORWARD_ATTEMPTS = 50
        private const val SSH_FAILURE_EXIT = 255
        private const val LOGIN_ERROR_LENGTH = 500
        private const val SCRIPT_ERROR_LENGTH = 1500
        private const val ROOT_PREAMBLE = "hg_own() { :; }"
        private const val NO_MESSAGE_FROM_SSH =
            "the connection was closed or dropped before login, with no message from ssh " +
                "(a login blocker such as fail2ban may have banned this address)"

        /** Shell snippet that writes [content] to [path] atomically with [mode], only if it changed. */
        fun writeFileSnippet(
            path: String,
            content: String,
            mode: String = "0644",
            owner: String = "root:root",
        ): String {
            val encoded = Base64.encode(content.encodeToByteArray())
            val quoted = shQuote(path)
            return """
                |install -d -m 0755 "$(dirname $quoted)"
                |printf '%s' '$encoded' | base64 -d > $quoted.homegap-new
                |if ! cmp -s $quoted.homegap-new $quoted 2>/dev/null; then chmod $mode $quoted.homegap-new; chown $owner $quoted.homegap-new; mv -f $quoted.homegap-new $quoted; echo "CHANGED $path"; else rm -f $quoted.homegap-new; chmod $mode $quoted; fi
            """.trimMargin()
        }

        /**
         * Shell functions every node script starts with. For a login user other than root,
         * `hg_own TOP [SKIP...]` gives the root-owned files under TOP (except the SKIP trees) to
         * that user, and from then on `docker` runs as the user when it is in the docker group, so
         * Compose reads the `.env` files the user now owns. Root logins get a no-op `hg_own`.
         * Scripts look for the Docker binary with `type -P docker`, because `command -v` also
         * finds the function.
         */
        fun ownerPreamble(user: String): String = if (user == "root") ROOT_PREAMBLE else $$"""
            |HG_OWNER=$${shQuote(user)}
            |HG_GROUP=$(id -gn "$HG_OWNER" 2>/dev/null || echo "$HG_OWNER")
            |HG_HOME=$(getent passwd "$HG_OWNER" | cut -d: -f6 || true)
            |HG_AS_OWNER=
            |hg_own() {
            |  local top=$1 p; shift
            |  if [ -e "$top" ]; then
            |    local prune=()
            |    for p in "$@"; do prune+=(-path "$p" -prune -o); done
            |    find "$top" "${prune[@]}" -user 0 -exec chown -h "$HG_OWNER:$HG_GROUP" {} + || true
            |  fi
            |  if id -nG "$HG_OWNER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then HG_AS_OWNER=1; fi
            |}
            |docker() {
            |  if [ -n "$HG_AS_OWNER" ]; then runuser -u "$HG_OWNER" -- env HOME="${HG_HOME:-/}" docker "$@"
            |  else command docker "$@"; fi
            |}
        """.trimMargin()

        /** `hg_own` for the install dir [dir]. It leaves the data of the cluster member to its containers. */
        fun ownInstallDir(dir: String): String =
            (listOf(dir) + ClusterTemplates.DATA_DIRS.map { "$dir/cluster/$it" })
                .joinToString(" ", prefix = "hg_own ") { shQuote(it) }

        fun shQuote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"
    }
}

class SshPool(private val controlDir: String, private val knownHostsFile: String) {
    private val sessions = mutableMapOf<String, SshSession>()
    private val lock = Mutex()

    suspend fun session(node: NodeConfig): SshSession = lock.withLock {
        val existing = sessions[node.id]
        if (existing != null && existing.node.sameConnection(node)) return@withLock existing
        existing?.close()
        SshSession(node, controlDir, knownHostsFile).also { sessions[node.id] = it }
    }

    private fun NodeConfig.sameConnection(other: NodeConfig) =
        host == other.host && sshUser == other.sshUser && sshPort == other.sshPort && sshKeyFile == other.sshKeyFile

    suspend fun drop(nodeId: String) = lock.withLock { sessions.remove(nodeId)?.close() }
}
