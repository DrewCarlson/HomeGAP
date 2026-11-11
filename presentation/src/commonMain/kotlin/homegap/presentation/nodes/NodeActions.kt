package homegap.presentation.nodes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.NodeStatusDto
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.PauseScope
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.ConfirmModel

/** Every per-node action, with its confirmation where one is needed; shared by the screens that show nodes. */
@SingleIn(AppScope::class)
@Inject
class NodeActions(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val pausedGuard: homegap.presentation.data.PausedGuard,
    private val relocation: NodeRelocation,
) {
    /** The buttons for [node] in the Inspector, most used first. */
    fun forNode(node: NodeStatusDto): List<ActionModel> = buildList {
        val update = node.update
        if (update?.status == "failed") {
            add(
                ActionModel(
                    label = "Retry update",
                    style = ActionStyle.Primary,
                    onClick = { actions.run("Retry update") { api.retryUpdate(node.id) } },
                )
            )
            add(
                ActionModel(
                    label = "Skip update",
                    onClick = { skipUpdate(node.id) },
                )
            )
        } else if (update != null) {
            add(
                ActionModel(
                    label = "Retry update",
                    onClick = { actions.run("Retry update") { api.retryUpdate(node.id) } },
                )
            )
        }
        add(
            ActionModel(
                label = "Diagnostics",
                location = Location(Route.Diagnostics, node.id),
            )
        )
        add(
            ActionModel(
                label = "Speed test",
                location = Location(Route.SpeedTest, node.id),
            )
        )
        add(
            ActionModel(
                label = "Re-provision",
                enabled = node.job == null,
                tooltip = if (node.job != null) "A ${node.job} job is running" else "Run the node's setup again",
                onClick = { reprovision(node.id) },
            )
        )
        add(
            ActionModel(
                label = if (node.paused) "Resume" else "Pause",
                tooltip = if (node.paused) "Apply changes to this node again" else "Leave this node as it is; observe only",
                onClick = { togglePause(node.id, node.paused) },
            )
        )
        add(
            ActionModel(
                label = "Change location",
                tooltip = "Move the node on the maps and in geo DNS steering",
                onClick = { relocation.open(node.id) },
            )
        )
        if (node.pendingDecommission) {
            add(
                ActionModel(
                    label = "Decommission",
                    style = ActionStyle.Danger,
                    onClick = { decommission(node.id) },
                )
            )
        } else {
            add(
                ActionModel(
                    label = "Remove",
                    style = ActionStyle.Danger,
                    onClick = { removeNode(node.id) },
                )
            )
        }
    }

    fun reprovision(id: String) {
        confirmations.ask(
            ConfirmModel(
                title = "Re-provision $id?",
                text = "HomeGAP runs the node's setup again: it rewrites the stack and restarts what changed.",
                confirmLabel = "Re-provision $id",
                consequences = listOf(
                    "Visitors keep being served while it runs; a restart takes a few seconds",
                    "Progress shows on the Nodes page",
                ),
            )
        ) {
            pausedGuard.run("Re-provisioning $id") {
                actions.run("Re-provision") { api.provision(id) }
            }
        }
    }

    fun skipUpdate(id: String) {
        confirmations.ask(
            ConfirmModel(
                title = "Skip the update on $id?",
                text = "$id stays on its current stack and the other nodes are updated.",
                confirmLabel = "Skip update",
            )
        ) {
            actions.run("Skip update") { api.skipUpdate(id) }
        }
    }

    fun togglePause(id: String, paused: Boolean) {
        actions.run(if (paused) "Resume" else "Pause") { api.pause(PauseScope.Node, id, !paused) }
    }

    fun removeNode(id: String) {
        confirmations.ask(
            ConfirmModel(
                title = "Remove $id from HomeGAP?",
                text = "$id is taken out of DNS first, so visitors move to other edges. Its stack keeps running until you confirm decommission.",
                confirmLabel = "Remove $id",
                consequences = listOf(
                    "DNS drains now: $id leaves every record set",
                    "Nothing is deleted on the server yet",
                    "Undo: add the node again with the same id",
                ),
                typeToConfirm = id,
                danger = true,
            )
        ) {
            actions.run("Remove") { api.removeNode(id) }
        }
    }

    fun decommission(id: String) {
        confirmations.ask(
            ConfirmModel(
                title = "Decommission $id?",
                text = "HomeGAP stops $id's stack and removes what it installed on the server. This can't be undone.",
                confirmLabel = "Decommission $id",
                consequences = listOf(
                    "The node's containers and data under /opt/homegap are removed",
                    "Its Pangolin site and DNS records are deleted",
                    "The server itself keeps running; delete it at your provider",
                ),
                typeToConfirm = id,
                danger = true,
            )
        ) {
            actions.run("Decommission") { api.decommission(id, id) }
        }
    }

    fun switchPrimary(id: String) {
        confirmations.ask(
            ConfirmModel(
                title = "Make $id the database primary?",
                text = "Writes pause for a second or two while it switches. If $id isn't where the primary belongs, HomeGAP moves it back after the hold.",
                confirmLabel = "Make $id primary",
            )
        ) {
            pausedGuard.run("Making $id the primary") {
                actions.run("Switch primary") { api.switchPrimary(id) }
            }
        }
    }
}
