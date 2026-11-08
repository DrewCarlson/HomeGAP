package homegap.presentation.config

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ConfigResponse
import homegap.api.DiffResponse
import homegap.api.Health
import homegap.api.SecretsResponse
import homegap.presentation.common.Badge
import homegap.presentation.core.Presenter
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ControllerApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data object ConfigScreenProps

@SingleIn(AppScope::class)
@Inject
class ConfigScreenPresenter(private val api: ControllerApi) : Presenter<ConfigScreenProps, ConfigScreenModel> {
    /** Everything the screen shows, loaded once when it opens. */
    private class Loaded(val diff: DiffResponse?, val secrets: SecretsResponse?, val config: ConfigResponse?)

    @Composable
    override fun model(props: ConfigScreenProps): ConfigScreenModel {
        val data by produceState<Loaded?>(null) {
            value = coroutineScope {
                val diff = async { runCatchingCancellable { api.diff() }.getOrNull() }
                val secrets = async { runCatchingCancellable { api.secrets() }.getOrNull() }
                val config = async { runCatchingCancellable { api.config() }.getOrNull() }
                Loaded(diff.await(), secrets.await(), config.await())
            }
        }
        var onlyChanges by remember { mutableStateOf(true) }
        val loaded = data
        val diff = remember(loaded, onlyChanges) {
            loaded?.diff?.items.orEmpty()
                .filter { !onlyChanges || it.action != "none" }
                .map { DiffRowModel(it.domain, it.target, it.key, Badge(if (it.action == "none") Health.OK else Health.DEGRADED, it.action), it.desired.orEmpty(), it.actual.orEmpty()) }
        }
        val secrets = remember(loaded) {
            loaded?.secrets?.secrets.orEmpty().map {
                SecretRowModel(it.name, it.usedBy, it.source, Badge.status(it.present, "present", "missing"))
            }
        }
        return ConfigScreenModel(
            loading = loaded == null,
            onlyChanges = onlyChanges,
            diff = diff,
            secrets = secrets,
            desiredState = loaded?.diff?.desiredState.orEmpty(),
            configPath = loaded?.config?.path,
            configYaml = loaded?.config?.yaml.orEmpty(),
            onToggleOnlyChanges = remember { { onlyChanges = it } },
        )
    }
}
