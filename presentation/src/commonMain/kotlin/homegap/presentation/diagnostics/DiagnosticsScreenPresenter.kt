package homegap.presentation.diagnostics

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.NodeLogServices
import homegap.api.NodeTextDto
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.core.ScreenModel
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ControllerApi
import kotlinx.coroutines.launch

data class DiagnosticsScreenProps(
    val status: StatusSnapshot,
    /** The node to look at, when the page was opened for one (`#diagnostics/sg1`). */
    val selection: String? = null,
)

/** A node's logs and diagnostics bundle, fetched on demand. */
@Immutable
data class DiagnosticsScreenModel(
    override val head: PageHeadModel,
    /** The bundle as a report, when the output is one. */
    val report: List<ReportSection>,
    /** The certificate on the chosen node and what it serves on each of its names. No file content. */
    val certificate: List<homegap.presentation.ui.FactRow> = emptyList(),
    val nodes: List<String>,
    val node: String?,
    /** Service id to label. */
    val services: List<Pair<String, String>>,
    val service: String,
    val sinceChoices: List<String>,
    val since: String,
    val loading: Boolean,
    val error: String?,
    val output: NodeTextDto?,
    val onSelectNode: (String) -> Unit,
    val onSelectService: (String) -> Unit,
    val onSelectSince: (String) -> Unit,
    val onShowLogs: () -> Unit,
    val onRunDiagnostics: () -> Unit,
) : ScreenModel

@SingleIn(AppScope::class)
@Inject
class DiagnosticsScreenPresenter(private val api: ControllerApi) : Presenter<DiagnosticsScreenProps, DiagnosticsScreenModel> {
    @Composable
    override fun model(props: DiagnosticsScreenProps): DiagnosticsScreenModel {
        val nodes = remember(props.status.nodes) { props.status.nodes.map { it.id } }
        var chosen by remember(props.selection) { mutableStateOf(props.selection) }
        val node = chosen?.takeIf { it in nodes } ?: nodes.firstOrNull()
        var service by remember { mutableStateOf(NodeLogServices.ALL.first().first) }
        var since by remember { mutableStateOf(SINCE.first()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var output by remember { mutableStateOf<NodeTextDto?>(null) }
        val scope = rememberCoroutineScope()

        val current by rememberUpdatedState(Triple(node, service, since))
        fun load(block: suspend (String) -> NodeTextDto) {
            val id = current.first ?: return
            if (loading) return
            loading = true
            error = null
            scope.launch {
                runCatchingCancellable { block(id) }
                    .onSuccess { output = it }
                    .onFailure { error = it.message ?: it.toString() }
                loading = false
            }
        }

        val shown = output
        return DiagnosticsScreenModel(
            head = PageHeadModel(
                caption = "Diagnostics · read-only",
                headline = if (node == null) "No nodes to look at yet." else "Logs and checks on $node.",
            ),
            report = remember(shown) {
                shown?.takeIf { it.text.contains("\n=== ") || it.text.startsWith("=== ") }?.let { reportOf(it.text) }.orEmpty()
            },
            certificate = node?.let { certificateFacts(props.status, it) }.orEmpty(),
            nodes = nodes,
            node = node,
            services = NodeLogServices.ALL,
            service = service,
            sinceChoices = SINCE,
            since = since,
            loading = loading,
            error = error,
            output = output,
            onSelectNode = { chosen = it },
            onSelectService = { service = it },
            onSelectSince = { since = it },
            onShowLogs = { load { id -> api.nodeLogs(id, current.second, current.third, TAIL) } },
            onRunDiagnostics = { load { id -> api.nodeDiagnostics(id) } },
        )
    }

    private companion object {
        val SINCE = listOf("15m", "1h", "6h", "24h")
        const val TAIL = 2000
    }
}

/** The installed certificate of [node], its dates, and what it serves on its own and the shared names. */
internal fun certificateFacts(status: StatusSnapshot, node: String): List<homegap.presentation.ui.FactRow> {
    val certs = status.certificates ?: return emptyList()
    val delivery = certs.delivery.firstOrNull { it.node == node } ?: return emptyList()
    val issued = certs.issued
    val head = listOfNotNull(
        homegap.presentation.ui.FactRow("installed", delivery.installed ?: delivery.state, mono = true),
        issued?.takeIf { delivery.state == "ok" }?.let { homegap.presentation.ui.FactRow("valid", "${dayOf(it.notBefore)} → ${dayOf(it.notAfter)}", mono = true) },
    )
    val mine = certs.probes.filter { row -> row.group != "management" || row.name == status.nodes.firstOrNull { it.id == node }?.managementHost }
    return head + mine.mapNotNull { row ->
        val cell = row.cells.firstOrNull { it.node == node } ?: return@mapNotNull null
        val text = when (cell.state) {
            "default" -> "default certificate"
            "none" -> "no certificate"
            "unknown" -> "not probed yet"
            "mismatch" -> "does not cover the name"
            else -> "${cell.daysRemaining ?: "?"} days"
        } + if (row.kind == "homelab") " · held by the homelab" else ""
        homegap.presentation.ui.FactRow(row.name, text, mono = true)
    }
}

/** A date as yyyy-mm-dd from epoch seconds, without a time zone library. */
private fun dayOf(seconds: Long): String {
    val days = seconds / 86_400
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
