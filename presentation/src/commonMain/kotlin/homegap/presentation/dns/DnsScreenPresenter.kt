package homegap.presentation.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter

data class DnsScreenProps(val status: StatusSnapshot)

@SingleIn(AppScope::class)
@Inject
class DnsScreenPresenter : Presenter<DnsScreenProps, DnsScreenModel> {
    @Composable
    override fun model(props: DnsScreenProps): DnsScreenModel =
        DnsScreenModel(remember(props.status.dns) { props.status.dns.map { it.toRowModel() } })
}
