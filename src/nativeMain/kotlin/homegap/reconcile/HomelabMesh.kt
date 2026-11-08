package homegap.reconcile

import homegap.config.MeshConfig
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.provision.Mesh
import kotlin.time.Duration.Companion.seconds

/**
 * The homelab's end of the mesh: the `hg-mesh` interface in the orchestrator's network namespace
 * (shared with the long-lived `homegap-mesh` container, so it survives orchestrator restarts).
 * Needs Linux, kernel WireGuard and `NET_ADMIN`; [apply] explains which one is missing.
 *
 * The private key is created once in [dir] (mode 0600) and never leaves it.
 */
class HomelabMesh(private val dir: String) {
    private val keyFile = "$dir/${Mesh.INTERFACE}.key"
    private val confFile = "$dir/${Mesh.INTERFACE}.conf"

    /** The homelab's public key, creating the key pair on first use. */
    suspend fun publicKey(): String {
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        if (Files.readTextOrNull(keyFile).isNullOrBlank()) {
            val key = wg("genkey").stdout.trim()
            Files.writeAtomic(keyFile, key + "\n", "600".toInt(8))
        }
        return wg("pubkey", stdin = privateKey() + "\n").stdout.trim()
    }

    /** Brings the interface to [address] with [peers]; peer changes apply in place. */
    suspend fun apply(mesh: MeshConfig, address: String, peers: List<Mesh.Peer>) {
        val prefix = Mesh.parseSubnet(mesh.subnet).prefix
        if (!run("ip", "link", "show", Mesh.INTERFACE).ok) {
            val r = run("ip", "link", "add", Mesh.INTERFACE, "type", "wireguard")
            if (!r.ok) throw IllegalStateException(explain(r))
        }
        Files.writeAtomic(confFile, Mesh.homelabConfig(mesh, privateKey(), peers), "600".toInt(8))
        wg("syncconf", Mesh.INTERFACE, confFile)
        val addresses = run("ip", "-4", "addr", "show", "dev", Mesh.INTERFACE).stdout
        if ("inet $address/$prefix " !in addresses) {
            run("ip", "-4", "addr", "flush", "dev", Mesh.INTERFACE)
            check(run("ip", "addr", "add", "$address/$prefix", "dev", Mesh.INTERFACE).ok) { "could not set the mesh address $address" }
        }
        check(run("ip", "link", "set", Mesh.INTERFACE, "up").ok) { "could not bring ${Mesh.INTERFACE} up" }
        guardInbound()
    }

    /**
     * Nodes may not open connections to the homelab over the mesh (the dashboard and everything
     * else listening here stay private): only replies to the homelab's own connections, and ping,
     * come in. Services that must accept mesh connections are allowed explicitly ahead of the drop.
     */
    private suspend fun guardInbound() {
        for (rule in INBOUND_RULES) {
            val spec = listOf("-i", Mesh.INTERFACE) + rule
            if (run("iptables", "-C", "INPUT", *spec.toTypedArray()).ok) continue
            val r = run("iptables", "-A", "INPUT", *spec.toTypedArray())
            check(r.ok) { "could not set the mesh firewall (iptables ${spec.joinToString(" ")}): ${r.stderr.trim()}" }
        }
    }

    /** Current peers, or null when the interface does not exist. */
    suspend fun peers(): List<Mesh.PeerStatus>? {
        val r = run("wg", "show", Mesh.INTERFACE, "dump")
        return if (r.ok) Mesh.parseDump(r.stdout) else null
    }

    private companion object {
        val INBOUND_RULES = listOf(
            listOf("-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"),
            listOf("-p", "icmp", "-j", "ACCEPT"),
            listOf("-j", "DROP"),
        )
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
                "the orchestrator needs NET_ADMIN to create the mesh interface (cap_add: [NET_ADMIN])"
            "Unknown device type" in err || "not supported" in err ->
                "this host's kernel has no WireGuard support"
            r.exitCode == 127 -> "the mesh needs Linux with iproute2 and wireguard-tools ($err)"
            else -> err.trim().ifEmpty { "ip link add failed (exit ${r.exitCode})" }
        }
    }
}
