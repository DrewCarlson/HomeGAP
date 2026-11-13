package homegap.presentation.activity

import androidx.compose.runtime.Immutable
import homegap.api.LogLevel
import homegap.presentation.core.ScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity

/** The reconcile log, newest first, with filters by level, domain and text, and older pages on demand. */
@Immutable
data class ActivityScreenModel(
    override val head: PageHeadModel,
    val level: LogLevel?,
    val levels: List<LogLevel?>,
    val domain: String?,
    val domains: List<String?>,
    val query: String,
    /** Only this reconcile pass's entries, when set. */
    val pass: Long?,
    val onClearPass: () -> Unit,
    val entries: List<LogRowModel>,
    val emptyText: String,
    val canLoadOlder: Boolean,
    val loadingOlder: Boolean,
    val onLevel: (LogLevel?) -> Unit,
    val onDomain: (String?) -> Unit,
    val onQuery: (String) -> Unit,
    val onLoadOlder: () -> Unit,
    /** The row that the Inspector shows, so the list can mark it. */
    val selected: Location? = null,
    override val inspector: InspectorModel? = null,
    override val empty: EmptyStateModel? = null,
) : ScreenModel

@Immutable
data class LogRowModel(
    val seq: Long,
    val time: String,
    val absolute: String,
    val level: LogLevel,
    val severity: Severity,
    val domain: String,
    val target: String?,
    val message: String,
    val location: Location,
)
