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
    Panel(
        title = "Locations",
        subtitle = "Data-center cities offered when adding a node, and where the homelab is",
        actions = {
            A(
                href = HashRouter.href(Route.Locations),
                { classes("link") }) { Text("Manage locations →") }
        }) {}
    Panel(
        title = "Desired vs. actual",
        subtitle = "From the last reconcile pass",
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
    Panel(
        title = "Referenced secrets",
        subtitle = "Names and sources only; values are never shown"
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
    Panel("Effective desired state") { Pre { Text(model.desiredState) } }
    Panel("Config file", model.configPath) { Pre { Text(model.configYaml) } }
}
