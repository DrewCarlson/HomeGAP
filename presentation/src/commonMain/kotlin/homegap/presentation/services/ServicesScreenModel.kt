package homegap.presentation.services

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.overview.MatrixCellModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.ProblemsFilterModel

/** Every published service, with how each edge serves it; filters for problems and by text. */
@Immutable
data class ServicesScreenModel(
    override val head: PageHeadModel,
    val query: String,
    val filter: ProblemsFilterModel,
    val edges: List<String>,
    val rows: List<ServiceRowModel>,
    val onQuery: (String) -> Unit,
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

/** One service: its hostname and container, how it is published, and each edge's cell. */
@Immutable
data class ServiceRowModel(
    val hostname: String,
    val container: String,
    val publishedAs: String,
    val access: String,
    val paused: Boolean,
    val health: Health,
    val cells: List<MatrixCellModel>,
    val location: Location,
)
