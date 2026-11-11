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
