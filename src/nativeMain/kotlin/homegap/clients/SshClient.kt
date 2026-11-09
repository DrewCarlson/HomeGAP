package homegap.clients

import homegap.config.NodeConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

class SshException(message: String) : Exception(message)

/**
 * Thin wrapper over the OpenSSH client binary using a persistent ControlMaster per node, so
 * repeated commands and port-forwards reuse one authenticated connection.
 */
class SshSession(
    val node: NodeConfig,
    private val controlDir: String,
    private val knownHostsFile: String,
    private val sshBinary: String = "ssh",
) {
    private val logger = KotlinLogging.logger {}
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

    private suspend fun io(argv: List<String>, stdin: String = "", timeout: Int = 900): ProcessResult =
        Subprocess.run(argv, stdin, timeout.seconds)

    /** When the master connection was last known to be up; checks within [MASTER_TRUST] are skipped. */
    private var masterVerifiedAt = 0L

    private fun markMasterSuspect() { masterVerifiedAt = 0L }

    suspend fun ensureMaster() = mutex.withLock {
        // Every pass runs several commands per node; one `ssh -O check` fork per window is enough.
        if (nowMillis() - masterVerifiedAt < MASTER_TRUST_MS) return@withLock
        Files.mkdirs(controlDir)
        Files.chmodPath(controlDir, "700".toInt(8))
        if (io(baseArgs() + listOf("-O", "check", target), timeout = 20).ok) {
            masterVerifiedAt = nowMillis()
            return@withLock
        }
        forwards.clear()
        // A socket left behind by a previous process (e.g. container restart) blocks a new master.
        Files.delete(controlPath)
        // `ssh -f` backgrounds the master while keeping inherited fds, so it must not share our
        // capture pipes: its output goes to a log file and we wait for the foreground ssh to exit.
        val log = "$controlDir/${node.id}.log"
        Files.writeAtomic(log, "", "600".toInt(8))
        val child = Subprocess.start(
            baseArgs() + listOf("-M", "-f", "-N", "-o", "ControlPersist=15m", "-o", "ExitOnForwardFailure=yes", target),
            log,
        )
        // Bounded: a master that never finishes logging in must not hold the pass.
        val code = withContext(Dispatchers.IO) { child.process.waitFor(60.seconds) }
        if (code == null) {
            child.kill()
            child.awaitExit()
            throw SshException("ssh to ${node.id} (${node.host}) timed out")
        }
        if (code != 0 || !io(baseArgs() + listOf("-O", "check", target), timeout = 20).ok) {
            val detail = Files.readTextOrNull(log)?.trim()?.takeLast(500)?.takeIf { it.isNotEmpty() }
                ?: "the connection was closed or dropped before login, with no message from ssh (a login blocker such as fail2ban may have banned this address)"
            throw SshException("ssh to ${node.id} (${node.host}) failed (exit $code): $detail")
        }
        masterVerifiedAt = nowMillis()
    }

    /**
     * Runs a bash script on the node as root (via `sudo -n` unless already root). Scripts start
     * with [ownerPreamble]: `hg_own` hands HomeGAP's files to the login user, and Docker then runs
     * as that user.
     */
    suspend fun runScript(script: String, timeoutSeconds: Int = 900): ProcessResult {
        ensureMaster()
        val wrapped = "set -euo pipefail\n${ownerPreamble(node.sshUser)}\n$script\n"
        val cmd = if (node.sshUser == "root") "bash -s" else "sudo -n bash -s"
        val r = io(baseArgs() + listOf(target, cmd), wrapped, timeoutSeconds)
        // 255 is ssh's own failure (connection lost): re-check the master next time.
        if (r.exitCode == 255) markMasterSuspect()
        return r
    }

    suspend fun runScriptOrThrow(step: String, script: String, timeoutSeconds: Int = 900): String {
        val r = runScript(script, timeoutSeconds)
        if (!r.ok) throw SshException("[$step] exit ${r.exitCode}: ${(r.stderr.ifBlank { r.stdout }).trim().takeLast(1500)}")
        return r.stdout
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
            repeat(50) {
                val r = io(baseArgs() + listOf("-O", "forward", "-L", "127.0.0.1:$local:127.0.0.1:$remotePort", target), timeout = 20)
                if (r.ok) {
                    forwards[remotePort] = local
                    return@withLock local
                }
                if (r.exitCode == 255 && "forward" !in r.stderr) markMasterSuspect()
                local++
            }
            throw SshException("could not open a port-forward to ${node.id}:$remotePort")
        }
    }

    suspend fun close() = mutex.withLock {
        io(baseArgs() + listOf("-O", "exit", target), timeout = 10)
        forwards.clear()
        masterVerifiedAt = 0L
    }

    companion object {
        /** Shell snippet that writes [content] to [path] atomically with [mode], only if it changed. */
        fun writeFileSnippet(path: String, content: String, mode: String = "0644", owner: String = "root:root"): String {
            val b64 = Base64.encode(content.encodeToByteArray())
            val q = shQuote(path)
            return """
                |install -d -m 0755 "$(dirname $q)"
                |printf '%s' '$b64' | base64 -d > $q.homegap-new
                |if ! cmp -s $q.homegap-new $q 2>/dev/null; then chmod $mode $q.homegap-new; chown $owner $q.homegap-new; mv -f $q.homegap-new $q; echo "CHANGED $path"; else rm -f $q.homegap-new; chmod $mode $q; fi
            """.trimMargin()
        }

        const val MASTER_TRUST_MS = 30_000L

        /**
         * Shell functions every node script starts with. For a login user other than root,
         * `hg_own TOP [SKIP...]` gives the root-owned files under TOP (except the SKIP trees) to
         * that user, and from then on `docker` runs as the user when it is in the docker group, so
         * Compose reads the `.env` files the user now owns. Root logins get a no-op `hg_own`.
         * Scripts look for the Docker binary with `type -P docker`, since `command -v` would also
         * find the function.
         */
        fun ownerPreamble(user: String): String = if (user == "root") "hg_own() { :; }" else $$"""
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

        /** `hg_own` for the install dir [dir], leaving the cluster member's data to its containers. */
        fun ownInstallDir(dir: String): String =
            (listOf(dir) + homegap.provision.ClusterTemplates.DATA_DIRS.map { "$dir/cluster/$it" })
                .joinToString(" ", prefix = "hg_own ") { shQuote(it) }

        fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
    }
}

class SshPool(private val controlDir: String, private val knownHostsFile: String) {
    private val sessions = mutableMapOf<String, SshSession>()
    private val lock = Mutex()

    suspend fun session(node: NodeConfig): SshSession = lock.withLock {
        val existing = sessions[node.id]
        if (existing != null && existing.node == node) return@withLock existing
        existing?.close()
        SshSession(node, controlDir, knownHostsFile).also { sessions[node.id] = it }
    }

    suspend fun drop(nodeId: String) = lock.withLock { sessions.remove(nodeId)?.close() }
}
