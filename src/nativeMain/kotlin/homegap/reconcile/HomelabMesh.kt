package homegap.reconcile

import homegap.config.MeshConfig
import homegap.config.SharedDirs
import homegap.platform.Files
import homegap.platform.ProcessResult
import homegap.platform.Subprocess
import homegap.platform.env
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.ClusterTemplates
import homegap.provision.Mesh
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The homelab's end of the mesh, as the orchestrator sees it. The `hg-mesh` interface and the
 * homelab's cluster member live in their own network namespace (the `homegap-mesh` container),
 * which the orchestrator is not part of: every node is a member of the cluster database, so what
 * runs next to that database must reach nothing else at home.
 *
 * The mesh agent (`homegap mesh`, in that namespace) owns the interface, its key and its firewall.
 * The orchestrator tells it what the mesh is through [dir] ([Mesh.SPEC_FILE]) and reads back what
 * it did ([Mesh.STATUS_FILE]). To reach the members itself (etcd, Patroni, Valkey), it routes the
 * mesh subnet through that namespace over the network the two share.
 */
class HomelabMesh(
    private val dir: String,
    /** The mesh namespace's name on the network it shares with the orchestrator. */
    private val gateway: String = env("HOMEGAP_MESH_HOST")?.takeIf { it.isNotBlank() } ?: "homegap-mesh",
    private val now: () -> Long = ::nowMillis,
) {
    private val specFile = "$dir/${Mesh.SPEC_FILE}"
    private val statusFile = "$dir/${Mesh.STATUS_FILE}"
    private var routed: String? = null

    /** The last report of the agent, or null if there is none or if it is older than [MAX_AGE_MS]. */
    fun status(): Mesh.AgentStatus? {
        val text = Files.readTextOrNull(statusFile) ?: return null
        val status = runCatching { json.decodeFromString<Mesh.AgentStatus>(text) }.getOrNull() ?: return null
        return status.takeIf { now() - it.at < MAX_AGE_MS }
    }

    /** The homelab's public key, which the agent creates with its key pair. */
    fun publicKey(): String {
        val status = status() ?: error(notReportedMessage())
        return status.publicKey ?: error(status.error ?: "the mesh agent has no key yet")
    }

    private fun notReportedMessage(): String {
        if (SharedDirs.notAVolume(dir)) {
            val hint = SharedDirs.notAVolumeHint(dir, "homegap-mesh", "homegap-mesh-agent")
            return "the mesh agent has not reported. $hint"
        }
        val setup = "the homegap-mesh-agent container shares the homegap-mesh namespace and the mesh volume"
        return "the mesh agent has not reported in $dir ($setup)"
    }

    /** Hands the agent the mesh ([address], [peers]) and routes the mesh subnet through its namespace. */
    suspend fun apply(mesh: MeshConfig, address: String, peers: List<Mesh.Peer>) {
        val spec = Mesh.AgentSpec(
            subnet = mesh.subnet,
            port = mesh.port,
            address = address,
            peers = peers.sortedBy { it.id },
            openPorts = ClusterTemplates.MESH_PORTS,
        )
        val text = json.encodeToString(spec)
        if (Files.readTextOrNull(specFile) != text) {
            Files.mkdirs(dir)
            Files.writeAtomic(specFile, text, "600".toInt(8))
        }
        route(mesh.subnet)
        status()?.error?.let { error(it) }
    }

    /** The interface's peers as the agent last saw them, or null before it applied the current mesh. */
    fun peers(): List<Mesh.PeerStatus>? {
        val status = status() ?: return null
        val wanted = Files.readTextOrNull(specFile) ?: return null
        return status.peers.takeIf { status.applied == Mesh.specHash(wanted) }
    }

    /**
     * Sends the mesh subnet through the mesh namespace. Each call finds the address of the
     * namespace again, because the address changes when that container is recreated.
     */
    private suspend fun route(subnet: String) {
        val resolved = run("getent", "ahostsv4", gateway).stdout.lineSequence()
            .map { it.substringBefore(' ').trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: error("the mesh namespace ($gateway) is not on a network the orchestrator shares (HOMEGAP_MESH_HOST)")
        val wanted = "$subnet via $resolved"
        if (routed == wanted && run("ip", "-4", "route", "show", subnet).stdout.contains("via $resolved ")) return
        val result = run("ip", "-4", "route", "replace", subnet, "via", resolved)
        check(result.ok) {
            val reason = when {
                "Operation not permitted" in result.stderr -> NEEDS_NET_ADMIN
                else -> result.stderr.trim().ifEmpty { "exit ${result.exitCode}" }
            }
            "could not route $subnet through $gateway: $reason"
        }
        routed = wanted
    }

    /** Subprocess reports a missing binary as an [IllegalStateException]. The result is exit code 127. */
    private suspend fun run(vararg argv: String): ProcessResult =
        runCatchingCancellable { Subprocess.run(argv.toList(), "", COMMAND_TIMEOUT) }.getOrElse { e ->
            // Subprocess reports a missing binary this way.
            if (e !is IllegalStateException) throw e
            ProcessResult(COMMAND_NOT_FOUND, "", e.message ?: "not found")
        }

    private companion object {
        /** The agent reports every few seconds. If the report is older than this, the agent does not run. */
        const val MAX_AGE_MS = 45_000L
        const val COMMAND_NOT_FOUND = 127
        const val NEEDS_NET_ADMIN = "the orchestrator needs NET_ADMIN to route the mesh subnet (cap_add: [NET_ADMIN])"
        val COMMAND_TIMEOUT = 15.seconds
        val json = Json { ignoreUnknownKeys = true }
    }
}
