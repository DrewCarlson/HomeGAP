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


private val DISABLED = BannerModel(
    BannerKind.Info,
    "Speed tests are disabled. Set speedtest.enabled: true in the config, route the speedtest hostnames to the " +
            "orchestrator's speed test port in your homelab proxy, and restart the orchestrator.",
)

private const val DEVICE_HELP = "DNS picks the node for the device. Anyone with the link can test until it expires."

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
        model.live?.let { LiveStage(it) }
        Div({ classes("speed-cards") }) {
            model.nodeTests.forEach { test ->
                key(test.nodeId) {
                    NodeCard(
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

/** The last result of one node: its two rates as figures over thin meters, then ping and the scale. */
@Composable
private fun NodeCard(test: NodeTestModel, canStart: Boolean, onRun: () -> Unit) {
    val metrics = test.metrics
    Div({
        classes("speed-card")
        if (test.running) classes("running")
        if (test.failed) classes("failed")
    }) {
        Div({ classes("speed-card-head") }) {
            Div({ classes("speed-card-name") }) {
                B { Text(test.nodeId) }
                Div({
                    classes("muted", "small")
                    test.note?.let { title(it) }
                }) {
                    Text(test.summary ?: "No result yet")
                }
            }
            ActionButton(
                text = test.buttonText,
                small = true,
                enabled = canStart,
                onClick = onRun,
            )
        }
        Div({ classes("speed-figures") }) {
            SpeedFigure(
                label = "Down",
                value = metrics?.get(2)?.value,
                fraction = test.downFraction,
                className = "bar-down",
            )
            SpeedFigure(
                label = "Up",
                value = metrics?.get(3)?.value,
                fraction = test.upFraction,
                className = if (test.slow) "bar-slow" else "bar-up",
            )
        }
        if (metrics != null) {
            Div({ classes("speed-card-foot", "mono", "small", "muted") }) {
                Span { Text("Ping ${metrics[0].value} · ±${metrics[1].value}") }
                Span { Text("Scale ${test.scale}") }
            }
        }
    }
}

/** One rate: its label, the number with its unit, and a meter that slides to [fraction]. */
@Composable
private fun SpeedFigure(label: String, value: String?, fraction: Double, className: String) {
    Div({ classes("speed-figure") }) {
        Div({ classes("metric-label") }) { Text(label) }
        // A new value is a new element, so it fades in.
        key(value) {
            Div({ classes("speed-number") }) {
                Text(value?.substringBefore(' ') ?: "—")
                value?.substringAfter(' ', "")?.takeIf { it.isNotEmpty() }?.let { unit ->
                    Span({ classes("speed-unit") }) { Text(" $unit") }
                }
            }
        }
        Div({ classes("speed-track") }) {
            Div({
                classes("speed-fill", className)
                style { property("width", "${fraction * 100}%") }
            })
        }
    }
}

/**
 * The test that runs: the gauge with the number of the current phase, the three phases with their
 * results, and one bar for each sample. The arc, the number and the bars move in CSS, from the
 * last value to the new one.
 */
@Composable
private fun LiveStage(live: LiveTestModel) {
    Div({ classes("live-stage", live.tone.className) }) {
        Div({
            classes("gauge")
            if (live.starting) {
                classes("starting")
            }
            attr("role", "img")
            attr("aria-label", "${live.phase}: ${live.value} ${live.unit}")
            style { property("--p", live.fraction.toString()) }
        }) {
            Div({ classes("gauge-arc") })
            Div({ classes("gauge-text") }) {
                Div({
                    classes("gauge-value")
                    if (!live.hasValue) classes("empty")
                    if (live.tenths != null) classes("tenths")
                    style {
                        property("--whole", live.whole.toString())
                        property("--tenths", (live.tenths ?: 0).toString())
                    }
                })
                Div({ classes("gauge-unit") }) { Text(live.unit) }
            }
            Div({ classes("gauge-scale") }) {
                Span { Text("0") }
                Span { Text(live.scaleMax) }
            }
        }
        Div({ classes("live-side") }) {
            Div({ classes("live-title") }) { Text(live.title) }
            Ol({ classes("live-phases") }) {
                live.phases.forEach { phase ->
                    Li({ classes(phase.state.name.lowercase()) }) {
                        Span({ classes("live-phase-mark") })
                        Span({ classes("live-phase-name") }) { Text(phase.label) }
                        Span({ classes("live-phase-value", "mono") }) { Text(phase.value.ifEmpty { "—" }) }
                    }
                }
            }
            Div({
                classes("live-strip")
                attr("aria-hidden", "true")
            }) {
                live.bars.forEachIndexed { index, height ->
                    // A bar keeps its number, so only a new bar rises.
                    key(live.phase, live.barsStart + index) {
                        Span({ style { property("height", "${(height * 100).coerceAtLeast(4.0)}%") } })
                    }
                }
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
