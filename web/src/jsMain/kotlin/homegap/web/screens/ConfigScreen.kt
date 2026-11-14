package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.config.ConfigScreenModel
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

@Composable
fun ConfigScreen(model: ConfigScreenModel) {
    Figure(
        title = "Locations",
        actions = {
            A(
                href = HashRouter.href(Route.Locations),
                attrs = { classes("btn", "sm") },
            ) {
                Text("Manage locations")
            }
        },
    )
    Figure(
        title = "Differences",
        flush = true,
        actions = {
            Label(attrs = { classes("check") }) {
                CheckboxInput(model.onlyChanges) {
                    onChange { model.onToggleOnlyChanges(it.value) }
                }
                Text("Only differences")
            }
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
            headers = remember { listOf("Secret", "Used by", "Source", "Present") },
            emptyText = "The config references no secrets.",
            isEmpty = model.secrets.isEmpty(),
        ) {
            model.secrets.forEach { secret ->
                key(secret.name, secret.usedBy) {
                    Tr {
                        Cell("Secret", "mono", "primary-cell") { Text(secret.name) }
                        Cell("Used by") { Text(secret.usedBy) }
                        Cell("Source", "mono") { Text(secret.source) }
                        Cell("Present") { Pill(secret.present) }
                    }
                }
            }
        }
    }
    Figure(title = "Desired state") {
        Pre({ classes("code-text") }) {
            ScrollHint()
            Text(model.desiredState)
        }
    }
    Figure(title = "Config file", note = model.configPath) {
        model.acmeNote?.let { note ->
            P({ classes("small", "muted") }) {
                Text("$note ")
                A(href = homegap.web.routing.HashRouter.href(homegap.presentation.routing.Route.Certificates), { classes("link") }) {
                    Text("Certificates →")
                }
            }
        }
        Pre({ classes("code-text") }) {
            ScrollHint()
            Text(model.configYaml)
        }
    }
}
