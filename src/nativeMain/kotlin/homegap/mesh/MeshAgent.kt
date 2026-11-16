package homegap.mesh

import homegap.config.MeshConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.platform.watchDirectory
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * `homegap mesh`: the homelab's end of the mesh. It runs in the network namespace of the mesh
 * (the `homegap-mesh` container), with `NET_ADMIN`, and is the
 * only thing there that can change the network.
 *
 * It keeps the `hg-mesh` interface as the orchestrator describes it in [Mesh.SPEC_FILE], and a
 * firewall that leaves that namespace three ways to talk: the mesh itself, WireGuard to the nodes,
 * and answers to the orchestrator. Nothing in the namespace can open a connection to the
 * orchestrator, to other containers or to the home network. Every node is a member of the mesh
 * database, so HomeGAP treats the containers next to it as reachable from the nodes.
 *
 * The agent creates the private key once in [dir] (mode 0600), and the key never leaves it.
 */
class MeshAgent(private val dir: String, private val vpnDir: String) {
    private val logger = KotlinLogging.logger("mesh")
    private val keyFile = "$dir/${Mesh.INTERFACE}.key"
    private val confFile = "$dir/${Mesh.INTERFACE}.conf"
    private val specFile = "$dir/${Mesh.SPEC_FILE}"
    private val statusFile = "$dir/${Mesh.STATUS_FILE}"
    private val vpnKeyFile = "$dir/${Mesh.VPN_INTERFACE}.key"
    private val vpnConfFile = "$dir/${Mesh.VPN_INTERFACE}.conf"
    private val vpnPeersFile = "$vpnDir/${Mesh.VPN_PEERS_FILE}"
    private val vpnStatusFile = "$vpnDir/${Mesh.VPN_STATUS_FILE}"
    private var livenessPort: Int? = null
    private var vpnStatus: Mesh.VpnStatus? = null
    private var vpnStatusText: String? = null
    private val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = PUSH_TIMEOUT_MS }
        expectSuccess = false
    }

    suspend fun run(): Unit = coroutineScope {
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        logger.info { "mesh agent started; waiting for the orchestrator's mesh in $specFile" }
        writeHostGateway()
        var applied: String? = null
        var appliedAll: String? = null
        var appliedAt = 0L
        var error: String? = null
        var reported: String? = null
        // A change of the mesh description starts the loop at once. With no change, the loop runs
        // at an interval, to report the peers and to put back anything that drifted.
        val changes = Channel<Unit>(Channel.CONFLATED)
        launch { watchDirectory(dir) { it == Mesh.SPEC_FILE }.collect { changes.trySend(Unit) } }
        // The sign-in server writes the devices of the VPN that are connected.
        launch { watchDirectory(vpnDir) { it in VPN_INPUT_FILES }.collect { changes.trySend(Unit) } }
        while (true) {
            val key = runCatchingCancellable { publicKey() }
            val text = Files.readTextOrNull(specFile)
            val spec = text?.let { runCatching { json.decodeFromString<Mesh.AgentSpec>(it) }.getOrNull() }
            if (text != null && spec == null) {
                error = "the mesh description $specFile is not readable"
            }
            if (spec != null && key.isSuccess) {
                val peersText = Files.readTextOrNull(vpnPeersFile).orEmpty()
                val vpnPeers = vpnPeers(spec, peersText)
                val hash = Mesh.specHash(text + peersText)
                // At once after a change. If not, once a minute, to put back anything that drifted.
                if (hash != appliedAll || error != null || nowMillis() - appliedAt >= REAPPLY_MS) {
                    val result = runCatchingCancellable { apply(spec, vpnPeers) }
                    if (result.isSuccess) serveLiveness(spec.vpn)
                    error = result.exceptionOrNull()?.message
                    if (result.isSuccess && Mesh.specHash(text) != applied) {
                        logger.info { "mesh applied: ${spec.address} with ${spec.peers.size} peer(s)" }
                    }
                    if (result.isSuccess) {
                        applied = Mesh.specHash(text)
                        appliedAll = hash
                    }
                    appliedAt = nowMillis()
                }
                if (error == null) reportVpn(pushExits(spec, vpnPeers))
            }
            val problem = key.exceptionOrNull()?.message ?: error
            if (problem != null && problem != reported) {
                logger.error { problem }
            }
            reported = problem
            val status = Mesh.AgentStatus(
                publicKey = key.getOrNull(),
                applied = applied,
                peers = if (applied != null) peers() else null,
                error = problem,
                at = nowMillis(),
            )
            // The orchestrator is a different user, and the status holds no secret.
            runCatchingCancellable { Files.writeAtomic(statusFile, json.encodeToString(status), READABLE) }
            withTimeoutOrNull(INTERVAL) { changes.receive() }
        }
    }

    /** Tells the orchestrator how this namespace reaches the homelab host, for a service on the host itself. */
    private fun writeHostGateway() {
        val gateway = Files.readTextOrNull(HostGateway.ROUTE_TABLE)?.let(HostGateway::parse)
        if (gateway == null) {
            logger.warn { "no default route: a service on the homelab host has no address" }
            return
        }
        runCatching { Files.writeAtomic("$dir/${Mesh.HOST_GATEWAY_FILE}", gateway, READABLE) }
    }

    /** The public key of the homelab. The first call creates the key pair. */
    private suspend fun publicKey(): String {
        if (Files.readTextOrNull(keyFile).isNullOrBlank()) {
            val key = wg("genkey").stdout.trim()
            Files.writeAtomic(keyFile, key + "\n", OWNER_ONLY)
        }
        return wg("pubkey", stdin = privateKey() + "\n").stdout.trim()
    }

    /**
     * Brings the interface to [spec]. Peer changes apply in place. The firewall goes in first,
     * and the interface only comes up behind it: a failure leaves the mesh down, never open.
     */
    private suspend fun apply(spec: Mesh.AgentSpec, vpnPeers: List<Mesh.VpnPeer>) {
        val subnet = Mesh.parseSubnet(spec.subnet)
        firewall(spec, vpnPeers)
        if (!run("ip", "link", "show", Mesh.INTERFACE).ok) {
            val added = run("ip", "link", "add", Mesh.INTERFACE, "type", "wireguard")
            check(added.ok) { explain(added) }
        }
        val mesh = MeshConfig(spec.subnet, spec.port)
        Files.writeAtomic(confFile, Mesh.homelabConfig(mesh, privateKey(), spec.peers), OWNER_ONLY)
        wg("syncconf", Mesh.INTERFACE, confFile)
        val addresses = run("ip", "-4", "addr", "show", "dev", Mesh.INTERFACE).stdout
        val address = "${spec.address}/${subnet.prefix}"
        if ("inet $address " !in addresses) {
            run("ip", "-4", "addr", "flush", "dev", Mesh.INTERFACE)
            check(run("ip", "addr", "add", address, "dev", Mesh.INTERFACE).ok) {
                "could not set the mesh address ${spec.address}"
            }
        }
        check(run("ip", "link", "set", Mesh.INTERFACE, "up").ok) { "could not bring ${Mesh.INTERFACE} up" }
        applyVpn(spec.vpn, vpnPeers)
    }

    /**
     * The devices in [text] (the file of the sign-in server) that the VPN can admit: a real key,
     * and an address of a device in the subnet of the VPN. Each other entry is left out.
     */
    private fun vpnPeers(spec: Mesh.AgentSpec, text: String): List<Mesh.VpnPeer> {
        val vpn = spec.vpn ?: return emptyList()
        val peers = runCatching { json.decodeFromString<List<Mesh.VpnPeer>>(text) }.getOrDefault(emptyList())
        val network = vpn.subnet.substringBefore('/').split('.')
        val fixed = vpn.subnet.substringAfter('/').toInt() / BYTE_BITS
        return peers
            .filter { WIREGUARD_KEY.matches(it.publicKey) && ADDRESS.matches(it.address) && it.address != vpn.address }
            .filter { it.address.split('.').take(fixed) == network.take(fixed) }
            .distinctBy { it.address }
            .distinctBy { it.publicKey }
    }

    /** Brings the interface of the VPN to [vpn] and [peers]. With null, the namespace has no such interface. */
    private suspend fun applyVpn(vpn: Mesh.Vpn?, peers: List<Mesh.VpnPeer>) {
        if (vpn == null) {
            if (run("ip", "link", "show", Mesh.VPN_INTERFACE).ok) run("ip", "link", "del", Mesh.VPN_INTERFACE)
            vpnStatus = null
            return
        }
        if (Files.readTextOrNull(vpnKeyFile).isNullOrBlank()) {
            Files.writeAtomic(vpnKeyFile, wg("genkey").stdout.trim() + "\n", OWNER_ONLY)
        }
        val privateKey = Files.readTextOrNull(vpnKeyFile)?.trim().orEmpty()
        if (!run("ip", "link", "show", Mesh.VPN_INTERFACE).ok) {
            val added = run("ip", "link", "add", Mesh.VPN_INTERFACE, "type", "wireguard")
            check(added.ok) { explain(added) }
        }
        Files.writeAtomic(vpnConfFile, Mesh.vpnConfig(vpn, privateKey, peers), OWNER_ONLY)
        wg("syncconf", Mesh.VPN_INTERFACE, vpnConfFile)
        val address = "${vpn.address}/${vpn.subnet.substringAfter('/')}"
        if ("inet $address " !in run("ip", "-4", "addr", "show", "dev", Mesh.VPN_INTERFACE).stdout) {
            run("ip", "-4", "addr", "flush", "dev", Mesh.VPN_INTERFACE)
            val added = run("ip", "addr", "add", address, "dev", Mesh.VPN_INTERFACE)
            check(added.ok) { "could not set the address of the VPN" }
        }
        check(run("ip", "link", "set", Mesh.VPN_INTERFACE, "up").ok) { "could not bring ${Mesh.VPN_INTERFACE} up" }
        vpnStatus = Mesh.VpnStatus(
            publicKey = wg("pubkey", stdin = privateKey + "\n").stdout.trim(),
            port = vpn.port,
            address = vpn.address,
            peers = peers.map { it.publicKey },
        )
    }

    /**
     * Sends each node the devices that can use its exit, over the mesh, and returns the exits
     * that answered. The agent of a node keeps the list in memory, so each round sends it again.
     * A node that does not answer in time is not in the result, and the devices get no site for it.
     */
    private suspend fun pushExits(spec: Mesh.AgentSpec, peers: List<Mesh.VpnPeer>): List<Mesh.ExitStatus> {
        val roles = spec.vpn?.nodeExitRoles ?: return emptyList()
        val state = Mesh.NodeState(
            peers = peers.filter { Mesh.permits(roles, it.roles) },
            directory = Files.readTextOrNull("$vpnDir/${Mesh.VPN_DIRECTORY_FILE}").orEmpty(),
            devices = Files.readTextOrNull("$vpnDir/${Mesh.VPN_DEVICES_FILE}").orEmpty(),
        )
        val body = json.encodeToString(state)
        return coroutineScope {
            spec.peers.filter { it.endpoint != null }.map { node ->
                async {
                    runCatchingCancellable {
                        @Suppress("HttpUrlsUsage") // WireGuard of the mesh encrypts the call.
                        val response = http.put("http://${node.address}:${Mesh.AGENT_PORT}${Mesh.NODE_STATE_PATH}") {
                            setBody(body)
                        }
                        check(response.status.isSuccess()) { "status ${response.status.value}" }
                        json.decodeFromString<Mesh.ExitStatus>(response.bodyAsText())
                            .copy(node = node.id, host = Mesh.endpointHost(node.endpoint!!))
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Tells the sign-in server what the VPN has now: the peer of the homelab and the [exits] of the nodes. */
    private suspend fun reportVpn(exits: List<Mesh.ExitStatus>) {
        val status = vpnStatus ?: return
        val text = json.encodeToString(status.copy(exits = exits.sortedBy { it.node }))
        if (text == vpnStatusText) return
        runCatchingCancellable { Files.writeAtomic(vpnStatusFile, text, READABLE) }
            .onSuccess { vpnStatusText = text }
    }

    /** Starts the answers to the liveness probe of the apps, on the port after the port of the VPN. */
    private fun CoroutineScope.serveLiveness(vpn: Mesh.Vpn?) {
        val port = vpn?.port?.plus(1) ?: return
        if (livenessPort == port) return
        if (livenessPort != null) {
            logger.error { "the port of the VPN changed: start the mesh agent again" }
            return
        }
        livenessPort = port
        launch(Dispatchers.IO) {
            val failure = runCatchingCancellable { LivenessResponder.serve(port) }.exceptionOrNull()
            logger.error { "the liveness probe of the VPN has no answer: ${failure?.message}" }
            livenessPort = null
        }
    }

    /**
     * Loads [Mesh.firewallRules] into HomeGAP's own chains in one step. It does not touch other
     * chains, such as Docker's for its DNS. Then it makes sure that the built-in chains pass
     * through HomeGAP's chains.
     */
    private suspend fun firewall(spec: Mesh.AgentSpec, vpnPeers: List<Mesh.VpnPeer>) {
        val restored = run("iptables-restore", "--noflush", stdin = Mesh.firewallRules(spec, vpnPeers))
        check(restored.ok) {
            val detail = restored.stderr.trim().ifEmpty { "iptables-restore exit ${restored.exitCode}" }
            "could not set the mesh firewall: $detail"
        }
        for ((table, chain, target) in Mesh.FIREWALL_JUMPS) {
            if (run("iptables", "-t", table, "-C", chain, "-j", target).ok) continue
            val added = run("iptables", "-t", table, "-I", chain, "1", "-j", target)
            check(added.ok) { "could not set the mesh firewall ($chain -> $target): ${added.stderr.trim()}" }
        }
    }

    /** Current peers, or null when the interface does not exist. */
    private suspend fun peers(): List<Mesh.PeerStatus>? {
        val dump = run("wg", "show", Mesh.INTERFACE, "dump")
        return if (dump.ok) Mesh.parseDump(dump.stdout) else null
    }

    private fun privateKey(): String = Files.readTextOrNull(keyFile)?.trim().orEmpty()

    private suspend fun wg(vararg args: String, stdin: String = ""): ProcessResult {
        val result = run("wg", *args, stdin = stdin)
        check(result.ok) { "wg ${args.first()}: ${result.stderr.trim().ifEmpty { "exit ${result.exitCode}" }}" }
        return result
    }

    private suspend fun run(vararg argv: String, stdin: String = ""): ProcessResult =
        runCatchingCancellable { Subprocess.run(argv.toList(), stdin, COMMAND_TIMEOUT) }.getOrElse { e ->
            // Subprocess reports a missing binary this way (wireguard-tools or iproute2 not installed).
            if (e !is IllegalStateException) throw e
            ProcessResult(NOT_FOUND_EXIT, "", e.message ?: "not found")
        }

    private fun explain(result: ProcessResult): String {
        val stderr = result.stderr
        return when {
            "Operation not permitted" in stderr ->
                "the mesh agent needs NET_ADMIN to create the mesh interface (cap_add: [NET_ADMIN])"
            "Unknown device type" in stderr || "not supported" in stderr ->
                "this host's kernel has no WireGuard support"
            result.exitCode == NOT_FOUND_EXIT -> "the mesh needs Linux with iproute2 and wireguard-tools ($stderr)"
            else -> stderr.trim().ifEmpty { "ip link add failed (exit ${result.exitCode})" }
        }
    }

    private companion object {
        val INTERVAL = 15.seconds
        val COMMAND_TIMEOUT = 15.seconds
        const val REAPPLY_MS = 60_000L

        /** The exit code of a shell for a command that it does not find. */
        const val NOT_FOUND_EXIT = 127

        /** The mode of the key, config and status files: only the owner can read and write them. */
        val OWNER_ONLY = "600".toInt(8)
        val READABLE = "644".toInt(8)
        val json = Json { ignoreUnknownKeys = true }
        const val BYTE_BITS = 8
        const val PUSH_TIMEOUT_MS = 3000L

        /** The files of the sign-in server that the agent acts on. */
        val VPN_INPUT_FILES = setOf(Mesh.VPN_PEERS_FILE, Mesh.VPN_DIRECTORY_FILE, Mesh.VPN_DEVICES_FILE)
        val WIREGUARD_KEY = Regex("^[A-Za-z0-9+/]{43}=$")
        val ADDRESS = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}
