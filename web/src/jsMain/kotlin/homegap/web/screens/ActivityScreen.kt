package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.activity.LogRowModel
import homegap.web.ui.Segmented
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun ActivityScreen(model: ActivityScreenModel) {
    Segmented(model.domains, model.selectedDomain, label = { it ?: "all" }, onSelect = model.onSelectDomain)
    Section({ classes("panel") }) {
        Div({ classes("log") }) {
            if (model.entries.isEmpty()) Div({ classes("empty-state") }) { Text(model.emptyText) }
            model.entries.forEach { entry -> key(entry.seq) { LogRow(entry) } }
        }
    }
}

@Composable
private fun LogRow(entry: LogRowModel) {
    Div({ classes("log-row", entry.level.name) }) {
        Span({ classes("log-time", "mono") }) { Text(entry.time) }
        Span({ classes("log-level") }) { Text(entry.level.name.lowercase()) }
        Span({ classes("tag") }) { Text(entry.domain) }
        entry.target?.let { Span({ classes("log-target") }) { Text(it) } }
        Span({ classes("log-msg") }) { Text(entry.message) }
    }
}
