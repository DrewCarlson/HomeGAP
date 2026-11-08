package homegap.presentation.tunnels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter

data class TunnelsScreenProps(val status: StatusSnapshot)

@SingleIn(AppScope::class)
@Inject
class TunnelsScreenPresenter : Presenter<TunnelsScreenProps, TunnelsScreenModel> {
    @Composable
    override fun model(props: TunnelsScreenProps): TunnelsScreenModel =
        TunnelsScreenModel(remember(props.status.tunnels) { props.status.tunnels.map { it.toRowModel() } })
}
