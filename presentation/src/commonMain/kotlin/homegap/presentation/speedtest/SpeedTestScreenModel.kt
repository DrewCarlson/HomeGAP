package homegap.presentation.speedtest

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel

sealed interface SpeedTestScreenModel : ScreenModel {
    data object Loading : SpeedTestScreenModel

    data object Disabled : SpeedTestScreenModel

    @Immutable
    data class Ready(
        override val head: homegap.presentation.ui.PageHeadModel,
        val streamChoices: List<Int>,
        val streams: Int,
        /** No test is running, so a new one (or another stream count) may start. */
        val canStart: Boolean,
        val nodeTests: List<NodeTestModel>,
        val device: DeviceTestModel,
        val history: List<HistoryRowModel>,
        val onSelectStreams: (Int) -> Unit,
        val onRunTest: (nodeId: String) -> Unit,
        /** Runs every node's test in turn. */
        val onRunAll: () -> Unit = {},
        /** "Test all nodes", or how far a run of them has got. */
        val allButtonText: String = "Test all nodes",
        /** Stops a run of all nodes; null when none runs. */
        val onCancelAll: (() -> Unit)? = null,
        /** The running test as it goes; null when none runs. */
        val live: LiveTestModel? = null,
        /** The homelab and the nodes with their latest results; empty without locations. */
        val map: homegap.presentation.map.MapModel = homegap.presentation.map.MapModel(),
    ) : SpeedTestScreenModel
}

/** Homelab ↔ node test card. */
@Immutable
data class NodeTestModel(
    val nodeId: String,
    val buttonText: String,
    /** Ping, jitter, down, up; null before the first result. */
    val metrics: List<MetricModel>?,
    val note: String?,
    /** Download and upload as shares of the scale of this node, for the bars. 0 before the first result. */
    val downFraction: Double = 0.0,
    val upFraction: Double = 0.0,
    /** The full scale of the bars of this node: "1 Gb/s". */
    val scale: String = "",
    /** The upload is under half of the fastest node's. */
    val slow: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The running test: a gauge for the current phase (ping in ms, then download and upload in
 * Mbps) on a scale that only grows, and one bar per sample.
 */
@Immutable
data class LiveTestModel(
    val title: String,
    val phase: String,
    /** The test has no figures yet: the gauge shows that it works. */
    val starting: Boolean = false,
    val value: String,
    val unit: String,
    /** How full the gauge is, 0..1. */
    val fraction: Double,
    val scaleMax: String,
    /** Bar heights, 0..1. */
    val bars: List<Double>,
    val metrics: List<MetricModel>,
    /** White for ping, cyan for download, violet for upload. */
    val tone: homegap.presentation.ui.ChartTone = homegap.presentation.ui.ChartTone.White,
)

@Immutable
data class MetricModel(val label: String, val value: String)

/** Device → node → homelab links. */
@Immutable
data class DeviceTestModel(
    val buttonText: String,
    val links: List<TestLinkModel>,
    val expires: String?,
    val onCreateLink: () -> Unit,
)

@Immutable
data class TestLinkModel(val label: String, val url: String) {
    /** The token lives in the fragment: show only the address. */
    val displayUrl: String get() = url.substringBefore("#")
}

@Immutable
data class HistoryRowModel(
    val id: String,
    val time: String,
    val path: String,
    val streams: String?,
    val node: String,
    val ping: String,
    val jitter: String,
    val down: String,
    val up: String,
    val error: String?,
    val client: String?,
    /** How busy the CPU of the node, and of the homelab, was in each phase of a homelab to node test. */
    val cpu: String? = null,
    val homelabCpu: String? = null,
)
