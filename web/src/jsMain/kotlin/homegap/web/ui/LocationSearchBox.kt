package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import homegap.presentation.addnode.LocationOptionModel
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * A search over the locations catalog: type, then pick a match. The matches float under the
 * input in a native popover (so no scrolling box or dialog clips them and the page does not
 * grow), scroll inside it, and go away once a match is picked or the input is emptied.
 *
 * The input is a combobox: Down moves into the list, Up and Down move through it, Enter picks
 * (the first match from the input), Esc clears the search.
 */
@Composable
fun LocationSearchBox(
    query: String,
    matches: List<LocationOptionModel>,
    onQuery: (String) -> Unit,
    onPick: (String) -> Unit,
    placeholder: String = "Search cities or providers: dallas, london, hetzner…",
) {
    val listId = remember { "location-results-${nextListId++}" }
    val open = query.isNotBlank()
    Div({ classes("location-search") }) {
        Input(InputType.Search) {
            value(query)
            placeholder(placeholder)
            attr("aria-label", "Search locations")
            attr("role", "combobox")
            attr("aria-autocomplete", "list")
            attr("aria-controls", listId)
            attr("aria-expanded", open.toString())
            onInput { onQuery(it.value) }
            onKeyDown { event ->
                when (event.key) {
                    "Enter" -> if (matches.isNotEmpty()) {
                        event.preventDefault()
                        onPick(matches.first().id)
                    }

                    "ArrowDown" -> {
                        event.preventDefault()
                        (document.getElementById(listId)?.querySelector("[role=option]") as? HTMLElement)?.focus()
                    }

                    "Escape" -> onQuery("")
                }
            }
        }
        if (open) {
            Ul({
                id(listId)
                classes("location-results")
                attr("popover", "manual")
                attr("role", "listbox")
                attr("aria-label", "Matching locations")
            }) {
                AnchoredPopover(onEscape = { onQuery("") })
                if (matches.isEmpty()) {
                    Li({ classes("muted") }) { Text("No known location matches. Pick a point on the map instead.") }
                }
                matches.forEach { match ->
                    key(match.id) {
                        Li {
                            Button({
                                attr("type", "button")
                                attr("role", "option")
                                onClick { onPick(match.id) }
                            }) {
                                Span({ classes("location-name") }) { Text(match.label) }
                                if (match.detail.isNotBlank()) {
                                    Span({ classes("muted") }) { Text(match.detail) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shows this popover under the input before it (above it when there is no room below), as wide
 * as the input, and keeps it there while anything scrolls or the window resizes. Up and Down
 * move between its options; Up from the first goes back to the input.
 */
@Composable
private fun ElementScope<HTMLElement>.AnchoredPopover(onEscape: () -> Unit) {
    val escape by rememberUpdatedState(onEscape)
    DisposableEffect(Unit) {
        val list = scopeElement
        val input = list.previousElementSibling as HTMLElement
        val place: (Event?) -> Unit = {
            val anchor = input.getBoundingClientRect()
            val below = window.innerHeight - anchor.bottom - GAP_PX - EDGE_PX
            val above = anchor.top - GAP_PX - EDGE_PX
            val upward = below < MIN_ROOM_PX && above > below
            val room = if (upward) above else below
            list.style.left = "${anchor.left}px"
            list.style.width = "${anchor.width}px"
            list.style.maxHeight = "${room.coerceAtMost(MAX_HEIGHT_PX)}px"
            if (upward) {
                list.style.top = "auto"
                list.style.bottom = "${window.innerHeight - anchor.top + GAP_PX}px"
            } else {
                list.style.bottom = "auto"
                list.style.top = "${anchor.bottom + GAP_PX}px"
            }
        }
        val onKey: (Event) -> Unit = { event ->
            val key = (event as KeyboardEvent).key
            val options = list.querySelectorAll("[role=option]")
            val current = (0 until options.length).indexOfFirst { options.item(it) == document.activeElement }
            when (key) {
                "ArrowDown" -> {
                    event.preventDefault()
                    (options.item((current + 1).coerceAtMost(options.length - 1)) as? HTMLElement)?.focus()
                }

                "ArrowUp" -> {
                    event.preventDefault()
                    if (current <= 0) input.focus() else (options.item(current - 1) as? HTMLElement)?.focus()
                }

                "Escape" -> {
                    input.focus()
                    escape()
                }
            }
        }
        list.asDynamic().showPopover()
        place(null)
        // Capture: a scroll in any box that holds the input moves the input.
        window.addEventListener("scroll", place, true)
        window.addEventListener("resize", place)
        list.addEventListener("keydown", onKey)
        onDispose {
            window.removeEventListener("scroll", place, true)
            window.removeEventListener("resize", place)
            list.removeEventListener("keydown", onKey)
        }
    }
}

private const val GAP_PX = 4.0
private const val EDGE_PX = 8.0
private const val MIN_ROOM_PX = 160.0
private const val MAX_HEIGHT_PX = 300.0

private var nextListId = 0
