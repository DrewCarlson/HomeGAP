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
        caption = "Locations",
        title = "Data-center cities offered when adding a node, and where the homelab is",
        actions = {
            A(
                href = HashRouter.href(Route.Locations),
                { classes("btn", "sm") }) { Text("Manage locations") }
        },
    ) {}
    Figure(
        caption = "Fig. 01 · Desired vs. actual · from the last reconcile pass",
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
        caption = "Fig. 02 · Referenced secrets · names and sources only, values are never shown",
        flush = true,
    ) {
        DataTable(
            headers = remember { listOf("Secret", "Used by", "Source", "Present") }
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
    Figure(caption = "Fig. 03 · Effective desired state") { Pre({ classes("code-text") }) { Text(model.desiredState) } }
    Figure(caption = "Fig. 04 · Config file · ${model.configPath.orEmpty()}") { Pre({ classes("code-text") }) { Text(model.configYaml) } }
}
