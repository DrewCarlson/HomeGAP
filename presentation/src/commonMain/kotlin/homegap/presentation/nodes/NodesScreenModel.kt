package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ChartModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.ProblemsFilterModel

/**
 * The fleet of edges and the cluster they share: the rollout strip (only while an update runs or
 * is held), the fleet table, the cluster database and the mesh. Per-node actions live in the
 * Inspector.
 */
@Immutable
data class NodesScreenModel(
    override val head: PageHeadModel,
    val rollout: RolloutStripModel?,
    val fleet: List<FleetRowModel>,
    val filter: ProblemsFilterModel,
    /** The cluster's database layer; null when the cluster is not configured. */
    val database: DatabasePanelModel?,
    val onSwitchPrimary: (memberId: String) -> Unit,
    /** The cluster mesh; null when the cluster is not configured. */
    val mesh: MeshMatrixModel?,
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** One node in the fleet table. */
@Immutable
data class FleetRowModel(
    val id: String,
    val place: String,
    val meta: String,
    val health: Health,
    val status: String,
    /** "6 / 7" checks passing. */
    val passing: String,
    val checks: List<Health>,
    val chart: ChartModel,
    val tunnel: String,
    val certificate: String,
    val stack: String,
    val drift: String,
    val location: Location,
    /** The row's own actions, behind its ⋮ button. */
    val menu: List<ActionModel> = emptyList(),
)

/** A stack update rolling through the nodes one at a time. */
@Immutable
data class RolloutStripModel(
    val title: String,
    val progress: String,
    val steps: List<RolloutStepModel>,
)

@Immutable
data class RolloutStepModel(
    val id: String,
    val text: String,
    val detail: String,
    val health: Health,
)

/** The mesh as a matrix: from each member (rows) to each other member (columns), in one [mode]. */
@Immutable
data class MeshMatrixModel(
    val links: String,
    val mode: MeshMode,
    val ids: List<String>,
    val rows: List<MeshRowModel>,
    val onMode: (MeshMode) -> Unit,
    val legend: String,
)

/** What a mesh cell shows: the probe from the host, the probe from the containers, or the last handshake. */
enum class MeshMode(val label: String) {
    Host("Host probe"),
    Container("Container probe"),
    Handshake("Handshake"),
}

@Immutable
data class MeshRowModel(
    val id: String,
    val cells: List<MeshCellModel>,
)

@Immutable
data class MeshCellModel(
    val text: String,
    val detail: String,
    val health: Health,
    val title: String,
)
