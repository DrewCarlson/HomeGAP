package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import homegap.presentation.app.SearchModel
import homegap.presentation.search.SearchHit
import homegap.presentation.search.SearchKind
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** The search box in the menu; it opens the search overlay. */
@Composable
fun SearchField(search: SearchModel) {
    Button({
        classes("search-field")
        attr("aria-label", "Search everything")
        onClick { search.onOpen() }
    }) {
        Icon(Icons.SEARCH, size = 14)
        Span({ classes("search-field-text") }) { Text("Search everything") }
        Kbd { Text("⌘K") }
    }
}

/**
 * ⌘K, Ctrl+K or "/" opens search from anywhere, except while typing in a field. The terminal
 * panel keeps every key: Ctrl+K belongs to the shell there.
 */
@Composable
fun SearchShortcut(search: SearchModel) {
    val open by rememberUpdatedState(search.onOpen)
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { event ->
            val key = event as KeyboardEvent
            val target = key.target
            val typing = target is HTMLInputElement || target is HTMLTextAreaElement ||
                (target as? HTMLElement)?.isContentEditable == true
            val inTerminal = (target as? HTMLElement)?.closest(".terminal-panel") != null
            val command = (key.metaKey || key.ctrlKey) && key.key.lowercase() == "k"
            if (!inTerminal && (command || (key.key == "/" && !typing))) {
                key.preventDefault()
                open()
            }
        }
        document.addEventListener("keydown", listener)
        onDispose { document.removeEventListener("keydown", listener) }
    }
}

/**
 * Spotlight-style search: a large field, results grouped by kind, kind chips, and the keys to
 * move. Full screen on phones.
 */
@Composable
fun SearchOverlay(search: SearchModel, viewport: Viewport) {
    Overlay(
        label = "Search",
        style = if (viewport == Viewport.Phone) OverlayStyle.FullScreen else OverlayStyle.Modal,
        onDismiss = search.onClose,
    ) {
        Div({ classes("overlay-panel", "search") }) {
            Div({ classes("search-input") }) {
                Icon(Icons.SEARCH, size = 18)
                Input(InputType.Search) {
                    value(search.query)
                    attr("placeholder", "Search, or type > for actions")
                    attr("aria-label", "Search")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    autoFocus()
                    onInput { event ->
                        val text = event.value
                        if (text.startsWith(">")) {
                            search.onKind(SearchKind.Action)
                            search.onQuery(text.removePrefix(">").trimStart())
                        } else {
                            search.onQuery(text)
                        }
                    }
                    onKeyDown { event ->
                        when (event.key) {
                            "ArrowDown" -> {
                                event.preventDefault()
                                search.onMove(1)
                            }

                            "ArrowUp" -> {
                                event.preventDefault()
                                search.onMove(-1)
                            }

                            "Enter" -> search.highlightedHit?.let(search.onChoose)
                        }
                    }
                }
                Button({
                    classes("btn", "sm", "search-cancel")
                    onClick { search.onClose() }
                }) {
                    Text(if (viewport == Viewport.Phone) "Cancel" else "esc")
                }
            }
            Div({
                classes("search-kinds")
                attr("role", "group")
                attr("aria-label", "Kinds")
            }) {
                KindChip(
                    label = "All",
                    active = search.kind == null,
                    onClick = { search.onKind(null) },
                )
                search.kinds.forEach { kind ->
                    KindChip(
                        label = kind.title,
                        active = search.kind == kind,
                        onClick = { search.onKind(kind) },
                    )
                }
            }
            Div({
                classes("search-results")
                attr("role", "listbox")
            }) {
                ScrollHint()
                if (search.count == 0 && search.query.isNotBlank()) {
                    P({ classes("search-none") }) { Text("Nothing matches “${search.query}”.") }
                }
                var index = 0
                search.groups.forEach { group ->
                    Div({ classes("caption", "search-group") }) { Text(group.title) }
                    group.hits.forEach { hit ->
                        Result(
                            hit = hit,
                            highlighted = index == search.highlighted,
                            onChoose = search.onChoose,
                        )
                        index++
                    }
                }
            }
            Div({ classes("search-keys") }) {
                Text("↑↓ move · ↵ open · > actions")
                Span { Text(if (search.count == 1) "1 result" else "${search.count} results") }
            }
        }
    }
}

@Composable
private fun KindChip(label: String, active: Boolean, onClick: () -> Unit) {
    Button({
        classes("chip")
        if (active) {
            classes("active")
        }
        attr("aria-pressed", active.toString())
        onClick { onClick() }
    }) {
        Text(label)
    }
}

@Composable
private fun Result(hit: SearchHit, highlighted: Boolean, onChoose: (SearchHit) -> Unit) {
    Button({
        classes("search-hit")
        if (highlighted) {
            classes("highlighted")
        }
        attr("role", "option")
        attr("aria-selected", highlighted.toString())
        onClick { onChoose(hit) }
    }) {
        Span({ classes("search-hit-icon") }) {
            Icon(hit.kind.iconPath, size = 14)
        }
        Span({ classes("search-hit-text") }) {
            Span({ classes("search-hit-title") }) { Text(hit.title) }
            Span({ classes("search-hit-sub") }) { Text(hit.subtitle) }
        }
        Span({ classes("search-hit-kind") }) {
            Text(if (hit.run != null) "↵ run" else hit.kind.title)
        }
    }
}

private val SearchKind.iconPath: String
    get() = when (this) {
        SearchKind.Action -> "M13 3L5 14h6l-1 7 8-11h-6z"
        SearchKind.Node -> "M7 12a5 5 0 1 0 10 0a5 5 0 1 0-10 0M12 3v4M12 17v4M3 12h4M17 12h4"
        SearchKind.Service -> "M4 6h16M4 12h16M4 18h16"
        SearchKind.Dns -> "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M3 12h18"
        SearchKind.Tunnel -> "M3 12h4M17 12h4M7 8h10v8H7z"
        SearchKind.Page -> "M5 4h14v16H5zM5 9h14"
        SearchKind.Setting -> "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M14 4v4M8 10v4M16 16v4"
        SearchKind.Log -> "M3 12h4l3-8 4 16 3-8h4"
    }
