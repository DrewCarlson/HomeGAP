package homegap.presentation.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.presentation.core.Presenter
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.ControllerSession
import homegap.presentation.format.TimeFormatter
import homegap.presentation.format.plural
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.StatusLineModel
import homegap.presentation.ui.StatusMessage
import kotlinx.coroutines.launch

data class ActivityScreenProps(
    val now: Long = 0,
    /** The selected entry's sequence number. */
    val selection: String? = null,
)

/** The reconcile log: the live entries from the event stream, then older pages from the server. */
@SingleIn(AppScope::class)
@Inject
class ActivityScreenPresenter(
    private val session: ControllerSession,
    private val api: ControllerApi,
    private val times: TimeFormatter,
) : Presenter<ActivityScreenProps, ActivityScreenModel> {
    @Composable
    override fun model(props: ActivityScreenProps): ActivityScreenModel {
        val log by session.log.collectAsState()
        val scope = rememberCoroutineScope()
        var level by remember { mutableStateOf<LogLevel?>(null) }
        var domain by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf<Long?>(null) }
        var older by remember { mutableStateOf<List<LogEntryDto>>(emptyList()) }
        var exhausted by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        val all = remember(log, older) { (log + older).distinctBy { it.seq } }
        val shown = remember(all, level, domain, query, pass) {
            all.filter { entry -> entry.matches(level, domain, query) && (pass == null || entry.pass == pass) }
        }
        val resetOlder = {
            older = emptyList()
            exhausted = false
        }
        val hourAgo = props.now - 3_600_000
        val recent = log.filter { it.time >= hourAgo }
        val changes = recent.count { it.level == LogLevel.CHANGE }
        val lastError = recent.firstOrNull { it.level == LogLevel.ERROR }
        val selected = props.selection?.toLongOrNull()?.let { seq -> all.firstOrNull { it.seq == seq } }
        return ActivityScreenModel(
            head = PageHeadModel(
                caption = "Activity · reconcile log",
                headline = "${plural(changes, "change")} in the last hour.",
                statusLine = lastError?.let { entry ->
                    StatusLineModel(
                        listOf(
                            StatusMessage(
                                severity = Severity.Down,
                                text = listOfNotNull(entry.target, entry.message).joinToString(": "),
                                location = Location(Route.Activity, entry.seq.toString()),
                            )
                        )
                    )
                },
            ),
            level = level,
            levels = listOf(null, LogLevel.CHANGE, LogLevel.WARN, LogLevel.ERROR, LogLevel.INFO),
            domain = domain,
            domains = DOMAINS,
            query = query,
            pass = pass,
            onClearPass = { pass = null },
            entries = shown.take(MAX_SHOWN).map { it.toRow() },
            emptyText = if (all.isEmpty()) "Nothing logged yet." else "No entries match the filters.",
            canLoadOlder = !exhausted && all.isNotEmpty(),
            loadingOlder = loading,
            onLevel = {
                level = it
                resetOlder()
            },
            onDomain = {
                domain = it
                resetOlder()
            },
            onQuery = {
                query = it
                resetOlder()
            },
            onLoadOlder = {
                if (!loading) {
                    loading = true
                    scope.launch {
                        val oldest = all.minOfOrNull { it.seq }
                        runCatchingCancellable {
                            api.log(
                                beforeSeq = oldest,
                                limit = PAGE,
                                level = level,
                                domain = domain,
                                query = query,
                            )
                        }.onSuccess { page ->
                            older = older + page.entries
                            exhausted = page.entries.size < PAGE
                        }
                        loading = false
                    }
                }
            },
            inspector = selected?.let { entry ->
                inspector(
                    entry = entry,
                    onShowPass = { pass = entry.pass },
                )
            },
        )
    }

    private fun LogEntryDto.toRow(): LogRowModel = LogRowModel(
        seq = seq,
        time = times.time(time),
        absolute = times.dateTime(time),
        level = level,
        severity = level.severity(),
        domain = domain,
        target = target,
        message = message,
        location = Location(Route.Activity, seq.toString()),
    )

    private fun inspector(entry: LogEntryDto, onShowPass: () -> Unit): InspectorModel = InspectorModel(
        kind = "Log entry",
        title = listOfNotNull(entry.target, entry.domain).joinToString(" · "),
        status = "${entry.level.name.lowercase()} · ${times.dateTime(entry.time)}",
        health = when (entry.level) {
            LogLevel.ERROR -> homegap.api.Health.DOWN
            LogLevel.WARN -> homegap.api.Health.DEGRADED
            else -> homegap.api.Health.OK
        },
        sections = listOf(
            InspectorSection.Code(title = "Message", text = entry.message),
            InspectorSection.Facts(
                title = null,
                rows = listOfNotNull(
                    FactRow("Domain", entry.domain),
                    entry.target?.let { FactRow("Target", it, mono = true) },
                    FactRow("Level", entry.level.name.lowercase()),
                    entry.pass?.let { FactRow("Pass", "#$it", mono = true) },
                    FactRow("Sequence", "#${entry.seq}", mono = true),
                ) + entry.fields.map { (key, value) ->
                    FactRow(key.replace('_', ' '), value, mono = true)
                },
            ),
        ),
        actions = listOfNotNull(
            entry.pass?.let {
                ActionModel(
                    label = "Show pass #$it",
                    onClick = onShowPass,
                    mutates = false,
                )
            },
        ),
    )

    private companion object {
        /** Reconcile domains in the order they run; `null` shows everything. */
        val DOMAINS = listOf(null, "discovery", "nodes", "tunnels", "resources", "dns", "certificates", "private", "observe", "cluster", "setup", "speedtest", "controller")
        const val PAGE = 200
        const val MAX_SHOWN = 1500
    }
}

private fun LogEntryDto.matches(level: LogLevel?, domain: String?, query: String): Boolean {
    val text = query.trim().lowercase()
    return (level == null || this.level == level) &&
        (domain == null || this.domain == domain) &&
        (text.isEmpty() || text in message.lowercase() || target?.lowercase()?.contains(text) == true)
}

private fun LogLevel.severity(): Severity = when (this) {
    LogLevel.ERROR -> Severity.Down
    LogLevel.WARN -> Severity.Warn
    else -> Severity.Info
}
