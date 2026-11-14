package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.app.BannerKind
import homegap.presentation.app.BannerModel
import homegap.presentation.speedtest.*
import homegap.presentation.ui.QrCode
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.*

private const val TUNNEL_PATH = "curl on the node → its own :443 → WireGuard tunnel → homelab proxy"

private val DISABLED = BannerModel(
    BannerKind.Info,
    "Speed tests are disabled. Set speedtest.enabled: true in the config, route the speedtest hostnames to the " +
            "orchestrator's speed test port in your homelab proxy, and restart the orchestrator.",
)

private const val DEVICE_HELP = "The auto link lets DNS pick the node for the device's location; that node " +
        "answers it and tells the page which node it is, so the result is attributed to it. Anyone holding a link " +
        "can run tests until it expires."

@Composable
fun SpeedTestScreen(model: SpeedTestScreenModel) {
    when (model) {
        SpeedTestScreenModel.Disabled -> Banner(DISABLED)
        SpeedTestScreenModel.Loading -> {
            Div({ classes("loading") }) {
                Text("Loading…")
            }
        }

        is SpeedTestScreenModel.Ready -> {
            TunnelTests(model)
            DeviceTests(model.device)
            History(model.history)
            if (!model.map.isEmpty) {
                Figure(title = "Map", note = "Select a node to test it.") {
                    WorldMap(
                        model = model.map,
                        heightPx = 340,
                        onMarkerClick = { id ->
                            if (id != "homelab" && model.canStart) {
                                model.onRunTest(id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TunnelTests(model: SpeedTestScreenModel.Ready) {
    Figure(
        title = "Homelab to node",
        flush = true,
        actions = {
            Span({ classes("muted", "small", "wide-only") }) { Text("Parallel streams") }
            Segmented(
                options = model.streamChoices,
                selected = model.streams,
                label = { "$it" },
                enabled = model.canStart,
                onSelect = model.onSelectStreams,
            )
            ActionButton(
                text = model.allButtonText,
                style = ButtonStyle.Primary,
                small = true,
                enabled = model.canStart,
                onClick = model.onRunAll,
            )
            model.onCancelAll?.let { cancel ->
                ActionButton(
                    text = "Stop",
                    small = true,
                    tooltip = "Stop after the node being tested now",
                    onClick = cancel,
                )
            }
        },
    ) {
        model.live?.let { LiveTest(it) }
        Div({ classes("speed-rows") }) {
            model.nodeTests.forEach { test ->
                key(test.nodeId) {
                    SpeedRow(
                        test = test,
                        canStart = model.canStart,
                        onRun = { model.onRunTest(test.nodeId) },
                    )
                }
            }
        }
        P({ classes("figure-note") }) {
            Text("Up is homelab to node, the direction of a download.")
        }
    }
}

@Composable
private fun SpeedRow(test: NodeTestModel, canStart: Boolean, onRun: () -> Unit) {
    val metrics = test.metrics
    Div({ classes("speed-row") }) {
        Div {
            B { Text(test.nodeId) }
            Div({ classes("muted", "mono", "small") }) { Text(test.note ?: "no result yet") }
        }
        Div({ classes("speed-bars") }) {
            SpeedBar(label = "down", fraction = test.downFraction, className = "bar-down")
            SpeedBar(label = "up", fraction = test.upFraction, className = if (test.slow) "bar-slow" else "bar-up")
            if (metrics != null) {
                Div({ classes("muted", "mono", "small", "speed-scale") }) { Text("of ${test.scale}") }
            }
        }
        Div({ classes("mono", "speed-values") }) {
            if (metrics != null) {
                Div { Text(metrics[2].value) }
                Div({ classes(if (test.slow) "tone-warn" else "muted") }) { Text(metrics[3].value) }
            } else {
                Div({ classes("muted") }) { Text("—") }
            }
        }
        Div({ classes("mono", "muted", "small", "speed-ping") }) {
            if (metrics != null) {
                Text("${metrics[0].value} · ±${metrics[1].value}")
            }
        }
        ActionButton(
            text = test.buttonText,
            small = true,
            enabled = canStart,
            onClick = onRun,
        )
    }
}

@Composable
private fun SpeedBar(label: String, fraction: Double, className: String) {
    Div({ classes("speed-bar") }) {
        Span({ classes("muted", "mono", "small") }) { Text(label) }
        Div({ classes("speed-track") }) {
            Div({
                classes("speed-fill", className)
                style { property("width", "${(fraction * 100).coerceAtLeast(0.5)}%") }
            })
        }
    }
}

@Composable
private fun LiveTest(live: LiveTestModel) {
    Div({ classes("live-test", live.tone.className) }) {
        Div({ classes("gauge-box") }) {
            Div({
                classes("gauge", live.tone.className)
                if (live.starting) {
                    classes("starting")
                }
                style { property("--p", live.fraction.toString()) }
            }) {
                Div({ classes("gauge-arc") })
                Div({ classes("gauge-text") }) {
                    Div({ classes("gauge-value") }) { Text(live.value) }
                    Div({ classes("gauge-unit") }) { Text(live.unit) }
                }
            }
            Div({ classes("gauge-scale") }) {
                Span { Text("0") }
                Span { Text(live.scaleMax) }
            }
        }
        Div({ classes("live-side") }) {
            Div({ classes("live-phase") }) { Text("${live.title} · ${live.phase}") }
            Div({ classes("bars") }) {
                live.bars.forEach { h ->
                    Span({ style { property("height", "${(h * 100).coerceAtLeast(4.0)}%") } })
                }
            }
            Div({ classes("metrics") }) {
                live.metrics.forEach { Metric(it) }
            }
        }
    }
}


@Composable
private fun DeviceTests(device: DeviceTestModel) {
    Figure(title = "Your device", note = "Open the link on the device.") {
        P({ classes("muted") }) { Text(DEVICE_HELP) }
        ActionButton(
            text = device.buttonText,
            style = ButtonStyle.Primary,
            onClick = device.onCreateLink
        )
        if (device.links.isNotEmpty()) {
            device.links.firstOrNull()?.let { auto ->
                QrCode.encode(auto.url)?.let { code ->
                    Div({ classes("app-address") }) {
                        QrImage(
                            code = code,
                            label = "QR code for the automatic test link",
                        )
                        P({ classes("muted") }) { Text("Scan from a phone to test through the node that DNS picks for it.") }
                    }
                }
            }
            Div({ classes("links") }) {
                device.links.forEach { LinkRow(it) }
                device.expires?.let { Muted(it) }
            }
        }
    }
}

@Composable
private fun LinkRow(link: TestLinkModel) {
    Div({ classes("link-row") }) {
        Span({ classes("link-label") }) { Text(link.label) }
        A(
            href = link.url,
            attrs = {
                classes("mono")
                target(ATarget.Blank)
                attr("rel", "noopener")
            }
        ) {
            Text(link.displayUrl)
        }
        ActionButton("Copy", small = true) {
            window.navigator.clipboard.writeText(link.url)
        }
    }
}

@Composable
private fun History(rows: List<HistoryRowModel>) {
    Figure(
        title = "History",
        flush = true,
    ) {
        val headers = listOf("When", "Path", "Node", "Ping", "Jitter", "Down", "Up")
        DataTable(headers, "No results yet.", rows.isEmpty()) {
            rows.forEach { row ->
                key(row.id) {
                    val notes = listOfNotNull(row.client, row.cpu, row.homelabCpu)
                    Tr {
                        Cell("When", "nowrap") { Text(row.time) }
                        Cell("Path") {
                            Tag(row.path)
                            row.streams?.let { Muted(it) }
                        }
                        Cell("Node") { Text(row.node) }
                        Cell("Ping", "mono") { Text(row.ping) }
                        Cell("Jitter", "mono") { Text(row.jitter) }
                        Cell("Down", "mono") { Text(row.down) }
                        Cell("Up", "mono") { Text(row.up) }
                    }
                    // The notes are long, so they get the full width under the row and not a column.
                    if (row.error != null || notes.isNotEmpty()) {
                        Tr({ classes("note-row") }) {
                            Td({ attr("colspan", headers.size.toString()) }) {
                                row.error?.let { ErrorText(it, mono = false) }
                                notes.forEach { Muted(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}
