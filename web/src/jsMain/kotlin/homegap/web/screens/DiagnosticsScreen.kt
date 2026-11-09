package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.diagnostics.DiagnosticsScreenModel
import homegap.web.ui.*
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Text

@Composable
fun DiagnosticsScreen(model: DiagnosticsScreenModel) {
    Panel("Node", "Everything here is read-only: nothing on the node changes") {
        Div({ classes("panel-body") }) {
            if (model.nodes.isEmpty()) {
                Muted("No nodes yet.")
                return@Div
            }
            Div({ classes("inline-row") }) {
                Muted("Node")
                Segmented(model.nodes, model.node ?: "", { it }, enabled = !model.loading, onSelect = model.onSelectNode)
            }
            Div({ classes("inline-row") }) {
                Muted("Container")
                Segmented(model.services.map { it.first }, model.service, { id -> model.services.first { it.first == id }.second }, enabled = !model.loading, onSelect = model.onSelectService)
            }
            Div({ classes("inline-row") }) {
                Muted("Since")
                Segmented(model.sinceChoices, model.since, { it }, enabled = !model.loading, onSelect = model.onSelectSince)
                ActionButton("Show logs", ButtonStyle.Primary, enabled = !model.loading && model.node != null, onClick = model.onShowLogs)
                ActionButton(
                    "Run diagnostics", enabled = !model.loading && model.node != null,
                    tooltip = "Containers, mesh, cluster proxy, firewall, NAT, ports and recent logs",
                    onClick = model.onRunDiagnostics,
                )
            }
            model.error?.let { Div({ classes("banner", "error") }) { Text(it) } }
        }
    }
    val output = model.output
    if (model.loading) {
        Panel("Loading…") { Div({ classes("panel-body") }) { Muted("Asking the node over SSH.") } }
    } else if (output != null) {
        Panel("${output.title} · ${output.node}", actions = {
            ActionButton("Download", small = true) { download("${output.node}-${output.title.substringBefore(' ').lowercase()}.txt", output.text) }
        }) {
            Pre { Text(output.text) }
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
