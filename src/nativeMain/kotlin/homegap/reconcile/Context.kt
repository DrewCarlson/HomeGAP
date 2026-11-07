package homegap.reconcile

import homegap.api.DiffItemDto
import homegap.clients.PangolinClient
import homegap.clients.SshPool
import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.state.StateStore
import io.ktor.client.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Shared dependencies for one reconcile pass. */
class ReconcileContext(
    val config: AppConfig,
    val state: StateStore,
    val log: ReconcileLog,
    val ssh: SshPool,
    /** Plain HTTP (unix socket, loopback forwards). */
    val localHttp: HttpClient,
    /** TLS-capable client for external APIs. */
    val externalHttp: HttpClient,
    val dryRun: Boolean,
    val diff: DiffCollector,
) {
    val tunnelsDir: String get() = config.tunnels.stateDir ?: "${config.dataDir}/tunnels"

    fun nodePaused(id: String): Boolean = state.snapshot().pauses.let { it.global || id in it.nodes }
}

class DiffCollector {
    private val lock = SynchronizedObject()
    private val items = mutableListOf<DiffItemDto>()

    fun add(domain: String, target: String, key: String, action: String, desired: String? = null, actual: String? = null) {
        synchronized(lock) { items += DiffItemDto(domain, target, key, action, desired, actual) }
    }

    fun all(): List<DiffItemDto> = synchronized(lock) { items.toList() }
}

/** A node whose Pangolin is up and whose Integration API we can reach. */
class ReadyNode(
    val node: NodeConfig,
    val api: PangolinClient,
)
