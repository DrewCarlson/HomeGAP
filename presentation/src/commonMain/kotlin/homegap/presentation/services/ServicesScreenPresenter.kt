package homegap.presentation.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.PauseScope

data class ServicesScreenProps(val status: StatusSnapshot)

@SingleIn(AppScope::class)
@Inject
class ServicesScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
) : Presenter<ServicesScreenProps, ServicesScreenModel> {
    @Composable
    override fun model(props: ServicesScreenProps): ServicesScreenModel {
        val rows = remember(props.status.services) { props.status.services.map { it.toRowModel() } }
        val onTogglePause = remember {
            { key: String, paused: Boolean ->
                actions.run(if (paused) "Resume" else "Pause") { api.pause(PauseScope.Service, key, !paused) }
            }
        }
        return ServicesScreenModel(rows, onTogglePause)
    }
}
