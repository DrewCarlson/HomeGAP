package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.config.ConfigScreenModel
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*

@Composable
fun ConfigScreen(model: ConfigScreenModel) {
    Figure(
        title = "Differences",
        flush = true,
        actions = {
            ProblemsFilter(model.filter)
        }
    ) {
        val emptyText = if (model.loading) "Loading…" else "Everything matches the desired state."
        DataTable(
            headers = remember { listOf("Domain", "Target", "Key", "Action", "Desired", "Actual") },
            emptyText = emptyText,
            isEmpty = model.diff.isEmpty()
        ) {
            model.diff.forEach { item ->
                Tr {
                    Cell("Domain") { Tag(item.domain) }
                    Cell("Target") { Text(item.target) }
                    Cell("Key", "mono", "primary-cell") { Text(item.key) }
                    Cell("Action") { Pill(item.action) }
                    Cell("Desired", "mono") { Text(item.desired) }
                    Cell("Actual", "mono") { Text(item.actual) }
                }
            }
        }
    }
    Figure(
        title = "Secrets",
        note = "Names and sources only.",
        flush = true,
    ) {
        DataTable(
            headers = remember { listOf("Secret", "Used by", "Source", "State") },
            emptyText = "The config references no secrets.",
            isEmpty = model.secrets.isEmpty(),
        ) {
            model.secrets.forEach { secret ->
                key(secret.name, secret.usedBy) {
                    Tr {
                        Cell("Secret", "mono", "primary-cell") { Text(secret.name) }
                        Cell("Used by") { Text(secret.usedBy) }
                        Cell("Source", "mono") { Text(secret.source) }
                        Cell("State") { Pill(secret.present) }
                    }
                }
            }
        }
    }
    CodeBox(
        title = "Desired state",
        text = model.desiredState,
    )
    CodeBox(
        title = "Config file",
        note = model.configPath,
        text = model.configYaml,
    ) {
        model.acmeNote?.let { note ->
            P({ classes("small", "muted") }) {
                Text("$note ")
                A(href = HashRouter.href(Route.Certificates), { classes("link") }) {
                    Text("Certificates →")
                }
            }
        }
    }
}

/** A long text that stays closed until it is asked for, with a button that copies it. */
@Composable
private fun CodeBox(
    title: String,
    text: String,
    note: String? = null,
    lead: @Composable () -> Unit = {},
) {
    Details({ classes("figure", "code-box") }) {
        Summary({ classes("figure-head") }) {
            Div({ classes("figure-titles") }) {
                H2 { Text(title) }
                note?.let { Div({ classes("figure-sub") }) { Text(it) } }
            }
        }
        Div({ classes("figure-body") }) {
            lead()
            Div {
                ActionButton(
                    text = "Copy",
                    small = true,
                    onClick = { window.navigator.clipboard.writeText(text) },
                )
            }
            Pre({ classes("code-text") }) { Text(text) }
        }
    }
}
