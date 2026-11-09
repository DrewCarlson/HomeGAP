package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.api.Health
import kotlinx.browser.window
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
    model.database?.let { DatabasePanel(it, model.events.onSwitchPrimary) }
    model.mesh?.let { MeshPanel(it) }
}

@Composable
private fun DatabasePanel(db: DatabasePanelModel, onSwitchPrimary: (String) -> Unit) {
    Panel("Cluster database", "PostgreSQL (Patroni) and Valkey, shared by every node's Pangolin") {
        Div({ classes("panel-body") }) {
            db.alerts.forEach { alert ->
                val kind = when (alert.health) { Health.DOWN -> "error"; Health.DEGRADED -> "warn"; else -> "info" }
                Div({ classes("banner", kind) }) { Text(alert.text) }
            }
            Div({ classes("inline-row") }) {
                Muted("PostgreSQL primary")
                Pill(db.primary)
                Muted("Valkey primary")
                Pill(db.valkeyPrimary)
                Muted("Vote")
                Pill(db.quorum)
            }
            db.switchBack?.let { Div({ classes("banner", "info") }) { Text(it) } }
        }
        DataTable(listOf("Member", "Address", "Vote", "Database", "Valkey", "Replication lag", ""), "No members yet.", db.members.isEmpty()) {
            db.members.forEach { m ->
                key(m.id) {
                    Tr {
                        Cell("Member", "primary-cell") { B { Text(m.id) } }
                        Cell("Address", "mono") { Text(m.address) }
                        Cell("Vote") { Pill(m.vote) }
                        Cell("Database") { Pill(m.database) }
                        Cell("Valkey") { Pill(m.valkey) }
                        Cell("Replication lag", "mono") { Text(m.lag) }
                        Cell("", "actions") {
                            if (m.canBecomePrimary) ActionButton("Make primary", small = true) {
                                if (window.confirm(SWITCH_CONFIRM.replace("@ID@", m.id))) onSwitchPrimary(m.id)
                            }
                        }
                    }
                }
            }
        }
        if (db.history.isNotEmpty()) {
            Div({ classes("panel-body") }) { Muted("Primary history") }
            DataTable(listOf("When", "Change", "Cause")) {
                db.history.forEach { h ->
                    Tr {
                        Cell("When", "nowrap") { Text(h.time) }
                        Cell("Change") { Text(h.change) }
                        Cell("Cause") { Pill(h.cause, small = true) }
                    }
                }
            }
        }
    }
}

private const val SWITCH_CONFIRM = "Make @ID@ the database primary now? Writes pause for a second or two while it switches. " +
    "If @ID@ isn't where the primary belongs, HomeGAP moves it back after the hold."

@Composable
private fun MeshPanel(mesh: MeshPanelModel) {
    Panel("Cluster mesh", "Private WireGuard links between the homelab and every node (${mesh.subnet})") {
        DataTable(listOf("Member", "Address", "Status", "Last handshake with", "Host probe (last hour)", "Container probe (last hour)"), "No members yet.", mesh.members.isEmpty()) {
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
                        Cell("Host probe (last hour)") { ProbePills(member.probes) }
                        Cell("Container probe (last hour)") { ProbePills(member.containerProbes) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbePills(probes: List<homegap.presentation.common.Badge>) {
    if (probes.isEmpty()) Muted("—")
    else Div({ classes("inline-row") }) { probes.forEach { Pill(it, small = true) } }
}
