package homegap.node

import homegap.mesh.LivenessResponder
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.runCatchingCancellable
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The exit of a node: a device of the VPN that selects the node sends all its traffic to this
 * WireGuard interface, and the traffic leaves for the internet from the node.
 *
 * The agent keeps the interface, its key in [stateDir] and the firewall of the exit. The mesh
 * agent of the homelab sends the full list of devices over the mesh, and the agent admits each
 * key at the address of its device. The list is in memory only: after a start of the agent, the
 * homelab sends it again.
 */
class NodeExitService(private val stateDir: String, private val scope: CoroutineScope) {
    private val logger = KotlinLogging.logger("exit")
    private val keyFile = "$stateDir/${Mesh.EXIT_INTERFACE}.key"
    private val confFile = "$stateDir/${Mesh.EXIT_INTERFACE}.conf"
    private val mutex = Mutex()
    private var current: Mesh.NodeExit? = null
    private var peers: List<Mesh.VpnPeer> = emptyList()
    private var server: EmbeddedServer<*, *>? = null
    private var livenessPort: Int? = null
    private var reported: String? = null

    /** Brings the exit to [exit]. With null, the node has no exit. */
    suspend fun apply(exit: Mesh.NodeExit?): Unit = mutex.withLock {
        val result = runCatchingCancellable {
            if (exit == null) remove() else bring(exit)
        }
        val problem = result.exceptionOrNull()?.message
        if (problem != null && problem != reported) logger.error { "the exit of the node is not ready: $problem" }
        if (problem == null && (reported != null || exit != current)) {
            val ready = exit?.let { "exit ready at ${it.address}, UDP port ${it.port}" }
            logger.info { ready ?: "no exit on this node" }
        }
        reported = problem
        current = exit.takeIf { problem == null }
    }

    /** Admits the devices in [text] ([Mesh.ExitPeers]). Returns the status for the homelab, or null with no exit. */
    private suspend fun admit(text: String): Mesh.ExitStatus? = mutex.withLock {
        val exit = current ?: return null
        val wanted = JSON.decodeFromString<Mesh.ExitPeers>(text).peers
            .filter { WIREGUARD_KEY.matches(it.publicKey) && ADDRESS.matches(it.address) && it.address != exit.address }
            .distinctBy { it.address }
            .distinctBy { it.publicKey }
        if (wanted != peers) {
            sync(exit, wanted)
            peers = wanted
        }
        status(exit)
    }

    private suspend fun bring(exit: Mesh.NodeExit) {
        if (Files.readTextOrNull(keyFile).isNullOrBlank()) {
            Files.mkdirs(stateDir)
            Files.writeAtomic(keyFile, wg("genkey").stdout.trim() + "\n", OWNER_ONLY)
        }
        firewall(exit)
        if (!run("ip", "link", "show", Mesh.EXIT_INTERFACE).ok) {
            val added = run("ip", "link", "add", Mesh.EXIT_INTERFACE, "type", "wireguard")
            check(added.ok) { added.stderr.trim().ifEmpty { "could not make the interface" } }
        }
        sync(exit, peers)
        val address = "${exit.address}/${exit.subnet.substringAfter('/')}"
        if ("inet $address " !in run("ip", "-4", "addr", "show", "dev", Mesh.EXIT_INTERFACE).stdout) {
            run("ip", "-4", "addr", "flush", "dev", Mesh.EXIT_INTERFACE)
            check(run("ip", "addr", "add", address, "dev", Mesh.EXIT_INTERFACE).ok) { "could not set the address" }
        }
        check(run("ip", "link", "set", Mesh.EXIT_INTERFACE, "up").ok) { "could not bring the interface up" }
        serveLiveness(exit.port + 1)
        listen(exit.listen)
    }

    private suspend fun remove() {
        server?.stop()
        server = null
        if (run("ip", "link", "show", Mesh.EXIT_INTERFACE).ok) run("ip", "link", "del", Mesh.EXIT_INTERFACE)
        for ((table, chain, target) in Mesh.EXIT_JUMPS) {
            run("iptables", "-t", table, "-D", chain, "-j", target)
            run("iptables", "-t", table, "-F", target)
        }
        peers = emptyList()
    }

