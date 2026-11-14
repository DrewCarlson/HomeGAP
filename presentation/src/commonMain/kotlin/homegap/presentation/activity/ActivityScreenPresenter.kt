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
import homegap.presentation.format.shortDate
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
                caption = "Activity",
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
            entries = when {
                query.isBlank() && pass == null -> foldIdlePasses(shown).take(MAX_SHOWN).map { (entry, passes) -> entry.toRow(props.now, passes) }
                else -> shown.take(MAX_SHOWN).map { it.toRow(props.now) }
            },
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
            selected = selected?.let { Location(Route.Activity, it.seq.toString()) },
            inspector = selected?.let { entry ->
                inspector(
                    entry = entry,
                    onShowPass = { pass = entry.pass },
                )
            },
        )
    }

    /** The row of the entry. [passes] over 1 makes it stand for that many passes that changed nothing. */
    private fun LogEntryDto.toRow(now: Long, passes: Int = 1): LogRowModel = LogRowModel(
        seq = seq,
        // An entry of another day shows its day too.
        time = (if (now - time > DAY_MS) "${shortDate(time)} " else "") + times.time(time),
        absolute = times.dateTime(time),
        level = level,
        severity = level.severity(),
        domain = domain,
        target = target,
        message = when (passes) {
            1 -> message
            else -> "$passes passes with no changes, the last in ${fields["duration_ms"].orEmpty()} ms"
        },
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
                ) + entry.fields.filterKeys { it != "detail" }.map { (key, value) ->
                    FactRow(key.replace('_', ' '), value, mono = true)
                },
            ),
        ) + listOfNotNull(entry.fields["detail"]?.let { InspectorSection.Code(title = "Detail", text = it) }),
        actions = listOfNotNull(
            entry.fields["order"]?.takeIf { entry.domain == "certificates" }?.let { order ->
                ActionModel(
                    label = "Open the order",
                    location = homegap.presentation.routing.Location(homegap.presentation.routing.Route.Certificates, "order:$order"),
                )
            },
            entry.pass?.let {
                ActionModel(
                    label = "Show pass #$it",
                    onClick = onShowPass,
                )
            },
        ),
    )

    private companion object {
        /** Reconcile domains in the order they run; `null` shows everything. */
        val DOMAINS = listOf(null, "discovery", "nodes", "tunnels", "resources", "dns", "certificates", "private", "observe", "cluster", "setup", "speedtest", "controller")
        const val PAGE = 200
        const val MAX_SHOWN = 1500
        const val DAY_MS = 86_400_000L
    }
}

/** A reconcile pass that changed nothing and had no error. */
private val LogEntryDto.isIdlePass: Boolean
    get() = domain == "controller" && fields["changes"] == "0" && fields["errors"] == "0"

/**
 * Folds each run of passes that changed nothing into its newest entry, with the size of the run.
 * A quiet system logs two of them a minute, and they hide the entries that matter.
 */
internal fun foldIdlePasses(entries: List<LogEntryDto>): List<Pair<LogEntryDto, Int>> {
    val folded = mutableListOf<Pair<LogEntryDto, Int>>()
    for (entry in entries) {
        val last = folded.lastOrNull()
        if (entry.isIdlePass && last != null && last.first.isIdlePass) {
            folded[folded.lastIndex] = last.first to last.second + 1
        } else {
            folded += entry to 1
        }
    }
    return folded
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
