package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import homegap.presentation.diagnostics.AnsiColor
import homegap.presentation.diagnostics.AnsiText
import homegap.presentation.diagnostics.DiagnosticsScreenModel
import homegap.web.ui.*
import kotlinx.browser.document
import org.jetbrains.compose.web.css.Color
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun DiagnosticsScreen(model: DiagnosticsScreenModel) {
    Figure(
        caption = "Fig. 01 · Node · read-only, nothing on the node changes",
        flush = true,
    ) {
        Div({ classes("figure-body") }) {
            if (model.nodes.isEmpty()) {
                Muted("No nodes yet.")
                return@Div
            }
            Div({ classes("inline-row") }) {
                Muted("Node")
                Segmented(
                    model.nodes,
                    selected = model.node.orEmpty(),
                    label = { it },
                    enabled = !model.loading,
                    onSelect = model.onSelectNode
                )
            }
            Div({ classes("inline-row") }) {
                Muted("Container")
                Select({
                    classes("select")
                    attr("aria-label", "Container")
                    if (model.loading) {
                        disabled()
                    }
                    onChange { event -> event.value?.let(model.onSelectService) }
                }) {
                    model.services.forEach { (id, label) ->
                        Option(
                            value = id,
                            attrs = {
                                if (model.service == id) {
                                    selected()
                                }
                            },
                        ) {
                            Text(label)
                        }
                    }
                }
            }
            Div({ classes("inline-row") }) {
                Muted("Since")
                Segmented(
                    options = model.sinceChoices,
                    selected = model.since,
                    label = { it },
                    enabled = !model.loading,
                    onSelect = model.onSelectSince
                )
                ActionButton(
                    text = "Show logs",
                    style = ButtonStyle.Primary,
                    enabled = !model.loading && model.node != null,
                    onClick = model.onShowLogs
                )
                ActionButton(
                    text = "Run diagnostics",
                    enabled = !model.loading && model.node != null,
                    tooltip = "Containers, mesh, cluster proxy, firewall, NAT, ports and recent logs",
                    onClick = model.onRunDiagnostics,
                )
            }
            model.error?.let { error ->
                Div({ classes("banner", "error") }) {
                    Text(error)
                }
            }
        }
    }
    if (model.report.isNotEmpty()) {
        Figure(
            caption = "Fig. 02 · Report · ${model.report.count { it.health == homegap.api.Health.DEGRADED }} to look at",
            flush = true,
        ) {
            Div({ classes("report-grid") }) {
                model.report.forEach { section ->
                    Div({ classes("report-cell") }) {
                        Div({ classes("report-title") }) {
                            Span({ classes("mark", section.health.toneClass) }) { Text(section.health.symbol) }
                            Text(section.title)
                        }
                        Div({ classes("muted", "small") }) { Text(section.summary) }
                    }
                }
            }
        }
    }
    val output = model.output
    if (model.loading) {
        Panel("Loading…") { Div({ classes("figure-body") }) { Muted("Asking the node over SSH.") } }
    } else if (output != null) {
        Panel(
            title = "${output.title} · ${output.node}",
            actions = {
                ActionButton("Download", small = true) {
                    val cleanTitle = output.title.substringBefore(' ').lowercase()
                    download(
                        "${output.node}-${cleanTitle}.txt",
                        AnsiText.plain(output.text)
                    )
                }
            }
        ) {
            TerminalText(output.text)
        }
    }
}

/** Log text with the programs' own terminal colours; the text is only ever rendered as text. */
@Composable
private fun TerminalText(text: String) {
    val runs = remember(text) { AnsiText.parse(text) }
    Pre {
        for (run in runs) {
            if (run.plain) {
                Text(run.text)
                continue
            }
            Span({
                (run.fg as? AnsiColor.Indexed)?.let { classes("ansi-fg-${it.index}") }
                (run.bg as? AnsiColor.Indexed)?.let { classes("ansi-bg-${it.index}") }
                if (run.bold) classes("ansi-bold")
                if (run.dim) classes("ansi-dim")
                if (run.italic) classes("ansi-italic")
                if (run.underline) classes("ansi-underline")
                style {
                    (run.fg as? AnsiColor.Rgb)?.let {
                        color(Color(it.css))
                    }
                    (run.bg as? AnsiColor.Rgb)?.let {
                        backgroundColor(Color(it.css))
                    }
                }
            }) { Text(run.text) }
        }
    }
}

private fun download(name: String, text: String) {
    val url = URL.createObjectURL(Blob(arrayOf(text), BlobPropertyBag(type = "text/plain")))
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
}
