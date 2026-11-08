package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.nodes.MeshPanelModel
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr

@Composable
fun NodesScreen(model: NodesScreenModel, onNavigate: (Route) -> Unit) {
    Panel("Nodes", actions = {
        NavLink(HashRouter.href(Route.AddNode), { onNavigate(Route.AddNode) }, listOf("btn", "primary", "sm")) { Text("Add node") }
    }) {
        NodeTable(model.nodes, model.events)
    }
    model.mesh?.let { MeshPanel(it) }
}

@Composable
private fun MeshPanel(mesh: MeshPanelModel) {
    Panel("Cluster mesh", "Private WireGuard links between the homelab and every node (${mesh.subnet})") {
        DataTable(listOf("Member", "Address", "Status", "Last handshake with"), "No members yet.", mesh.members.isEmpty()) {
            mesh.members.forEach { member ->
                key(member.id) {
                    Tr {
                        Cell("Member", "primary-cell") { B { Text(member.id) } }
                        Cell("Address", "mono") { Text(member.address) }
                        Cell("Status") {
                            Pill(member.status)
                            member.error?.let { Muted(it) }
                        }
                        Cell("Last handshake with") {
                            Div({ classes("inline-row") }) { member.peers.forEach { Pill(it, small = true) } }
                        }
                    }
                }
            }
        }
    }
}
