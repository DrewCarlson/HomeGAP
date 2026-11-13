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
import homegap.presentation.format.plural
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.StatusLineModel
import homegap.presentation.ui.StatusMessage
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
        val pending = loaded?.diff?.items?.count { it.action != "none" }
        val missing = loaded?.secrets?.secrets?.filter { !it.present }.orEmpty()
        return ConfigScreenModel(
            head = PageHeadModel(
                caption = "Config · desired vs. actual",
                headline = when (pending) {
                    null -> "Loading the config…"
                    0 -> "Everything matches the config."
                    else -> "${plural(pending, "difference")} from the config."
                } + if (missing.isEmpty()) "" else " ${plural(missing.size, "secret")} ${if (missing.size == 1) "is" else "are"} missing.",
                statusLine = missing.takeIf { it.isNotEmpty() }?.let { secrets ->
                    StatusLineModel(
                        secrets.map { secret ->
                            StatusMessage(
                                severity = Severity.Down,
                                text = "${secret.name} is missing (${secret.source}), used by ${secret.usedBy}.",
                            )
                        }
                    )
                },
            ),
            loading = loaded == null,
            onlyChanges = onlyChanges,
            diff = diff,
            secrets = secrets,
            desiredState = loaded?.diff?.desiredState.orEmpty(),
            configPath = loaded?.config?.path,
            configYaml = loaded?.config?.yaml.orEmpty(),
            acmeNote = ACME_NOTE.takeIf { loaded?.config?.yaml?.contains(Regex("^acme:", RegexOption.MULTILINE)) == true },
            onToggleOnlyChanges = remember { { onlyChanges = it } },
        )
    }
}

private const val ACME_NOTE = "The acme keys set the certificate of the nodes. A change of acme.staging or acme.directory starts a new order, " +
    "and a change of acme.caa changes who can issue for the domain."
