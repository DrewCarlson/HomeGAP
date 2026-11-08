package homegap.presentation.activity

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.presentation.core.Presenter
import homegap.presentation.data.ControllerSession
import homegap.presentation.format.TimeFormatter

data object ActivityScreenProps

/** The reconcile log, pushed by the event stream and filtered by domain here. */
@SingleIn(AppScope::class)
@Inject
class ActivityScreenPresenter(
    private val session: ControllerSession,
    private val timeFormatter: TimeFormatter,
) : Presenter<ActivityScreenProps, ActivityScreenModel> {
    @Composable
    override fun model(props: ActivityScreenProps): ActivityScreenModel {
        val log by session.log.collectAsState()
        var domain by remember { mutableStateOf<String?>(null) }
        val entries = remember(log, domain) {
            log.filter { domain == null || it.domain == domain }.map {
                LogRowModel(it.seq, timeFormatter.time(it.time), it.level, it.domain, it.target, it.message)
            }
        }
        return ActivityScreenModel(
            domains = DOMAINS,
            selectedDomain = domain,
            entries = entries,
            emptyText = domain?.let { "Nothing logged for $it yet." } ?: "Nothing logged yet.",
            onSelectDomain = remember { { domain = it } },
        )
    }

    private companion object {
        /** Reconcile domains in the order they run; `null` shows everything. */
        val DOMAINS = listOf(null, "discovery", "nodes", "tunnels", "resources", "dns", "private", "observe", "speedtest", "controller")
    }
}
