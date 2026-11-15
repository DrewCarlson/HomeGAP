package homegap.reconcile

import homegap.config.MeshConfig
import homegap.config.SharedDirs
import homegap.mesh.MeshRoute
import homegap.platform.Files
import homegap.platform.env
import homegap.platform.nowMillis
import homegap.provision.ClusterTemplates
import homegap.provision.Mesh
import kotlinx.coroutines.delay
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
 * it did ([Mesh.STATUS_FILE]). To reach the members itself (etcd, Patroni, Valkey), the mesh
 * subnet goes through that namespace over the network that the two share ([MeshRoute]).
 */
class HomelabMesh(
    private val dir: String,
    /** The mesh namespace's name on the network it shares with the orchestrator. */
    private val gateway: String = env("HOMEGAP_MESH_HOST")?.takeIf { it.isNotBlank() } ?: "homegap-mesh",
    private val now: () -> Long = ::nowMillis,
) {
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
        return "the mesh agent has not reported in $dir. $CLUSTER_SERVICES_HINT"
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
     * Waits until the mesh subnet goes through the mesh namespace. The orchestrator is an ordinary
     * user and cannot change a route: the `homegap-mesh-route` container sets it. Each call finds
     * the address of the namespace again, because the address changes when that container is
     * recreated.
     */
    private suspend fun route(subnet: String) {
        val resolved = MeshRoute.gatewayAddress(gateway)
            ?: error("the mesh namespace ($gateway) is not on a network of the orchestrator. $CLUSTER_SERVICES_HINT")
        repeat(ROUTE_POLLS) {
            if (MeshRoute.present(subnet, resolved)) return
            delay(ROUTE_POLL)
        }
        check(MeshRoute.present(subnet, resolved)) {
            "no route to $subnet through $gateway: the homegap-mesh-route container sets it. $CLUSTER_SERVICES_HINT"
        }
    }

    private companion object {
        /** The agent reports every few seconds. If the report is older than this, the agent does not run. */
        const val MAX_AGE_MS = 45_000L
        const val ROUTE_POLLS = 15
        val ROUTE_POLL = 1.seconds
        /** The cluster services of the shipped compose file run only with the profile `cluster`. */
        const val CLUSTER_SERVICES_HINT =
            "If the cluster services of the stack do not run, add COMPOSE_PROFILES=cluster to .env " +
                "and run `docker compose up -d`."
        val json = Json { ignoreUnknownKeys = true }
    }
}
