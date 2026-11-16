package homegap.presentation.nodes

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.api.StatusSnapshot
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.data.Samples
import homegap.presentation.format.label
import homegap.presentation.format.plural
import homegap.presentation.overview.edgesSentence
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.chartOf
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.on
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.rememberProblemsFilter
import homegap.presentation.ui.statusLineOf

data class NodesScreenProps(
    val status: StatusSnapshot,
    val now: Long,
    val metrics: Map<String, Map<String, Samples>> = emptyMap(),
    /** The selected node id. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class NodesScreenPresenter(
    private val nodeActions: NodeActions,
) : Presenter<NodesScreenProps, NodesScreenModel> {
    @Composable
    override fun model(props: NodesScreenProps): NodesScreenModel {
        val status = props.status
        val now = props.now
        val selected = status.nodes.firstOrNull { it.id == props.selection }
        val filter = rememberProblemsFilter(
            total = status.nodes.size,
            problems = status.nodes.count { it.health != Health.OK },
        )
        return NodesScreenModel(
            head = PageHeadModel(
                caption = "Nodes",
                headline = edgesSentence(status.nodes),
                statusLine = statusLineOf(status.attention.on(Route.Nodes)),
                actions = listOf(
                    ActionModel(
                        label = "+ Add node",
                        style = ActionStyle.Primary,
                        location = Location(Route.AddNode),
                    ),
                ),
            ),
            rollout = rollout(status.nodes),
            filter = filter,
            fleet = status.nodes
                .filter { !filter.problemsOnly || it.health != Health.OK }
                .sortedWith(compareBy<NodeStatusDto> { it.health.rank() }.thenBy { it.id })
                .map { node ->
                fleetRow(
                    node = node,
                    samples = props.metrics[node.id],
                    now = now,
                )
            },
            mesh = status.mesh?.let { mesh -> meshMatrix(mesh.toPanelModel(now)) },
            selected = selected?.let { Location(Route.Nodes, it.id) },
            inspector = selected?.let { node ->
                nodeInspector(
                    node = node,
                    samples = props.metrics[node.id],
                    now = now,
                    actions = nodeActions.forNode(node),
                    certificates = status.certificates,
                )
            },
            empty = if (status.nodes.isEmpty()) {
                EmptyStateModel(
                    title = "No nodes yet",
                    text = "A node is a small VPS near your visitors. HomeGAP sets it up over SSH: " +
                        "a user, a key, the firewall and the software. Nothing changes until you run it.",
                    action = ActionModel(
                        label = "+ Add node",
                        style = ActionStyle.Primary,
                        location = Location(Route.AddNode),
                    ),
                )
            } else {
                null
            },
        )
    }

    private fun fleetRow(
        node: NodeStatusDto,
        samples: Map<String, Samples>?,
        now: Long,
    ): FleetRowModel {
        val passing = node.checks.count { it.health == Health.OK }
        val problem = node.checks.firstOrNull { it.health == Health.DOWN } ?: node.checks.firstOrNull { it.health == Health.DEGRADED }
        return FleetRowModel(
            id = node.id,
            place = node.location?.name ?: node.region,
            meta = listOf(node.provider, node.region, node.host).filter { it.isNotBlank() }.joinToString(" · "),
            health = node.health,
            status = when {
                node.paused -> "paused · ${node.health.label}"
                node.waitingForCertificate -> "waiting for certificate"
                else -> node.health.label
            },
            passing = "$passing / ${node.checks.size}",
            note = node.jobStep ?: problem?.let { listOf(checkTitle(it.name), it.detail).filter(String::isNotBlank).joinToString(": ") }
                ?: node.drift.takeIf { it.isNotEmpty() }?.let { "${plural(it.size, "difference")} from config" },
            noteHealth = when {
                node.jobStep != null -> Health.UNKNOWN
                problem != null -> problem.health
                else -> Health.DEGRADED
            },
            chart = chartOf(
                label = "Requests served",
                samples = samples?.get("rps"),
                nowSeconds = now / 1000,
                unit = "r/s",
            ),
            meshLink = if (node.tunnelUp) "link up" else "link down",
            meshLinkHealth = if (node.tunnelUp) Health.OK else Health.DOWN,
            certificate = if (node.waitingForCertificate) "waiting" else node.certificate?.daysRemaining?.let { "$it days" } ?: "—",
            certificateHealth = node.checks.firstOrNull { it.name == "tls-mgmt" }?.health ?: Health.UNKNOWN,
            stack = stackText(node),
            drift = if (node.drift.isEmpty()) "none" else plural(node.drift.size, "difference"),
            location = Location(Route.Nodes, node.id),
            // A menu with one item only repeats an action of the Inspector, so the row shows none.
            menu = nodeActions.rowMenu(node.id).takeIf { it.size > 1 }.orEmpty(),
        )
    }

    private fun stackText(node: NodeStatusDto): String {
        val update = node.update
        return when {
            node.job != null -> node.jobStep ?: "${node.job}…"
            update?.status == "failed" -> if (update.rolledBack) "update rolled back" else "update failed"
            update?.status == "skipped" -> "update skipped"
            node.updateWaiting != null -> "update waiting"
            node.teardownError != null -> "teardown failed"
            node.pendingDecommission -> "pending decommission"
            else -> node.stack ?: "current"
        }
    }

    /** Shown only while an update is running, waiting or held by a failure. */
    private fun rollout(nodes: List<NodeStatusDto>): RolloutStripModel? {
        val involved = nodes.filter { it.job == "updating" || it.updateWaiting != null || it.update?.status == "failed" }
        if (involved.isEmpty()) return null
        val steps = nodes.map { node ->
            val update = node.update
            when {
                node.job == "updating" -> RolloutStepModel(node.id, "updating", node.jobStep ?: "running now", Health.DEGRADED)
                update?.status == "failed" -> RolloutStepModel(
                    id = node.id,
                    text = if (update.rolledBack) "failed, rolled back" else "failed",
                    detail = "holds the rollout",
                    health = Health.DOWN,
                )

                node.updateWaiting != null -> RolloutStepModel(node.id, "waiting", node.updateWaiting.orEmpty(), Health.UNKNOWN)
                else -> RolloutStepModel(node.id, "updated", "", Health.OK)
            }
        }
        val held = steps.any { it.health == Health.DOWN }
        val target = nodes.firstNotNullOfOrNull { it.targetStack }
        val from = nodes.firstNotNullOfOrNull { node -> node.stack?.takeIf { node.targetStack != null } }
        val versions = if (target != null) " · ${from ?: "?"} → $target" else ""
        return RolloutStripModel(
            title = (if (held) "Update held" else "Update, one node at a time") + versions,
            progress = "${steps.count { it.health == Health.OK }} of ${steps.size} updated",
            steps = steps,
        )
    }

    private fun meshMatrix(panel: MeshPanelModel): MeshMatrixModel {
        val ids = panel.members.map { it.id }
        val rows = panel.members.map { member ->
            MeshRowModel(
                id = member.id,
                cells = ids.map { other ->
                    if (other == member.id) {
                        MeshCellModel(text = "", detail = "", health = Health.UNKNOWN, title = "")
                    } else {
                        member.peers.firstOrNull { it.text.substringBefore(" ") == other }?.toCell()
                            ?: MeshCellModel(
                                text = "—",
                                detail = "",
                                health = Health.UNKNOWN,
                                title = member.error ?: "No handshake from ${member.id}",
                            )
                    }
                },
            )
        }
        val links = panel.members.sumOf { it.peers.size }
        val up = panel.members.sumOf { member -> member.peers.count { it.health == Health.OK } }
        return MeshMatrixModel(
            links = "$up of $links links",
            ids = ids,
            rows = rows,
            legend = "When each member last completed a WireGuard handshake with each other one " +
                "(about every 2 min while traffic flows).",
        )
    }
}

/** "eu1 · 2 min ago" as a cell: the time of the handshake, with the rest under it. */
private fun Badge.toCell(): MeshCellModel {
    val parts = text.split(" · ").drop(1)
    return MeshCellModel(
        text = parts.firstOrNull() ?: text,
        detail = parts.drop(1).joinToString(" · "),
        health = health,
        title = tooltip.orEmpty(),
    )
}

/** Problems first in the fleet table. */
private fun Health.rank(): Int = when (this) {
    Health.DOWN -> 0
    Health.DEGRADED -> 1
    Health.UNKNOWN -> 2
    Health.OK -> 3
}
