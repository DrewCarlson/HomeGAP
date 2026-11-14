package homegap.state

import homegap.platform.Files
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RUN_HISTORY_SIZE = 10

/** The mode of the state files: only the owner can read and write them. */
private val OWNER_ONLY = "600".toInt(8)

/**
 * Per-node identity and minted credentials. The node itself keeps an authoritative copy of its
 * secrets (`<install_dir>/secrets/node.json`). If this cache is lost, the orchestrator reads them
 * from the node again.
 */
@Serializable
data class NodeState(
    val setupToken: String? = null,
    val serverSecret: String? = null,
    val adminEmail: String? = null,
    val adminPassword: String? = null,
    val apiKeyId: String? = null,
    val apiKey: String? = null,
    val siteId: Int? = null,
    val newtId: String? = null,
    val newtSecret: String? = null,
    /** The node's exit site (cluster.exit_nodes.nodes) and its connector's credentials. */
    val exitSiteId: Int? = null,
    val exitNewtId: String? = null,
    val exitNewtSecret: String? = null,
    val licenseActivated: Boolean = false,
    val lastProvisionAt: Long? = null,
    val lastProvisionOk: Boolean? = null,
    val lastProvisionError: String? = null,
    val lastCheckAt: Long? = null,
    val stackHash: String? = null,
    val facts: Map<String, String> = emptyMap(),
    val drift: List<String> = emptyList(),
    val pendingDecommission: Boolean = false,
    /**
     * Why the teardown of a removed node did not finish. The node is out of the cluster vote from
     * the start of its teardown, so a value here also means that it is not a member.
     */
    val teardownError: String? = null,
    /** Outcome of the last stack update that did not go through (rolled back or skipped). */
    val update: UpdateState? = null,
    /** Permanent tunnel slot (subnet, interface name, local ports). See TunnelSlots. */
    val tunnelSlot: Int? = null,
    /** [tunnelSlot] matched the node's live site subnet at least once. */
    val tunnelSlotConfirmed: Boolean = false,
    /** The node's mesh public key, learned from the node (its private key never leaves it). */
    val meshPublicKey: String? = null,
    /** The last provisioning and update runs, newest first. */
    val runs: List<RunRecord> = emptyList(),
)

/** One provisioning or update run of a node. */
@Serializable
data class RunRecord(
    /** `provision` or `update`. */
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val error: String? = null,
    /** The stack hash the run applied. */
    val stack: String? = null,
    val rolledBack: Boolean = false,
)

/** These runs with [run] added first. The list keeps the last ten. */
fun List<RunRecord>.withRun(run: RunRecord): List<RunRecord> = (listOf(run) + this).take(RUN_HISTORY_SIZE)

/**
 * A stack update that failed on this node. While [status] is `failed`, the rollout to every other
 * node waits. `skipped` releases the hold, but this node stays on its previous stack. That ends
 * when its desired stack changes again or when an update runs again.
 */
@Serializable
data class UpdateState(
    /** Stack hash the update tried to apply. */
    val targetHash: String,
    /** `failed` or `skipped`. */
    val status: String,
    val error: String? = null,
    val at: Long,
    /** The rollback restored the previous stack, and that stack is healthy again. */
    val rolledBack: Boolean = false,
)

@Serializable
data class Pauses(
    val global: Boolean = false,
    val nodes: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
)

/**
 * The one Pangolin of the cluster. Every node shares it (one database), so the store keeps its
 * secrets, admin, API key and license once, not for each node.
 */
@Serializable
data class ClusterPangolinState(
    val serverSecret: String,
    val setupToken: String,
    val adminEmail: String,
    val adminPassword: String,
    val apiKeyId: String? = null,
    val apiKey: String? = null,
    /** A node's Pangolin has migrated the database and the server admin and org exist. */
    val bootstrapped: Boolean = false,
    val licenseActivated: Boolean = false,
    /** Hash of a key that the license server refused, so a pass does not try that key again. */
    val licenseRejected: String? = null,
    /** Nodes whose running Pangolin has not seen the license activated through another node. */
    val licenseRecheck: Set<String> = emptySet(),
    /** Hash of each shared resource's last applied blueprint (`private:<id>` for private ones, `base`). */
    val blueprints: Map<String, String> = emptyMap(),
    /** Hash of the action set last granted to the API key. */
    val keyActions: String? = null,
)

@Serializable
data class ControllerState(
    val version: Int = 1,
    val nodes: Map<String, NodeState> = emptyMap(),
    val pauses: Pauses = Pauses(),
    /** The id of the Bunny zone of the platform domain. */
    val bunnyZoneId: Long? = null,
    /** The id of the Bunny zone of each extra domain, by the name of the zone. */
    val bunnyZoneIds: Map<String, Long> = emptyMap(),
    val clusterPangolin: ClusterPangolinState? = null,
)

class StateStore(private val path: String) {
    private val logger = KotlinLogging.logger {}
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }
    private val mutex = Mutex()
    private var state: ControllerState = load()

    /**
     * No file means a first start or a deliberate reset, so the cache starts empty. A file that
     * is there but that the store cannot read or parse is a different case. An empty cache drops
     * every pause, hold and tunnel slot, and the next pass acts on that. The store uses the last
     * good copy instead. If there is no good copy, the orchestrator does not start.
     */
    private fun load(): ControllerState {
        if (!Files.exists(path)) return ControllerState()
        val loaded = runCatching { Files.readText(path) }
            .mapCatching { it to json.decodeFromString<ControllerState>(it) }
        loaded.getOrNull()?.let { (text, parsed) ->
            // The copy to fall back on, as of this start.
            runCatching { Files.writeAtomic(backupPath, text, OWNER_ONLY) }
                .onFailure { logger.warn { "could not keep a copy of $path at $backupPath: ${it.message}" } }
            return parsed
        }
        val reason = loaded.exceptionOrNull()?.message ?: "unreadable"
        runCatching { Files.readText(path) }.onSuccess { Files.writeAtomic("$path.corrupt", it, OWNER_ONLY) }
        val backup = readBackup()
        if (backup != null) {
            logger.error { "State file $path is unreadable ($reason); using the last good copy $backupPath" }
            return backup
        }
        error(
            "The state file $path is there but cannot be read ($reason), and there is no good copy at $backupPath. " +
                "HomeGAP does not start from an empty state by itself: that drops every pause and every node's " +
                "tunnel slot. Restore the file, or delete it to start from an empty state on purpose.",
        )
    }

    private fun readBackup(): ControllerState? {
        if (!Files.exists(backupPath)) return null
        return runCatching { json.decodeFromString<ControllerState>(Files.readText(backupPath)) }.getOrNull()
    }

    private val backupPath get() = "$path.bak"

    fun snapshot(): ControllerState = state

    /** Increases by one with each change that the store saves. */
    val revision = MutableStateFlow(0L)

    fun node(id: String): NodeState = state.nodes[id] ?: NodeState()

    suspend fun update(block: (ControllerState) -> ControllerState): ControllerState = mutex.withLock {
        val next = block(state)
        if (next != state) {
            state = next
            Files.writeAtomic(path, json.encodeToString(next), OWNER_ONLY)
            revision.value++
        }
        state
    }

    suspend fun updateNode(id: String, block: (NodeState) -> NodeState): NodeState {
        val updated = update { current ->
            val node = block(current.nodes[id] ?: NodeState())
            current.copy(nodes = current.nodes + (id to node))
        }
        return updated.nodes.getValue(id)
    }

    suspend fun removeNode(id: String) = update { current -> current.copy(nodes = current.nodes - id) }
}
