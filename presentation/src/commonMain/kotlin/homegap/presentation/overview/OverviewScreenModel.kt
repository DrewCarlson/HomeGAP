package homegap.presentation.overview

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.api.LabelErrorDto
import homegap.presentation.core.ScreenModel
import homegap.presentation.nodes.NodeRowModel
import homegap.presentation.services.ServiceRowModel

@Immutable
data class OverviewScreenModel(
    val stats: List<StatModel>,
    val labelErrors: List<LabelErrorDto>,
    val nodes: List<NodeRowModel>,
    val services: List<ServiceRowModel>,
    /** Nodes and the homelab on the map; empty when none has a location. */
    val map: homegap.presentation.map.MapModel = homegap.presentation.map.MapModel(),
    /** Whether the homelab's location is missing (the map then has no homelab or lines). */
    val homelabUnplaced: Boolean = false,
) : ScreenModel

/** A counter tile. */
@Immutable
data class StatModel(val label: String, val value: String, val health: Health, val hint: String? = null)
