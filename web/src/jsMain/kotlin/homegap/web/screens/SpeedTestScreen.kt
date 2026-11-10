package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.app.BannerKind
import homegap.presentation.app.BannerModel
import homegap.presentation.speedtest.*
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
            if (!model.map.isEmpty) {
                Panel(
                    title = "Map",
                    subtitle = "Latest homelab ↔ node results on each line; click a node to test it"
                ) {
                    WorldMap(
                        model = model.map,
                        heightPx = 340,
                        onMarkerClick = { id ->
                            if (id != "homelab" && model.canStart) {
                                model.onRunTest(id)
                            }
                        }
                    )
                }
            }
            Div({ classes("two-col") }) {
                TunnelTests(model)
                DeviceTests(model.device)
            }
            History(model.history)
        }
    }
}

@Composable
private fun TunnelTests(model: SpeedTestScreenModel.Ready) {
    Panel("Homelab ↔ node", TUNNEL_PATH, flush = false) {
        Div({ classes("stream-picker") }) {
            Span({ classes("muted") }) { Text("Parallel streams") }
            Segmented(
                options = model.streamChoices,
                selected = model.streams,
                label = { "$it" },
                enabled = model.canStart,
                onSelect = model.onSelectStreams
            )
        }
        Div({ classes("node-tests") }) {
            model.nodeTests.forEach { test ->
                key(test.nodeId) {
                    NodeTest(test, model.canStart) { model.onRunTest(test.nodeId) }
                }
            }
        }
    }
}

@Composable
private fun NodeTest(test: NodeTestModel, canStart: Boolean, onRun: () -> Unit) {
    Div({ classes("node-test") }) {
        Div({ classes("node-test-head") }) {
            B { Text(test.nodeId) }
            ActionButton(test.buttonText, small = true, enabled = canStart, onClick = onRun)
        }
        when (val metrics = test.metrics) {
            null -> Muted("No result yet")
            else -> {
                Div({ classes("metrics") }) {
                    metrics.forEach { Metric(it) }
                }
            }
        }
        test.note?.let { Muted(it) }
    }
}

@Composable
private fun DeviceTests(device: DeviceTestModel) {
    Panel(
        title = "Your device → node → homelab",
        subtitle = "Open a link on the device you want to test",
        flush = false
    ) {
        P({ classes("muted") }) { Text(DEVICE_HELP) }
        ActionButton(
            text = device.buttonText,
            style = ButtonStyle.Primary,
            onClick = device.onCreateLink
        )
        if (device.links.isNotEmpty()) {
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
    Panel("History") {
        val headers = listOf("When", "Path", "Node", "Ping", "Jitter", "Down", "Up", "Notes")
        DataTable(headers, "No results yet.", rows.isEmpty()) {
            rows.forEach { row ->
                key(row.id) {
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
                        Cell("Notes") {
                            row.error?.let { ErrorText(it, mono = false) }
                            row.client?.let { Muted(it) }
                        }
                    }
                }
            }
        }
    }
}
