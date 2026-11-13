package homegap.presentation.overview

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.map.MapModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ChartModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity

/**
 * The whole system at a glance, top to bottom: the headline, the gauges, what needs attention,
 * the network, each edge's graph, services through each edge, and system vitals with recent changes.
 */
@Immutable
data class OverviewScreenModel(
    override val head: PageHeadModel,
    val gauges: List<GaugeModel>,
    val attention: List<AttentionRowModel>,
    val network: NetworkModel,
    val edges: List<EdgeCardModel>,
    val matrix: MatrixModel,
    val system: List<FactRow>,
    val recent: List<RecentChangeModel>,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** A counter with a bar: "Edges healthy 5 / 6". */
@Immutable
data class GaugeModel(
    val label: String,
    val value: String,
    /** How full the bar is, 0 to 1. */
    val fraction: Double,
    val health: Health,
)

@Immutable
data class AttentionRowModel(
    val severity: Severity,
    val title: String,
    val detail: String,
    val since: String,
    val location: Location,
    /** Items that follow from this one: a node that waits for the certificate whose order failed. */
    val children: List<AttentionRowModel> = emptyList(),
)

/** Which view the network figure shows; remembered per browser. */
enum class NetworkView {
    Path,
    Map,
}

/** The network figure: the traffic path (homelab, tunnels, edges, names) or the map. */
@Immutable
data class NetworkModel(
    val view: NetworkView,
    val homelab: HomelabModel,
    val edges: List<EdgeModel>,
    val map: MapModel,
    val onView: (NetworkView) -> Unit,
)

@Immutable
data class HomelabModel(
    val place: String,
    val facts: List<String>,
)

/** An edge on the traffic path or the map. */
@Immutable
data class EdgeModel(
    val id: String,
    val place: String,
    val health: Health,
    /** "API 412 ms" */
    val api: String,
    /** "mesh 31 ms", or null without a cluster. */
    val mesh: String?,
    /** The hostnames it serves, shortened. */
    val names: String,
    val location: Location,
)

/** One edge's card: its Pangolin API probe over the last hour, with what else is measured. */
@Immutable
data class EdgeCardModel(
    val id: String,
    val place: String,
    val health: Health,
    val status: String,
    val chart: ChartModel,
    val facts: List<String>,
    val location: Location,
)

/** Services × edges: each cell is the response time through that edge, or the reason it fails. */
@Immutable
data class MatrixModel(
    val edges: List<String>,
    val rows: List<MatrixRowModel>,
    val total: Int,
)

@Immutable
data class MatrixRowModel(
    val label: String,
    val location: Location,
    val cells: List<MatrixCellModel>,
)

@Immutable
data class MatrixCellModel(
    val text: String,
    val health: Health,
    val title: String,
)

@Immutable
data class RecentChangeModel(
    val time: String,
    val absolute: String,
    val text: String,
    val severity: Severity,
)
