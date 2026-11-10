package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.tunnels.TunnelsScreenModel
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr

@Composable
fun TunnelsScreen(model: TunnelsScreenModel) {
    Panel(
        title = "Newt connectors",
        subtitle = "Run by the tunnels container; independent of the orchestrator"
    ) {
        val headers = remember {
            listOf("Node", "Endpoint", "Site", "Process", "Restarts", "Traffic in / out")
        }
        DataTable(
            headers = headers,
            emptyText = "No tunnels yet.",
            isEmpty = model.tunnels.isEmpty()
        ) {
            model.tunnels.forEach { tunnel ->
                key(tunnel.nodeId) {
                    Tr {
                        Cell("Node", "primary-cell") {
                            B { Text(tunnel.nodeId) }
                        }
                        Cell("Endpoint", "mono") {
                            Text(tunnel.endpoint)
                        }
                        Cell("Site") {
                            Pill(tunnel.site)
                        }
                        Cell("Process") {
                            Pill(tunnel.process)
                            tunnel.lastExit?.let { Muted(it) }
                        }
                        Cell("Restarts") {
                            Text(tunnel.restarts)
                        }
                        Cell("Traffic in / out", "mono") {
                            Text(tunnel.traffic)
                        }
                    }
                }
            }
        }
    }
}
