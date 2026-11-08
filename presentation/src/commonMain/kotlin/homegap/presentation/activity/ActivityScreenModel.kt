package homegap.presentation.activity

import androidx.compose.runtime.Immutable
import homegap.api.LogLevel
import homegap.presentation.core.ScreenModel

@Immutable
data class ActivityScreenModel(
    /** Domain filters; `null` is "all". */
    val domains: List<String?>,
    val selectedDomain: String?,
    val entries: List<LogRowModel>,
    val emptyText: String,
    val onSelectDomain: (String?) -> Unit,
) : ScreenModel

@Immutable
data class LogRowModel(
    val seq: Long,
    val time: String,
    val level: LogLevel,
    val domain: String,
    val target: String?,
    val message: String,
)
