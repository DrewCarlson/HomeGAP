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
 * The fleet of nodes and the mesh they share: the rollout strip (only while an update runs or
 * is held), the fleet table and the mesh. Per-node actions live in the Inspector.
 */
@Immutable
data class NodesScreenModel(
    override val head: PageHeadModel,
    val rollout: RolloutStripModel?,
    val fleet: List<FleetRowModel>,
    val filter: ProblemsFilterModel,
    /** The mesh. Null before the first pass that reads it. */
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
    /** What to know about the node now: the step of its job, the check that fails, or its differences from the config. */
    val note: String? = null,
    val noteHealth: Health = Health.UNKNOWN,
    val chart: ChartModel,
    /** "link up" or "link down": the mesh link between the node and the homelab. */
    val meshLink: String,
    val meshLinkHealth: Health,
    val certificate: String,
    val certificateHealth: Health = Health.UNKNOWN,
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

/** The mesh as a matrix: the last handshake from each member (rows) with each other member (columns). */
@Immutable
data class MeshMatrixModel(
    val links: String,
    val ids: List<String>,
    val rows: List<MeshRowModel>,
    val legend: String,
)

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
