package homegap.mesh

import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.nowMillis
import homegap.provision.Mesh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * orchestrator, to other containers or to the home network: every node is a member of the cluster
 * database, so the containers next to it are treated as reachable from the nodes.
 *
 * The private key is created once in [dir] (mode 0600) and never leaves it.
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
            val key = attempt { publicKey() }
            val text = Files.readTextOrNull(specFile)
            val spec = text?.let { runCatching { json.decodeFromString<Mesh.AgentSpec>(it) }.getOrNull() }
            if (text != null && spec == null) error = "the mesh description $specFile is not readable"
            if (spec != null && key.isSuccess) {
                val hash = Mesh.specHash(text)
                // On a change at once; otherwise once a minute, to put back anything that drifted.
                if (hash != applied || error != null || nowMillis() - appliedAt >= REAPPLY_MS) {
                    val result = attempt { apply(spec) }
                    error = result.exceptionOrNull()?.message
                    if (result.isSuccess && hash != applied) logger.info { "mesh applied: ${spec.address} with ${spec.peers.size} peer(s)" }
                    if (result.isSuccess) applied = hash
                    appliedAt = nowMillis()
                }
            }
            val problem = key.exceptionOrNull()?.message ?: error
            if (problem != null && problem != reported) logger.error { problem }
            reported = problem
            val status = Mesh.AgentStatus(
                publicKey = key.getOrNull(),
                applied = applied,
                peers = if (applied != null) peers() else null,
                error = problem,
                at = nowMillis(),
            )
            attempt { Files.writeAtomic(statusFile, json.encodeToString(status), "600".toInt(8)) }
            delay(INTERVAL)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        Result.failure(e)
    }

    /** The homelab's public key, creating the key pair on first use. */
    private suspend fun publicKey(): String {
        if (Files.readTextOrNull(keyFile).isNullOrBlank()) {
            val key = wg("genkey").stdout.trim()
            Files.writeAtomic(keyFile, key + "\n", "600".toInt(8))
        }
        return wg("pubkey", stdin = privateKey() + "\n").stdout.trim()
    }

    /**
     * Brings the interface to [spec]; peer changes apply in place. The firewall goes in first,
     * and the interface only comes up behind it: a failure leaves the mesh down, never open.
     */
    private suspend fun apply(spec: Mesh.AgentSpec) {
        val subnet = Mesh.parseSubnet(spec.subnet)
        firewall(spec)
        if (!run("ip", "link", "show", Mesh.INTERFACE).ok) {
            val r = run("ip", "link", "add", Mesh.INTERFACE, "type", "wireguard")
            if (!r.ok) throw IllegalStateException(explain(r))
        }
        Files.writeAtomic(confFile, Mesh.homelabConfig(homegap.config.MeshConfig(spec.subnet, spec.port), privateKey(), spec.peers), "600".toInt(8))
        wg("syncconf", Mesh.INTERFACE, confFile)
        val addresses = run("ip", "-4", "addr", "show", "dev", Mesh.INTERFACE).stdout
        if ("inet ${spec.address}/${subnet.prefix} " !in addresses) {
            run("ip", "-4", "addr", "flush", "dev", Mesh.INTERFACE)
            check(run("ip", "addr", "add", "${spec.address}/${subnet.prefix}", "dev", Mesh.INTERFACE).ok) { "could not set the mesh address ${spec.address}" }
        }
        check(run("ip", "link", "set", Mesh.INTERFACE, "up").ok) { "could not bring ${Mesh.INTERFACE} up" }
    }

    /**
     * Loads [Mesh.firewallRules] into HomeGAP's own chains in one step (other chains, such as
     * Docker's for its DNS, are left alone), then makes sure the built-in chains pass through them.
     */
    private suspend fun firewall(spec: Mesh.AgentSpec) {
        val r = run("iptables-restore", "--noflush", stdin = Mesh.firewallRules(spec))
        check(r.ok) { "could not set the mesh firewall: ${r.stderr.trim().ifEmpty { "iptables-restore exit ${r.exitCode}" }}" }
        for ((table, chain, target) in Mesh.FIREWALL_JUMPS) {
            if (run("iptables", "-t", table, "-C", chain, "-j", target).ok) continue
            val added = run("iptables", "-t", table, "-I", chain, "1", "-j", target)
            check(added.ok) { "could not set the mesh firewall ($chain -> $target): ${added.stderr.trim()}" }
        }
    }

    /** Current peers, or null when the interface does not exist. */
    private suspend fun peers(): List<Mesh.PeerStatus>? {
        val r = run("wg", "show", Mesh.INTERFACE, "dump")
        return if (r.ok) Mesh.parseDump(r.stdout) else null
    }

    private fun privateKey(): String = Files.readTextOrNull(keyFile)?.trim().orEmpty()

    private suspend fun wg(vararg args: String, stdin: String = ""): ProcessResult {
        val r = run("wg", *args, stdin = stdin)
        if (!r.ok) throw IllegalStateException("wg ${args.first()}: ${r.stderr.trim().ifEmpty { "exit ${r.exitCode}" }}")
        return r
    }

    private suspend fun run(vararg argv: String, stdin: String = ""): ProcessResult = try {
        Subprocess.run(argv.toList(), stdin, 15.seconds)
    } catch (e: IllegalStateException) {
        // Subprocess reports a missing binary this way (wireguard-tools or iproute2 not installed).
        ProcessResult(127, "", e.message ?: "not found")
    }

    private fun explain(r: ProcessResult): String {
        val err = r.stderr
        return when {
            "Operation not permitted" in err ->
                "the mesh agent needs NET_ADMIN to create the mesh interface (cap_add: [NET_ADMIN])"
            "Unknown device type" in err || "not supported" in err ->
                "this host's kernel has no WireGuard support"
            r.exitCode == 127 -> "the mesh needs Linux with iproute2 and wireguard-tools ($err)"
            else -> err.trim().ifEmpty { "ip link add failed (exit ${r.exitCode})" }
        }
    }

    private companion object {
        val INTERVAL = 3.seconds
        const val REAPPLY_MS = 60_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
