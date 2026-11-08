package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.nodes.NodeEvents
import homegap.presentation.nodes.NodeRowModel
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*

/** Nodes with health and provisioning state; without [events] (overview) drift and actions are left out. */
@Composable
fun NodeTable(nodes: List<NodeRowModel>, events: NodeEvents?) {
    val full = events != null
    val headers = listOf("Node", "Health", "Checks", "Certificate", "Tunnel", "Last provision") +
        if (full) listOf("Drift", "") else emptyList()
    DataTable(headers, "No nodes configured yet. Add one below or in the config file.", nodes.isEmpty()) {
        nodes.forEach { node ->
            key(node.id) {
                Tr {
                    Cell("Node", "primary-cell") { NodeIdentity(node, showTcp = full) }
                    Cell("Health") { Pill(node.health) }
                    Cell("Checks") { Checks(node.checks) }
                    Cell("Certificate") {
                        node.certificate?.let {
                            Pill(it.badge)
                            Muted(it.subject, mono = true)
                        } ?: Placeholder()
                    }
                    Cell("Tunnel") { Pill(node.tunnel) }
                    Cell("Last provision") { Provisioning(node) }
                    if (events != null) {
                        Cell("Drift") {
                            if (node.drift.isEmpty()) Placeholder("none") else node.drift.forEach { Div({ classes("mono") }) { Text(it) } }
                        }
                        Cell("Actions", "actions-cell") { NodeActions(node, events) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeIdentity(node: NodeRowModel, showTcp: Boolean) {
    Div({ classes("cell-title") }) {
        B { Text(node.id) }
        node.tags.forEach { Tag(it) }
    }
    Muted(node.host, mono = true)
    node.meta?.let { Muted(it) }
    if (showTcp) {
        node.tcp?.let { Muted(it) }
        node.edgeTcp?.let { Muted(it) }
    }
}

@Composable
private fun Provisioning(node: NodeRowModel) {
    val provisioning = node.provisioning
    Div({ classes("inline-row") }) {
        Span { Text(provisioning.lastAt) }
        provisioning.badge?.let { Pill(it) }
    }
    provisioning.pending?.let { Muted(it) }
    provisioning.errors.forEach { ErrorText(it) }
}

@Composable
private fun NodeActions(node: NodeRowModel, events: NodeEvents) {
    val actions = node.actions
    Div({ classes("row-actions") }) {
        if (actions.pendingDecommission) {
            ActionButton("Confirm decommission", ButtonStyle.Danger, small = true) {
                window.prompt("Type the node id to stop its stack and forget it:")?.let { events.onDecommission(node.id, it) }
            }
            return@Div
        }
        if (actions.canRetryUpdate) {
            ActionButton("Retry update", ButtonStyle.Primary, small = true) { events.onRetryUpdate(node.id) }
        }
        if (actions.canSkipUpdate) {
            ActionButton("Skip update", small = true) { if (confirmSkipUpdate(node.id)) events.onSkipUpdate(node.id) }
        }
        ActionButton("Re-provision", small = true, enabled = actions.canReprovision) { events.onReprovision(node.id) }
        ActionButton(if (actions.paused) "Resume" else "Pause", small = true) { events.onTogglePause(node.id, actions.paused) }
        ActionButton("Remove", ButtonStyle.Danger, small = true) {
            val question = "Remove node ${node.id} from the config? DNS drains first; " +
                "the stack stays up until you confirm decommission."
            if (window.confirm(question)) events.onRemove(node.id)
        }
    }
}
