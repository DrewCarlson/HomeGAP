package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import homegap.api.Health
import homegap.presentation.data.Toast
import homegap.presentation.routing.Location
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The selected item. On desktops it is a drawer over the right of the page that does not block the
 * page, so another row can be selected. On tablets it is a modal drawer, and on phones a bottom
 * sheet. Esc, ×, the dimmed page or the back button close it.
 */
@Composable
fun InspectorHost(
    inspector: InspectorModel,
    toasts: List<Toast>,
    viewport: Viewport,
    onClose: () -> Unit,
    onOpen: (Location) -> Unit,
) {
    if (viewport == Viewport.Desktop || viewport == Viewport.NarrowDesktop) {
        Aside({
            classes("inspector", "inspector-column")
            attr("aria-label", "Inspector")
        }) {
            CloseOnEscape(onClose)
            InspectorContent(
                inspector = inspector,
                toasts = emptyList(),
                onClose = onClose,
                onOpen = onOpen,
            )
            ToastStack(
                toasts = toasts,
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
                InspectorContent(
                    inspector = inspector,
                    toasts = toasts,
                    onClose = onClose,
                    onOpen = onOpen,
                )
            }
        }
    }
}

/**
 * Closes the Inspector column on Escape. A dialog, a menu and a field that is being edited take
 * the key first.
 */
@Composable
private fun CloseOnEscape(onClose: () -> Unit) {
    val close by rememberUpdatedState(onClose)
    DisposableEffect(Unit) {
        val onKey: (Event) -> Unit = { event ->
            val target = event.target as? HTMLElement
            val busy = document.querySelector("dialog[open], [popover]:popover-open, details[open]") != null ||
                target?.closest("input, textarea, select, [contenteditable]") != null
            if (event.asDynamic().key == "Escape" && !event.defaultPrevented && !busy) {
                close()
            }
        }
        document.addEventListener("keydown", onKey)
        onDispose { document.removeEventListener("keydown", onKey) }
    }
}

@Composable
private fun InspectorContent(
    inspector: InspectorModel,
    toasts: List<Toast>,
    onClose: () -> Unit,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("inspector-head") }) {
        Div({ classes("inspector-titles") }) {
            Div({ classes("caption") }) { Text("Inspector · ${inspector.kind}") }
            H2({ id("inspector-title") }) { HostName(inspector.title) }
            Div({ classes("inspector-status", inspector.health.toneClass) }) { Text(inspector.status) }
        }
        IconButton(
            iconPath = Icons.CLOSE,
            label = "Close the Inspector",
            onClick = onClose,
        )
    }
    Div({ classes("inspector-body") }) {
        ScrollHint()
        ScrollReset(inspector.kind to inspector.title)
        inspector.sections.forEach { section ->
            Section(
                section = section,
                onOpen = onOpen,
            )
        }
    }
    // Drawer and sheet: a zero-height line over the action bar; the toasts rise from it over the body's end.
    if (toasts.isNotEmpty()) {
        Div({ classes("inspector-toasts") }) {
            ToastStack(
                toasts = toasts,
                placement = ToastPlacement.AboveInspectorActions,
            )
        }
    }
    if (inspector.actions.isNotEmpty()) {
        Div({ classes("inspector-actions") }) {
            InspectorActions(
                actions = inspector.actions,
                onOpen = onOpen,
            )
        }
    }
}

/**
 * Up to three actions show as buttons. With more, the first primary action (or the first action)
 * is the button, and the others are in the "More" menu, with the dangerous ones last and apart.
 */
@Composable
private fun InspectorActions(actions: List<ActionModel>, onOpen: (Location) -> Unit) {
    if (actions.size <= MAX_ACTION_BUTTONS) {
        actions.forEach { action ->
            Action(
                action = action,
                onOpen = onOpen,
            )
        }
        return
    }
    val safe = actions.filter { it.style != ActionStyle.Danger }
    val main = safe.firstOrNull { it.style == ActionStyle.Primary } ?: safe.firstOrNull()
    val others = (safe - setOfNotNull(main)) + actions.filter { it.style == ActionStyle.Danger }
    main?.let {
        Action(
            action = it,
            onOpen = onOpen,
        )
    }
    KebabMenu(
        label = "More actions",
        text = "More",
        items = others.mapIndexed { index, action ->
            val danger = action.style == ActionStyle.Danger
            MenuItem(
                label = action.label,
                danger = danger,
                enabled = action.enabled,
                separated = danger && others.getOrNull(index - 1)?.style != ActionStyle.Danger,
                onClick = {
                    action.onClick?.invoke()
                    action.location?.let(onOpen)
                    action.href?.let { window.open(it, "_blank", "noopener") }
                },
            )
        },
    )
}

private const val MAX_ACTION_BUTTONS = 3

@Composable
private fun Section(section: InspectorSection, onOpen: (Location) -> Unit) {
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
                            if (row.copy) {
                                classes("copyable")
                            }
                        }) {
                            Span { Text(row.value) }
                            if (row.copy) {
                                IconButton(
                                    iconPath = Icons.COPY,
                                    label = "Copy ${row.label}",
                                    onClick = { window.navigator.asDynamic().clipboard?.writeText(row.value) },
                                )
                            }
                        }
                    }
                }
            }
        }

        is InspectorSection.Marks -> Div({ classes("inspector-section") }) {
            Div({ classes("caption") }) { Text(section.title) }
            Ul({ classes("marks") }) {
                section.rows.forEach {
                    Mark(
                        row = it,
                        onOpen = onOpen,
                    )
                }
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
private fun Mark(row: MarkRow, onOpen: (Location) -> Unit) {
    Li({ classes("mark-row") }) {
        Span({
            classes("mark", row.health.toneClass)
            attr("aria-label", row.health.name.lowercase())
        }) {
            Text(row.health.symbol)
        }
        Span({ classes("mark-name") }) {
            Text(row.name)
            row.tag?.let { tag ->
                Span({ classes("cert-tag", "homelab") }) { Text(tag) }
            }
            if (row.detail.isNotEmpty()) {
                Span({ classes("mark-detail") }) { Text(row.detail) }
            }
            row.location?.let { location ->
                NavLink(
                    href = homegap.web.routing.HashRouter.href(location),
                    onNavigate = { onOpen(location) },
                    classNames = listOf("mark-link", "small"),
                ) {
                    Text("Certificates →")
                }
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
