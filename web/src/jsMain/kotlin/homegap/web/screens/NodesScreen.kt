package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.nodes.DatabasePanelModel
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
    model.database?.let { DatabasePanel(it) }
    model.mesh?.let { MeshPanel(it) }
}

@Composable
private fun DatabasePanel(db: DatabasePanelModel) {
    Panel("Cluster database", "PostgreSQL (Patroni) and Valkey, shared by every node's Pangolin") {
        Div({ classes("inline-row") }) {
            Muted("PostgreSQL primary")
            Pill(db.primary)
            Muted("Valkey primary")
            Pill(db.valkeyPrimary)
        }
        db.switchBack?.let { Div({ classes("banner", "info") }) { Text(it) } }
        DataTable(listOf("Member", "Address", "Vote", "Database", "Replication lag"), "No members yet.", db.members.isEmpty()) {
            db.members.forEach { m ->
                key(m.id) {
                    Tr {
                        Cell("Member", "primary-cell") { B { Text(m.id) } }
                        Cell("Address", "mono") { Text(m.address) }
                        Cell("Vote") { Pill(m.vote) }
                        Cell("Database") { Pill(m.database) }
                        Cell("Replication lag", "mono") { Text(m.lag) }
                    }
                }
            }
        }
    }
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
