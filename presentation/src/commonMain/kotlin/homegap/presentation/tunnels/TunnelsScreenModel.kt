package homegap.presentation.tunnels

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ChartModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel

/** The Newt connectors from the homelab to each edge. */
@Immutable
data class TunnelsScreenModel(
    override val head: PageHeadModel,
    val tunnels: List<TunnelCardModel>,
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** One connector: up or down, its process, restarts and throughput. */
@Immutable
data class TunnelCardModel(
    val nodeId: String,
    val endpoint: String,
    val health: Health,
    val status: String,
    val facts: List<String>,
    val chart: ChartModel,
    val location: Location,
)
