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
) : ScreenModel

/** A counter tile. */
@Immutable
data class StatModel(val label: String, val value: String, val health: Health, val hint: String? = null)