    private suspend fun sync(exit: Mesh.NodeExit, wanted: List<Mesh.VpnPeer>) {
        val key = Files.readTextOrNull(keyFile)?.trim().orEmpty()
        Files.writeAtomic(confFile, Mesh.exitConfig(exit, key, wanted), OWNER_ONLY)
        wg("syncconf", Mesh.EXIT_INTERFACE, confFile)
    }

    private suspend fun status(exit: Mesh.NodeExit): Mesh.ExitStatus {
        val key = Files.readTextOrNull(keyFile)?.trim().orEmpty()
        return Mesh.ExitStatus(
            publicKey = wg("pubkey", stdin = key + "\n").stdout.trim(),
            address = exit.address,
            port = exit.port,
            peers = peers.map { it.publicKey },
        )
    }

    /** The rules go in first, and the interface comes up behind them. */
    private suspend fun firewall(exit: Mesh.NodeExit) {
        val restored = run("iptables-restore", "--noflush", stdin = Mesh.exitFirewall(exit))
        check(restored.ok) { "could not set the firewall of the exit: ${restored.stderr.trim()}" }
        for ((table, chain, target) in Mesh.EXIT_JUMPS) {
            if (run("iptables", "-t", table, "-C", chain, "-j", target).ok) continue
            val added = run("iptables", "-t", table, "-I", chain, "1", "-j", target)
            check(added.ok) { "could not set the firewall of the exit ($chain): ${added.stderr.trim()}" }
        }
    }

    /** The call of the homelab, on the mesh address of the node only. The firewall accepts it only from the homelab. */
    private fun listen(address: String) {
        if (server != null) return
        val started = embeddedServer(
            CIO,
            configure = {
                reuseAddress = true
                connector {
                    host = address.substringBeforeLast(':')
                    port = address.substringAfterLast(':').toInt()
                }
            },
        ) {
            routing {
                put(Mesh.EXIT_PEERS_PATH) {
                    val text = call.receiveText().take(MAX_BODY)
                    val status = runCatchingCancellable { admit(text) }.getOrElse { e ->
                        logger.error { "could not admit the devices: ${e.message}" }
                        return@put call.respondText("", status = HttpStatusCode.InternalServerError)
                    }
                    when (status) {
                        null -> call.respondText("", status = HttpStatusCode.ServiceUnavailable)
                        else -> call.respondText(JSON.encodeToString(status), ContentType.Application.Json)
                    }
                }
            }
        }
        started.start(wait = false)
        server = started
    }

    private fun serveLiveness(port: Int) {
        if (livenessPort != null) return
        livenessPort = port
        scope.launch(Dispatchers.IO) {
            val failure = runCatchingCancellable { LivenessResponder.serve(port) }.exceptionOrNull()
            logger.error { "the liveness probe of the exit has no answer: ${failure?.message}" }
            livenessPort = null
        }
    }

    private suspend fun wg(vararg args: String, stdin: String = ""): ProcessResult {
        val result = run("wg", *args, stdin = stdin)
        check(result.ok) { "wg ${args.first()}: ${result.stderr.trim().ifEmpty { "exit ${result.exitCode}" }}" }
        return result
    }

    private suspend fun run(vararg argv: String, stdin: String = ""): ProcessResult =
        runCatchingCancellable { Subprocess.run(argv.toList(), stdin, COMMAND_TIMEOUT) }.getOrElse { e ->
            if (e !is IllegalStateException) throw e
            ProcessResult(NOT_FOUND_EXIT, "", e.message ?: "not found")
        }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val COMMAND_TIMEOUT = 15.seconds
        const val NOT_FOUND_EXIT = 127
        const val MAX_BODY = 4 * 1024 * 1024
        val OWNER_ONLY = "600".toInt(8)
        val WIREGUARD_KEY = Regex("^[A-Za-z0-9+/]{43}=$")
        val ADDRESS = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}
