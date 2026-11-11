package homegap.presentation.config

import androidx.compose.runtime.Immutable
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel

@Immutable
data class ConfigScreenModel(
    override val head: homegap.presentation.ui.PageHeadModel,
    val loading: Boolean,
    val onlyChanges: Boolean,
    val diff: List<DiffRowModel>,
    val secrets: List<SecretRowModel>,
    val desiredState: String,
    val configPath: String?,
    val configYaml: String,
    val onToggleOnlyChanges: (Boolean) -> Unit,
) : ScreenModel

@Immutable
data class DiffRowModel(
    val domain: String,
    val target: String,
    val key: String,
    val action: Badge,
    val desired: String,
    val actual: String,
)

@Immutable
data class SecretRowModel(val name: String, val usedBy: String, val source: String, val present: Badge)
