package homegap.reconcile

import homegap.config.MeshConfig
import homegap.config.SharedDirs
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.provision.Mesh
import kotlinx.serialization.json.Json

/**
 * The homelab's end of the mesh, as the orchestrator sees it. The `hg-mesh` interface and the VPN
 * peer live in their own network namespace (the `homegap-mesh` container), which the orchestrator
 * is not part of: what a node can reach at home is only what that namespace forwards.
 *
 * The mesh agent (`homegap mesh`, in that namespace) owns the interface, its key and its firewall.
 * The orchestrator tells it what the mesh is through [dir] ([Mesh.SPEC_FILE]) and reads back what
 * it did ([Mesh.STATUS_FILE]).
 */
class HomelabMesh(private val dir: String, private val now: () -> Long = ::nowMillis) {
    private val specFile = "$dir/${Mesh.SPEC_FILE}"
    private val statusFile = "$dir/${Mesh.STATUS_FILE}"

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
            val hint = SharedDirs.notAVolumeHint(dir, "data/mesh", "homegap-mesh-agent")
            return "the mesh agent has not reported. $hint"
        }
        return "the mesh agent has not reported in $dir. Check that the homegap-mesh-agent container runs."
    }

    /** Hands the agent the mesh: [address], [peers] and the [forwards] with the VPN. */
    fun apply(mesh: MeshConfig, address: String, peers: List<Mesh.Peer>, forwards: MeshForwards.Result) {
        val spec = Mesh.AgentSpec(
            subnet = mesh.subnet,
            port = mesh.port,
            address = address,
            peers = peers.sortedBy { it.id },
            openPorts = emptyList(),
            forwards = forwards.homelab.sortedWith(compareBy({ it.port }, { it.protocol })),
            vpn = forwards.vpn,
        )
        val text = json.encodeToString(spec)
        if (Files.readTextOrNull(specFile) != text) {
            Files.mkdirs(dir)
            Files.writeAtomic(specFile, text, "600".toInt(8))
        }
        status()?.error?.let { error(it) }
    }

    /** The interface's peers as the agent last saw them, or null before it applied the current mesh. */
    fun peers(): List<Mesh.PeerStatus>? {
        val status = status() ?: return null
        val wanted = Files.readTextOrNull(specFile) ?: return null
        return status.peers.takeIf { status.applied == Mesh.specHash(wanted) }
    }

    private companion object {
        /** The agent reports four times a minute, and after each change. If the report is older than this, the agent does not run. */
        const val MAX_AGE_MS = 45_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
