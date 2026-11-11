package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.api.LogLevel
import homegap.presentation.activity.ActivityScreenModel
import homegap.presentation.activity.LogRowModel
import homegap.presentation.routing.Location
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.*

/** The reconcile log, newest first; an entry opens in the Inspector. */
@Composable
fun ActivityScreen(
    model: ActivityScreenModel,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("filters") }) {
        Label(attrs = { classes("filter-field") }) {
            Icon(Icons.SEARCH, size = 14)
            Input(InputType.Search) {
                value(model.query)
                attr("placeholder", "Filter by text or target")
                attr("aria-label", "Filter the log")
                onInput { model.onQuery(it.value) }
            }
        }
        Div({
            classes("segmented")
            attr("role", "group")
            attr("aria-label", "Level")
        }) {
            model.levels.forEach { level ->
                Button({
                    if (model.level == level) {
                        classes("active")
                    }
                    attr("aria-pressed", (model.level == level).toString())
                    onClick { model.onLevel(level) }
                }) {
                    Text(level.label)
                }
            }
        }
        model.pass?.let { pass ->
            Button({
                classes("chip", "active")
                attr("aria-label", "Show every pass")
                onClick { model.onClearPass() }
            }) {
                Text("Pass #$pass ×")
            }
        }
        Select({
            classes("select")
            attr("aria-label", "Domain")
            onChange { event -> model.onDomain(event.value?.takeIf { it.isNotEmpty() }) }
        }) {
            model.domains.forEach { domain ->
                Option(
                    value = domain.orEmpty(),
                    attrs = {
                        if (model.domain == domain) {
                            selected()
                        }
                    },
                ) {
                    Text(domain ?: "All domains")
                }
            }
        }
    }
    Figure(
        caption = "Fig. 01 · Log · newest first",
        flush = true,
    ) {
        Ol({ classes("log-list") }) {
            if (model.entries.isEmpty()) {
                Li({ classes("figure-note") }) { Text(model.emptyText) }
            }
            model.entries.forEach { entry ->
                key(entry.seq) {
                    LogRow(
                        entry = entry,
                        onOpen = onOpen,
                    )
                }
            }
        }
        if (model.canLoadOlder) {
            Div({ classes("figure-note") }) {
                ActionButton(
                    text = if (model.loadingOlder) "Loading…" else "Load older",
                    enabled = !model.loadingOlder,
                    mutates = false,
                    onClick = model.onLoadOlder,
                )
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogRowModel, onOpen: (Location) -> Unit) {
    Li {
        NavLink(
            href = HashRouter.href(entry.location),
            onNavigate = { onOpen(entry.location) },
            classNames = listOf("log-entry"),
        ) {
            Span({
                classes("mono", "log-time")
                title(entry.absolute)
            }) {
                Text(entry.time)
            }
            Span({ classes("log-level", entry.severity.className) }) { Text(entry.level.name.lowercase()) }
            Span({ classes("mono", "muted", "log-domain") }) { Text(entry.domain) }
            Span({ classes("log-text") }) {
                entry.target?.let { target ->
                    B { Text("$target ") }
                }
                Text(entry.message)
            }
        }
    }
}

private val LogLevel?.label: String
    get() = when (this) {
        null -> "All"
        LogLevel.CHANGE -> "Changes"
        LogLevel.WARN -> "Warnings"
        LogLevel.ERROR -> "Errors"
        LogLevel.INFO -> "Info"
    }
