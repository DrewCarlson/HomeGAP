package homegap.presentation.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.api.StatusSnapshot
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.data.Preferences
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
    private val preferences: Preferences,
) : Presenter<NodesScreenProps, NodesScreenModel> {
    @Composable
    override fun model(props: NodesScreenProps): NodesScreenModel {
        val status = props.status
        val now = props.now
        var meshMode by remember {
            mutableStateOf(
                preferences.get(MESH_MODE_KEY)?.let { saved -> MeshMode.entries.firstOrNull { it.name == saved } } ?: MeshMode.Host
            )
        }
        val onMeshMode = remember {
            { mode: MeshMode ->
                meshMode = mode
                preferences.set(MESH_MODE_KEY, mode.name)
            }
        }
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
                    status = status,
                    now = now,
                )
            },
            database = remember(status.cluster, now / 60_000) { status.cluster?.toPanelModel(now) },
            onSwitchPrimary = remember { { id: String -> nodeActions.switchPrimary(id) } },
            mesh = status.mesh?.let { mesh ->
                meshMatrix(
                    panel = mesh.toPanelModel(now),
                    mode = meshMode,
                    onMode = onMeshMode,
                )
            },
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
                    text = "An edge node is a small VPS near your visitors. HomeGAP sets it up over SSH: " +
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
        status: StatusSnapshot,
        now: Long,
    ): FleetRowModel {
        val passing = node.checks.count { it.health == Health.OK }
        val api = node.checks.firstOrNull { it.name == "pangolin-api" }
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
            checks = node.checks.map { it.health },
            chart = chartOf(
                label = "Pangolin API probe",
                samples = samples?.get("api"),
                nowSeconds = now / 1000,
                threshold = api?.threshold,
            ),
            tunnel = when {
                status.tunnels.firstOrNull { it.nodeId == node.id }?.up == true -> "healthy"
                node.tunnelUp -> "healthy"
                else -> "failing"
            },
            certificate = if (node.waitingForCertificate) "waiting" else node.certificate?.daysRemaining?.let { "$it days" } ?: "—",
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

    private fun meshMatrix(
        panel: MeshPanelModel,
        mode: MeshMode,
        onMode: (MeshMode) -> Unit,
    ): MeshMatrixModel {
        val ids = panel.members.map { it.id }
        val rows = panel.members.map { member ->
            val badges = when (mode) {
                MeshMode.Host -> member.probes
                MeshMode.Container -> member.containerProbes
                MeshMode.Handshake -> member.peers
            }
            MeshRowModel(
                id = member.id,
                cells = ids.map { other ->
                    if (other == member.id) {
                        MeshCellModel(text = "", detail = "", health = Health.UNKNOWN, title = "")
                    } else {
                        badges.firstOrNull { it.text.substringBefore(" ") == other }?.toCell()
                            ?: MeshCellModel(
                                text = "—",
                                detail = "",
                                health = Health.UNKNOWN,
                                title = member.error ?: "No ${mode.label.lowercase()} from ${member.id}",
                            )
                    }
                },
            )
        }
        val links = panel.members.sumOf { it.peers.size }
        val up = panel.members.sumOf { member -> member.peers.count { it.health == Health.OK } }
        return MeshMatrixModel(
            links = "$up of $links links",
            mode = mode,
            ids = ids,
            rows = rows,
            onMode = onMode,
            legend = when (mode) {
                MeshMode.Host -> "Round trip of each member's probe over the mesh, from its host, with loss and outages in the last hour."
                MeshMode.Container -> "The same probe from inside the member's Docker network, the path Pangolin's database traffic takes."
                MeshMode.Handshake -> "When each member last completed a WireGuard handshake with each other one (about every 2 min while traffic flows)."
            },
        )
    }

    private companion object {
        const val MESH_MODE_KEY = "nodes.mesh"
    }
}

/** "eu1 · 12.3 ms · 0.4% lost" as a cell: the round trip on top, loss and outages under it. */
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
