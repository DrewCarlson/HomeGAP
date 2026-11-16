package homegap.node

import homegap.model.NodeDesired
import homegap.provision.Mesh
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the agent of a node does. The orchestrator writes it to [FILE] in the folder [DIR] of the
 * node stack, and the agent acts when the file changes.
 */
@Serializable
data class AgentSpec(
    val listeners: List<Listener> = emptyList(),
    /** The exit of the node for the devices of the VPN. With null, the node has none. */
    val exit: Mesh.NodeExit? = null,
) {
    /**
     * A public TCP port that the agent accepts. The agent sends each connection to [target]
     * (`address:port`, on the mesh) with a PROXY protocol header first.
     */
    @Serializable
    data class Listener(val port: Int, val target: String)

    fun render(): String = JSON.encodeToString(this) + "\n"

    companion object {
        const val SERVICE = "homegap-agent"
        const val DIR = "agent"
        const val FILE = "agent.json"
        const val PATH = "$DIR/$FILE"

        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /** The spec for [desired]: one listener for each raw TCP port with PROXY protocol. */
        fun of(desired: NodeDesired): AgentSpec {
            val home = desired.meshHome ?: return AgentSpec(exit = desired.exit)
            val listeners = desired.proxiedResources.filter { !it.paused }.mapNotNull { resource ->
                resource.proxyPort?.let { Listener(it, "$home:$it") }
            }
            return AgentSpec(listeners.sortedBy { it.port }, desired.exit)
        }

        /** The spec in [text], or null if the text is not a spec. */
        fun parse(text: String): AgentSpec? = runCatching { JSON.decodeFromString<AgentSpec>(text) }.getOrNull()
    }
}
