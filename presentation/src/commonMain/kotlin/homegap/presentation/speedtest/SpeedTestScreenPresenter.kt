package homegap.presentation.speedtest

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.SpeedTestLinkDto
import homegap.api.SpeedTestOverview
import homegap.api.SpeedTestLiveDto
import homegap.api.SpeedTestResultDto
import homegap.presentation.core.Presenter
import homegap.presentation.core.rememberEventTrigger
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.ControllerApi
import homegap.presentation.format.*
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ChartTone
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.StatusLineModel
import homegap.presentation.ui.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class SpeedTestScreenProps(val now: Long, val status: homegap.api.StatusSnapshot? = null)

@SingleIn(AppScope::class)
@Inject
class SpeedTestScreenPresenter(
    private val api: ControllerApi,
    private val session: homegap.presentation.data.ControllerSession,
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

        // A "test all nodes" run lives on the orchestrator; its progress comes with the status.
        val run = props.status?.speedRun
        val runCurrent = run?.current
        val allProgress = runCurrent?.let { (run.done.size + 1) to run.nodes.size }
        val active = running ?: runCurrent ?: session.speedLive.collectAsState().value?.nodeId
        LaunchedEffect(run?.done?.size, run?.finishedAt) {
            // Each finished node adds a result.
            reload++
        }

        // One test; on the app scope, since a test takes ~30 s and should finish even if the user
        // navigates away. Returns the result's error, or null.
        suspend fun runOne(nodeId: String, notify: Boolean): String? {
            running = nodeId
            try {
                val result = runCatchingCancellable { api.runTunnelTest(nodeId, currentStreams) }.getOrElse { e ->
                    actions.notify("Speed test on $nodeId failed: ${e.message}", isError = true)
                    return "${e.message}"
                }
                val error = result.error
                if (error != null) actions.notify("$nodeId: $error", isError = true)
                else if (notify) actions.notify("$nodeId: ${result.summary()}")
                return error
            } finally {
                running = null
                reload++
            }
        }
        val onRunTest = remember<(String) -> Unit> {
            { nodeId -> appScope.launch { runOne(nodeId, notify = true) } }
        }
        val onRunAll = remember<() -> Unit> {
            { actions.run("Test all nodes") { api.startSpeedRun(currentStreams) } }
        }
        val onCancelAll = remember<() -> Unit> {
            { actions.run("Stop the test") { api.cancelSpeedRun() } }
        }
        // The running test as the speed test origin sees it, pushed a few times a second.
        val live by session.speedLive.collectAsState()
        val createLink = appScope.rememberEventTrigger {
            runCatchingCancellable { api.speedLink() }
                .onSuccess { link = it }
                .onFailure { actions.notify("Link failed: ${it.message}", isError = true) }
        }

        val latest = current.nodes.mapNotNull { id -> current.results.firstOrNull { it.kind == "tunnel" && it.nodeId == id && it.error == null } }
        val fastest = latest.filter { it.upMbps != null }.maxByOrNull { it.upMbps ?: 0.0 }
        val slowest = latest.filter { it.upMbps != null }.minByOrNull { it.upMbps ?: 0.0 }
        return SpeedTestScreenModel.Ready(
            head = PageHeadModel(
                caption = "Speed test · homelab and device throughput",
                headline = fastest?.let { "Fastest upload: ${it.nodeId}, ${formatMbps(it.upMbps)}." } ?: "No speed tests yet.",
                statusLine = slowest?.takeIf { it != fastest && (it.upMbps ?: 0.0) < (fastest?.upMbps ?: 0.0) / 2 }?.let { slow ->
                    StatusLineModel(
                        listOf(
                            StatusMessage(
                                severity = Severity.Warn,
                                text = "Slowest: ${slow.nodeId}, ${formatMbps(slow.upMbps)}.",
                                location = Location(Route.Nodes, slow.nodeId.orEmpty()),
                            )
                        )
                    )
                },
            ),
            streamChoices = STREAM_CHOICES,
            streams = streams,
            canStart = active == null,
            onRunAll = onRunAll,
            onCancelAll = onCancelAll.takeIf { runCurrent != null },
            allButtonText = allProgress?.let { (i, n) -> "Testing $i of $n…" } ?: "Test all nodes",
            live = active?.let { liveTestModel(it, live?.takeIf { l -> l.nodeId == it }, allProgress) },
            nodeTests = remember(current, active, props.now) { nodeTests(current, active, props.now) },
            device = DeviceTestModel(
                buttonText = if (link == null) "Create test link" else "Create new link",
                links = link?.toLinks().orEmpty(),
                expires = link?.let { "Expires ${timeFormatter.dateTime(it.expiresAt)}" },
                onCreateLink = createLink::trigger,
            ),
            history = remember(current.results, props.now) { current.results.map { it.toHistoryRow(props.now) } },
            onSelectStreams = remember { { chosenStreams = it } },
            onRunTest = onRunTest,
            map = remember(current, props.status, active, props.now / 60_000) { testMap(current, props.status, active, props.now) },
        )
    }

    /**
     * The homelab and the nodes, each line labeled with the latest homelab ↔ node result; a node's
     * card also shows the latest device test through it.
     */
    private fun testMap(overview: SpeedTestOverview, status: homegap.api.StatusSnapshot?, running: String?, now: Long): homegap.presentation.map.MapModel {
        if (status == null) return homegap.presentation.map.MapModel()
        val home = status.homelab
        val markers = status.nodes.filter { it.id in overview.nodes }.mapNotNull { n ->
            val loc = n.location ?: return@mapNotNull null
            val tunnel = overview.results.firstOrNull { it.kind == "tunnel" && it.nodeId == n.id }
            val device = overview.results.firstOrNull { it.kind != "tunnel" && it.nodeId == n.id }
            val rows = buildList {
                if (running == n.id) add(homegap.presentation.map.MapInfoRow("Test", "running…"))
                tunnel?.let {
                    add(homegap.presentation.map.MapInfoRow("Homelab ↔ node", ago(it.at, now)))
                    add(homegap.presentation.map.MapInfoRow("Ping", formatMillis(it.pingMs)))
                    add(homegap.presentation.map.MapInfoRow("Down / up", "${formatMbps(it.downMbps)} / ${formatMbps(it.upMbps)}"))
                    it.error?.let { e -> add(homegap.presentation.map.MapInfoRow("Error", e.take(48), homegap.api.Health.DOWN)) }
                }
                device?.let {
                    add(homegap.presentation.map.MapInfoRow("Device test", ago(it.at, now)))
                    add(homegap.presentation.map.MapInfoRow("Device down / up", "${formatMbps(it.downMbps)} / ${formatMbps(it.upMbps)}"))
                }
                if (tunnel == null && device == null) add(homegap.presentation.map.MapInfoRow("Results", "none yet"))
            }
            homegap.presentation.map.MapMarker(
                n.id, loc.latitude, loc.longitude, n.health, label = n.id, highlight = running == n.id,
                info = homegap.presentation.map.MapInfo(n.id, "Click to run a homelab ↔ node test", rows),
            )
        }
        val lines = if (home == null) emptyList() else status.nodes.filter { it.id in overview.nodes }.mapNotNull { n ->
            val loc = n.location ?: return@mapNotNull null
            val tunnel = overview.results.firstOrNull { it.kind == "tunnel" && it.nodeId == n.id }
            val health = when {
                tunnel == null -> homegap.api.Health.UNKNOWN
                tunnel.error != null -> homegap.api.Health.DOWN
                else -> homegap.api.Health.OK
            }
            val label = tunnel?.takeIf { it.error == null }?.let { "${formatMillis(it.pingMs)} · ${formatMbps(it.downMbps)}" }
            homegap.presentation.map.MapLine("st-${n.id}", homegap.presentation.map.MapPoint(home.latitude, home.longitude), homegap.presentation.map.MapPoint(loc.latitude, loc.longitude), health, label)
        }
        return homegap.presentation.map.MapModel(listOfNotNull(home?.let { homegap.presentation.map.homelabMarker(it) }) + markers, lines)
    }

    private fun nodeTests(overview: SpeedTestOverview, running: String?, now: Long): List<NodeTestModel> {
        val fastestUp = overview.results.filter { it.kind == "tunnel" && it.error == null }.mapNotNull { it.upMbps }.maxOrNull() ?: 0.0
        return overview.nodes.map { nodeId ->
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
                downFraction = ((latest?.downMbps ?: 0.0) / BAR_SCALE_MBPS).coerceIn(0.0, 1.0),
                upFraction = ((latest?.upMbps ?: 0.0) / BAR_SCALE_MBPS).coerceIn(0.0, 1.0),
                slow = latest?.upMbps?.let { it < fastestUp / 2 } == true,
                failed = latest?.error != null,
            )
        }
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

        /** The bars' full scale: 1 Gb/s. */
        const val BAR_SCALE_MBPS = 1000.0
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

