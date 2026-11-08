package homegap.presentation.speedtest

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.SpeedTestLinkDto
import homegap.api.SpeedTestOverview
import homegap.api.SpeedTestResultDto
import homegap.presentation.core.Presenter
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.ControllerApi
import homegap.presentation.format.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class SpeedTestScreenProps(val now: Long)

@SingleIn(AppScope::class)
@Inject
class SpeedTestScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val timeFormatter: TimeFormatter,
    @AppCoroutineScope private val appScope: CoroutineScope,
) : Presenter<SpeedTestScreenProps, SpeedTestScreenModel> {
    @Composable
    override fun model(props: SpeedTestScreenProps): SpeedTestScreenModel {
        // Bumped after a test so the overview reloads at once instead of on the next refresh.
        var reload by remember { mutableIntStateOf(0) }
        val overview by produceState<SpeedTestOverview?>(null, reload) {
            while (true) {
                runCatchingCancellable { api.speedtest() }.onSuccess { value = it }
                delay(REFRESH)
            }
        }
        var chosenStreams by remember { mutableStateOf<Int?>(null) }
        var running by remember { mutableStateOf<String?>(null) }
        var link by remember { mutableStateOf<SpeedTestLinkDto?>(null) }

        val current = overview ?: return SpeedTestScreenModel.Loading
        if (!current.enabled) return SpeedTestScreenModel.Disabled
        val streams = chosenStreams ?: current.streams
        val currentStreams by rememberUpdatedState(streams)

        val onRunTest = remember<(String) -> Unit> {
            { nodeId ->
                // On the app scope: a test takes ~30 s and should finish even if the user navigates away.
                running = nodeId
                appScope.launch {
                    try {
                        runCatchingCancellable { api.runTunnelTest(nodeId, currentStreams) }
                            .onSuccess { result ->
                                val error = result.error
                                if (error != null) actions.notify("$nodeId: $error", isError = true)
                                else actions.notify("$nodeId: ${result.summary()}")
                            }
                            .onFailure { actions.notify("Speed test on $nodeId failed: ${it.message}", isError = true) }
                    } finally {
                        running = null
                        reload++
                    }
                }
            }
        }
        val createLink = appScope.rememberEventTrigger {
            runCatchingCancellable { api.speedLink() }
                .onSuccess { link = it }
                .onFailure { actions.notify("Link failed: ${it.message}", isError = true) }
        }

        return SpeedTestScreenModel.Ready(
            streamChoices = STREAM_CHOICES,
            streams = streams,
            canStart = running == null,
            nodeTests = remember(current, running, props.now) { nodeTests(current, running, props.now) },
            device = DeviceTestModel(
                buttonText = if (link == null) "Create test link" else "Create new link",
                links = link?.toLinks().orEmpty(),
                expires = link?.let { "Expires ${timeFormatter.dateTime(it.expiresAt)}" },
                onCreateLink = createLink::trigger,
            ),
            history = remember(current.results, props.now) { current.results.map { it.toHistoryRow(props.now) } },
            onSelectStreams = remember { { chosenStreams = it } },
            onRunTest = onRunTest,
        )
    }

    private fun nodeTests(overview: SpeedTestOverview, running: String?, now: Long): List<NodeTestModel> =
        overview.nodes.map { nodeId ->
            val latest = overview.results.firstOrNull { it.kind == "tunnel" && it.nodeId == nodeId }
            NodeTestModel(
                nodeId = nodeId,
                buttonText = if (running == nodeId) "Testing…" else "Run test",
                metrics = latest?.let {
                    listOf(
                        MetricModel("Ping", formatMillis(it.pingMs)),
                        MetricModel("Jitter", formatMillis(it.jitterMs)),
                        MetricModel("Down", formatMbps(it.downMbps)),
                        MetricModel("Up", formatMbps(it.upMbps)),
                    )
                },
                note = latest?.let { ago(it.at, now) + it.streamsNote() + it.error?.let { e -> " · $e" }.orEmpty() },
            )
        }

    private fun SpeedTestLinkDto.toLinks(): List<TestLinkModel> =
        listOf(TestLinkModel("Auto (DNS)", autoUrl)) + nodeUrls.map { (nodeId, url) -> TestLinkModel(nodeId, url) }

    private fun SpeedTestResultDto.toHistoryRow(now: Long) = HistoryRowModel(
        id = id,
        time = ago(at, now),
        path = if (kind == "tunnel") "homelab ↔ node" else "device",
        streams = streams?.takeIf { it > 1 }?.let { "$it streams" },
        node = (nodeId ?: "unknown") + if (auto) " (auto)" else "",
        ping = formatMillis(pingMs),
        jitter = formatMillis(jitterMs),
        down = formatMbps(downMbps),
        up = formatMbps(upMbps),
        error = error,
        client = client?.let(::shortClient),
    )

    private companion object {
        val STREAM_CHOICES = listOf(1, 4, 8)
        val REFRESH = 10.seconds

        fun SpeedTestResultDto.summary(): String =
            "${formatMbps(downMbps)} down, ${formatMbps(upMbps)} up, ${formatMillis(pingMs)} ping"

        /** " · 4 streams (down 62/60/58/61)" for multi-stream tunnel results, "" for device results. */
        fun SpeedTestResultDto.streamsNote(): String {
            val count = streams ?: return ""
            if (count <= 1) return " · 1 stream"
            val perStream = downStreamsMbps.takeIf { it.size > 1 }?.joinToString("/") { it.oneDecimal() }?.let { " (down $it)" }.orEmpty()
            return " · $count streams$perStream"
        }
    }
}
