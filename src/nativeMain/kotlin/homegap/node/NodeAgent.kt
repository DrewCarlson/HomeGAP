package homegap.node

import homegap.platform.Files
import homegap.platform.watchDirectory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * `homegap node-agent`: the part of HomeGAP that runs on a node, in the network of the host.
 *
 * It serves each raw TCP port that needs PROXY protocol. The kernel of the node forwards the other
 * raw ports, but the kernel cannot add the header. The agent accepts the connection, opens one to
 * the homelab over the mesh, sends the header and then passes the bytes in both directions.
 *
 * The agent reads [AgentSpec] from [dir] and acts when the file changes. A new port opens and a
 * removed port closes with no effect on the open connections. No container starts again for a
 * change of ports.
 */
class NodeAgent(private val dir: String, private val stateDir: String) {
    private val logger = KotlinLogging.logger("agent")
    private val specFile = "$dir/${AgentSpec.FILE}"

    suspend fun run(): Unit = coroutineScope {
        logger.info { "node agent started; it reads $specFile" }
        val relay = tcpRelay { line -> logger.info { line } }
        launch(Dispatchers.IO) { relay.run() }
        lateinit var control: NodeControlService
        val exit = NodeExitService(stateDir, this) { control.onState(it) }
        control = NodeControlService(stateDir, this, exit)
        var unreadable = false
        // The watch emits once when it starts, so the agent reads a file that is already there.
        watchDirectory(dir) { it == AgentSpec.FILE }.collect {
            val spec = Files.readTextOrNull(specFile)?.let { AgentSpec.parse(it) }
            if (spec == null && !unreadable) logger.error { "$specFile is not readable; the ports stay as they are" }
            unreadable = spec == null
            if (spec != null) {
                relay.apply(spec.listeners.associate { it.port to it.target })
                exit.apply(spec.exit)
                control.apply(spec.exit)
            }
        }
    }
}
