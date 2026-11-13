package homegap.reconcile

import homegap.api.DiffItemDto
import homegap.clients.PangolinClient
import homegap.clients.SshPool
import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.config.tunnelsDir
import homegap.state.Pauses
import homegap.state.StateStore
import io.ktor.client.HttpClient
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
    val dryRun: Boolean,
    val diff: DiffCollector,
    /** This one pass applies changes even under a global pause ("apply just this change"). */
    val applyOnce: Boolean = false,
) {
    val tunnelsDir: String get() = config.tunnelsDir

    /** The pauses that apply to this pass. An [applyOnce] pass has no global pause. */
    fun pauses(): Pauses = state.snapshot().pauses.let { if (applyOnce) it.copy(global = false) else it }

    fun nodePaused(id: String): Boolean = pauses().let { it.global || id in it.nodes }
}

class DiffCollector {
    private val lock = SynchronizedObject()
    private val items = mutableListOf<DiffItemDto>()

    fun add(
        domain: String,
        target: String,
        key: String,
        action: String,
        desired: String? = null,
        actual: String? = null,
    ) {
        synchronized(lock) {
            items += DiffItemDto(
                domain = domain,
                target = target,
                key = key,
                action = action,
                desired = desired,
                actual = actual,
            )
        }
    }

    fun all(): List<DiffItemDto> = synchronized(lock) { items.toList() }
}

/** A node whose Pangolin runs and whose Integration API the orchestrator can reach. */
class ReadyNode(
    val node: NodeConfig,
    val api: PangolinClient,
    /** The node's dashboard API (`/api/v1`), for the few calls the Integration API lacks (signup). */
    val dashboardUrl: String? = null,
)
