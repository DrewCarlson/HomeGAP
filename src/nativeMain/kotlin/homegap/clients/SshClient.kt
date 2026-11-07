package homegap.clients

import homegap.config.NodeConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Processes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

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

    private suspend fun io(argv: List<String>, stdin: ByteArray? = null, timeout: Int = 900): ProcessResult =
        withContext(Dispatchers.IO) { Processes.run(argv, stdin, timeoutSeconds = timeout) }

    suspend fun ensureMaster() = mutex.withLock {
        Files.mkdirs(controlDir)
        Files.chmodPath(controlDir, "700".toInt(8))
        if (io(baseArgs() + listOf("-O", "check", target), timeout = 20).ok) return@withLock
        forwards.clear()
        // A socket left behind by a previous process (e.g. container restart) blocks a new master.
        Files.delete(controlPath)
        // `ssh -f` backgrounds the master while keeping inherited fds, so it must not share our
        // capture pipes: spawn it detached with output to a log file and wait for the parent to exit.
        val log = "$controlDir/${node.id}.log"
        Files.writeAtomic(log, "", "600".toInt(8))
        val pid = withContext(Dispatchers.IO) {
            Processes.spawnDetached(
                baseArgs() + listOf("-M", "-f", "-N", "-o", "ControlPersist=15m", "-o", "ExitOnForwardFailure=yes", target),
                emptyMap(), log,
            )
        }
        var exit: String? = null
        for (i in 0 until 600) {
            exit = Processes.reap(pid)
            if (exit != null) break
            kotlinx.coroutines.delay(100)
        }
        if (exit == null) {
            Processes.forceKill(pid)
            throw SshException("ssh to ${node.id} (${node.host}) timed out")
        }
        if (exit != "exit 0" || !io(baseArgs() + listOf("-O", "check", target), timeout = 20).ok) {
            val detail = Files.readTextOrNull(log)?.trim()?.takeLast(500).orEmpty()
            throw SshException("ssh to ${node.id} (${node.host}) failed ($exit): $detail")
        }
    }

    /** Runs a bash script on the node as root (via `sudo -n` unless already root). */
    suspend fun runScript(script: String, timeoutSeconds: Int = 900): ProcessResult {
        ensureMaster()
        val wrapped = "set -euo pipefail\n$script\n"
        val cmd = if (node.sshUser == "root") "bash -s" else "sudo -n bash -s"
        return io(baseArgs() + listOf(target, cmd), wrapped.encodeToByteArray(), timeoutSeconds)
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
                local++
            }
            throw SshException("could not open a port-forward to ${node.id}:$remotePort")
        }
    }

    suspend fun close() = mutex.withLock {
        io(baseArgs() + listOf("-O", "exit", target), timeout = 10)
        forwards.clear()
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