private val GAUGE_STEPS = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)

/** The gauge's full scale: the first step above [v] with a little room. */
internal fun gaugeScale(v: Double): Double = GAUGE_STEPS.firstOrNull { it >= v * 1.1 } ?: GAUGE_STEPS.last()

/** The gauge and bars for [nodeId]'s running test; before its first figures, an empty gauge. */
internal fun liveTestModel(nodeId: String, live: SpeedTestLiveDto?, all: Pair<Int, Int>?): LiveTestModel {
    val title = "Testing $nodeId" + (all?.let { (i, n) -> " ($i of $n)" } ?: "")
    val ping = live?.phase == "ping"
    val samples = if (live == null) emptyList() else if (ping) live.pingSamples else live.mbpsSamples
    val value = if (live == null) null else if (ping) live.pingSamples.lastOrNull() else live.mbps
    val scale = gaugeScale(samples.maxOrNull() ?: value ?: 0.0)
    val top = samples.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    return LiveTestModel(
        title = title,
        phase = when (live?.phase) {
            null -> "Starting…"
            "ping" -> "Ping"
            "download" -> "Download"
            else -> "Upload"
        },
        value = value?.let { if (ping) formatMillis(it).removeSuffix(" ms") else formatMbps(it).removeSuffix(" Mb/s") } ?: "—",
        unit = if (ping || live == null) "ms" else "Mb/s",
        fraction = ((value ?: 0.0) / scale).coerceIn(0.0, 1.0),
        scaleMax = "${scale.toInt()} ${if (ping || live == null) "ms" else "Mb/s"}",
        tone = when (live?.phase) {
            "download" -> ChartTone.Cyan
            "upload" -> ChartTone.Violet
            else -> ChartTone.White
        },
        bars = samples.takeLast(48).map { it / top },
        metrics = listOf(
            MetricModel("Ping", formatMillis(live?.pingMs)),
            MetricModel("Jitter", formatMillis(live?.jitterMs)),
            MetricModel("Down", formatMbps(if (live?.phase == "download") live.mbps else live?.downMbps)),
            MetricModel("Up", formatMbps(if (live?.phase == "upload") live.mbps else null)),
        ),
    )
}
