package homegap.presentation.config

import androidx.compose.runtime.Immutable
import homegap.presentation.common.Badge
import homegap.presentation.core.ScreenModel
import homegap.presentation.ui.ProblemsFilterModel

@Immutable
data class ConfigScreenModel(
    override val head: homegap.presentation.ui.PageHeadModel,
    val loading: Boolean,
    val onlyChanges: Boolean,
    /** All rows or only the rows that differ. */
    val filter: ProblemsFilterModel,
    val diff: List<DiffRowModel>,
    val secrets: List<SecretRowModel>,
    val desiredState: String,
    val configPath: String?,
    val configYaml: String,
    /** Shown when the config has `acme` keys: they set the certificate of the nodes. */
    val acmeNote: String? = null,
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
