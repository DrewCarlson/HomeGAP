package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.api.Health
import homegap.presentation.routing.Location
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow
import org.jetbrains.compose.web.dom.*

/**
 * The selected item. On wide desktops it is a column beside the page; on narrower ones and
 * tablets a drawer, and on phones a bottom sheet, over the page. Esc, ×, the dimmed page or the
 * back button close it.
 */
@Composable
fun InspectorHost(
    inspector: InspectorModel,
    viewport: Viewport,
    onClose: () -> Unit,
    onOpen: (Location) -> Unit,
) {
    if (viewport == Viewport.Desktop) {
        Aside({
            classes("inspector", "inspector-column")
            attr("aria-label", "Inspector")
        }) {
            InspectorContent(
                inspector = inspector,
                onClose = onClose,
                onOpen = onOpen,
            )
        }
    } else {
        Overlay(
            label = "Inspector · ${inspector.title}",
            titleId = "inspector-title",
            style = if (viewport == Viewport.Phone) OverlayStyle.Sheet else OverlayStyle.Drawer,
            onDismiss = onClose,
        ) {
            Div({ classes("overlay-panel", "inspector") }) {
                if (viewport == Viewport.Phone) {
                    Div({ classes("sheet-handle") })
                }
                InspectorContent(
                    inspector = inspector,
                    onClose = onClose,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun InspectorContent(
    inspector: InspectorModel,
    onClose: () -> Unit,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("inspector-head") }) {
        Div({ classes("inspector-titles") }) {
            Div({ classes("caption") }) { Text("Inspector · ${inspector.kind}") }
            H2({ id("inspector-title") }) { Text(inspector.title) }
            Div({ classes("inspector-status", inspector.health.toneClass) }) { Text(inspector.status) }
        }
        IconButton(
            iconPath = Icons.CLOSE,
            label = "Close the Inspector",
            onClick = onClose,
        )
    }
    Div({ classes("inspector-body") }) {
        inspector.sections.forEach { section ->
            Section(section)
        }
    }
    if (inspector.actions.isNotEmpty()) {
        Div({ classes("inspector-actions") }) {
            inspector.actions.forEach { action ->
                Action(
                    action = action,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun Section(section: InspectorSection) {
    when (section) {
        is InspectorSection.Charts -> Div({ classes("inspector-charts") }) {
            section.charts.forEach { Chart(it) }
        }

        is InspectorSection.Facts -> Div({ classes("inspector-section") }) {
            section.title?.let { Div({ classes("caption") }) { Text(it) } }
            Dl({ classes("fact-list") }) {
                section.rows.forEach { row ->
                    Div {
                        Dt { Text(row.label) }
                        Dd({
                            if (row.mono) {
                                classes("mono")
                            }
                        }) {
                            Text(row.value)
                        }
                    }
                }
            }
        }

        is InspectorSection.Marks -> Div({ classes("inspector-section") }) {
            Div({ classes("caption") }) { Text(section.title) }
            Ul({ classes("marks") }) {
                section.rows.forEach { Mark(it) }
            }
        }

        is InspectorSection.Code -> Div({ classes("inspector-section") }) {
            Div({ classes("caption") }) { Text(section.title) }
            Pre({ classes("code-text") }) { Text(section.text) }
        }

        is InspectorSection.Choices -> Div({ classes("inspector-section") }) {
            Div({ classes("caption") }) { Text(section.title) }
            Div({ classes("role-chips") }) {
                section.options.forEach { option ->
                    Button({
                        classes("chip")
                        if (option.selected) {
                            classes("active")
                        }
                        attr("aria-pressed", option.selected.toString())
                        onClick { section.onToggle(option.id) }
                    }) {
                        Text(if (option.selected) "✓ ${option.label}" else "+ ${option.label}")
                    }
                }
            }
        }

        is InspectorSection.Note -> P({ classes("inspector-note", section.severity.className) }) {
            Text(section.text)
        }
    }
}

@Composable
private fun Mark(row: MarkRow) {
    Li({ classes("mark-row") }) {
        Span({
            classes("mark", row.health.toneClass)
            attr("aria-label", row.health.name.lowercase())
        }) {
            Text(row.health.symbol)
        }
        Span({ classes("mark-name") }) {
            Text(row.name)
            if (row.detail.isNotEmpty()) {
                Span({ classes("mark-detail") }) { Text(row.detail) }
            }
        }
        if (row.value.isNotEmpty()) {
            Span({ classes("mark-value", row.health.toneClass) }) { Text(row.value) }
        }
    }
}

/** State is never color alone: each health also has a symbol. */
val Health.symbol: String
    get() = when (this) {
        Health.OK -> "✓"
        Health.DEGRADED -> "▲"
        Health.DOWN -> "✗"
        Health.UNKNOWN -> "—"
    }

/** The CSS class that colors a health. */
val Health.toneClass: String
    get() = when (this) {
        Health.OK -> "tone-ok"
        Health.DEGRADED -> "tone-warn"
        Health.DOWN -> "tone-down"
        Health.UNKNOWN -> "tone-unknown"
    }
