package homegap.state

import homegap.platform.Files
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-node identity and minted credentials. The node itself keeps an authoritative copy of its
 * secrets (`<install_dir>/secrets/node.json`), so losing this cache only means re-reading them.
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
    val licenseActivated: Boolean = false,
    val lastProvisionAt: Long? = null,
    val lastProvisionOk: Boolean? = null,
    val lastProvisionError: String? = null,
    val lastCheckAt: Long? = null,
    val stackHash: String? = null,
    val facts: Map<String, String> = emptyMap(),
    val drift: List<String> = emptyList(),
    val pendingDecommission: Boolean = false,
    /** Outcome of the last stack update that did not go through (rolled back or skipped). */
    val update: UpdateState? = null,
    /** Permanent tunnel slot (subnet, interface name, local ports); see TunnelSlots. */
    val tunnelSlot: Int? = null,
    /** [tunnelSlot] matched the node's live site subnet at least once. */
    val tunnelSlotConfirmed: Boolean = false,
    /** The node's mesh public key, learned from the node (its private key never leaves it). */
    val meshPublicKey: String? = null,
)

/**
 * A stack update that failed on this node. While [status] is `failed`, the rollout to every other
 * node is held; `skipped` releases the hold but keeps this node on its previous stack until its
 * desired stack changes again or an update is retried.
 */
@Serializable
data class UpdateState(
    /** Stack hash the update tried to apply. */
    val targetHash: String,
    /** `failed` or `skipped`. */
    val status: String,
    val error: String? = null,
    val at: Long,
    /** The previous stack was restored and came back healthy. */
    val rolledBack: Boolean = false,
)

@Serializable
data class Pauses(
    val global: Boolean = false,
    val nodes: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
)

/**
 * The cluster's one Pangolin: shared by every node (one database), so its secrets, admin, API key
 * and license are kept once rather than per node.
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
    /** Hash of a key the license server refused, so it is not retried every pass. */
    val licenseRejected: String? = null,
    /** Nodes whose running Pangolin has not seen the license activated through another node. */
    val licenseRecheck: Set<String> = emptySet(),
)

@Serializable
data class ControllerState(
    val version: Int = 1,
    val nodes: Map<String, NodeState> = emptyMap(),
    val pauses: Pauses = Pauses(),
    val bunnyZoneId: Long? = null,
    val clusterPangolin: ClusterPangolinState? = null,
)

class StateStore(private val path: String) {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val mutex = Mutex()
    private var state: ControllerState = load()

    private fun load(): ControllerState {
        val text = Files.readTextOrNull(path) ?: return ControllerState()
        return try {
            json.decodeFromString(ControllerState.serializer(), text)
        } catch (e: Exception) {
            logger.error(e) { "State file $path is unreadable; starting from an empty cache (reality will be re-read)" }
            Files.writeAtomic("$path.corrupt", text, "600".toInt(8))
            ControllerState()
        }
    }

    fun snapshot(): ControllerState = state

    /** Bumped on every persisted change. */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun node(id: String): NodeState = state.nodes[id] ?: NodeState()

    suspend fun update(block: (ControllerState) -> ControllerState): ControllerState = mutex.withLock {
        val next = block(state)
        if (next != state) {
            state = next
            Files.writeAtomic(path, json.encodeToString(ControllerState.serializer(), next), "600".toInt(8))
            revision.value++
        }
        state
    }

    suspend fun updateNode(id: String, block: (NodeState) -> NodeState): NodeState =
        update { s -> s.copy(nodes = s.nodes + (id to block(s.nodes[id] ?: NodeState()))) }.nodes.getValue(id)

    suspend fun removeNode(id: String) = update { s -> s.copy(nodes = s.nodes - id) }
}
