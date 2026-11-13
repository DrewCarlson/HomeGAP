package homegap.mesh

import homegap.config.MeshConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * `homegap mesh`: the homelab's end of the cluster mesh. It runs in the network namespace the
 * homelab's cluster member lives in (the `homegap-mesh` container), with `NET_ADMIN`, and is the
 * only thing there that can change the network.
 *
 * It keeps the `hg-mesh` interface as the orchestrator describes it in [Mesh.SPEC_FILE], and a
 * firewall that leaves that namespace three ways to talk: the mesh itself, WireGuard to the nodes,
 * and answers to the orchestrator. Nothing in the namespace can open a connection to the
 * orchestrator, to other containers or to the home network. Every node is a member of the cluster
 * database, so HomeGAP treats the containers next to it as reachable from the nodes.
 *
 * The agent creates the private key once in [dir] (mode 0600), and the key never leaves it.
 */
class MeshAgent(private val dir: String) {
    private val logger = KotlinLogging.logger("mesh")
    private val keyFile = "$dir/${Mesh.INTERFACE}.key"
    private val confFile = "$dir/${Mesh.INTERFACE}.conf"
    private val specFile = "$dir/${Mesh.SPEC_FILE}"
    private val statusFile = "$dir/${Mesh.STATUS_FILE}"

    suspend fun run() {
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        logger.info { "mesh agent started; waiting for the orchestrator's mesh in $specFile" }
        var applied: String? = null
        var appliedAt = 0L
        var error: String? = null
        var reported: String? = null
        while (true) {
            val key = runCatchingCancellable { publicKey() }
            val text = Files.readTextOrNull(specFile)
            val spec = text?.let { runCatching { json.decodeFromString<Mesh.AgentSpec>(it) }.getOrNull() }
            if (text != null && spec == null) {
                error = "the mesh description $specFile is not readable"
            }
            if (spec != null && key.isSuccess) {
                val hash = Mesh.specHash(text)
                // At once after a change. If not, once a minute, to put back anything that drifted.
                if (hash != applied || error != null || nowMillis() - appliedAt >= REAPPLY_MS) {
                    val result = runCatchingCancellable { apply(spec) }
                    error = result.exceptionOrNull()?.message
                    if (result.isSuccess && hash != applied) {
                        logger.info { "mesh applied: ${spec.address} with ${spec.peers.size} peer(s)" }
                    }
                    if (result.isSuccess) {
                        applied = hash
                    }
                    appliedAt = nowMillis()
                }
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
            runCatchingCancellable { Files.writeAtomic(statusFile, json.encodeToString(status), OWNER_ONLY) }
            delay(INTERVAL)
        }
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
    private suspend fun apply(spec: Mesh.AgentSpec) {
        val subnet = Mesh.parseSubnet(spec.subnet)
        firewall(spec)
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
    }

    /**
     * Loads [Mesh.firewallRules] into HomeGAP's own chains in one step. It does not touch other
     * chains, such as Docker's for its DNS. Then it makes sure that the built-in chains pass
     * through HomeGAP's chains.
     */
    private suspend fun firewall(spec: Mesh.AgentSpec) {
        val restored = run("iptables-restore", "--noflush", stdin = Mesh.firewallRules(spec))
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
        val INTERVAL = 3.seconds
        val COMMAND_TIMEOUT = 15.seconds
        const val REAPPLY_MS = 60_000L

        /** The exit code of a shell for a command that it does not find. */
        const val NOT_FOUND_EXIT = 127

        /** The mode of the key, config and status files: only the owner can read and write them. */
        val OWNER_ONLY = "600".toInt(8)
        val json = Json { ignoreUnknownKeys = true }
    }
}
