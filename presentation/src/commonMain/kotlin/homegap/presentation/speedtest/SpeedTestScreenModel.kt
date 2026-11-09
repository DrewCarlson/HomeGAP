package homegap.presentation.speedtest

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel

sealed interface SpeedTestScreenModel : ScreenModel {
    data object Loading : SpeedTestScreenModel

    data object Disabled : SpeedTestScreenModel

    @Immutable
    data class Ready(
        val streamChoices: List<Int>,
        val streams: Int,
        /** No test is running, so a new one (or another stream count) may start. */
        val canStart: Boolean,
        val nodeTests: List<NodeTestModel>,
        val device: DeviceTestModel,
        val history: List<HistoryRowModel>,
        val onSelectStreams: (Int) -> Unit,
        val onRunTest: (nodeId: String) -> Unit,
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
)
