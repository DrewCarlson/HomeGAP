package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** One choice in a [KebabMenu]. */
data class MenuItem(
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A row's less frequent actions behind a ⋮ button. The menu is a native popover: Esc and a
 * click outside close it, it sits above dialogs, and focus returns to the button. Arrow keys
 * move between the items; it opens under the button, or above it near the bottom of the screen.
 */
@Composable
fun KebabMenu(
    label: String,
    items: List<MenuItem>,
) {
    val menuId = remember { "menu-${nextMenuId++}" }
    Button({
        classes("btn", "icon-btn", "kebab")
        attr("type", "button")
        attr("aria-label", label)
        attr("aria-haspopup", "menu")
        attr("aria-controls", menuId)
        attr("popovertarget", menuId)
    }) {
        Icon(Icons.MORE, size = 18)
    }
    Div({
        id(menuId)
        classes("menu")
        attr("popover", "auto")
        attr("role", "menu")
        attr("aria-label", label)
    }) {
        DisposableEffect(Unit) {
            val menu = scopeElement
            val button = menu.previousElementSibling as HTMLElement
            val onToggle: (Event) -> Unit = { event ->
                val open = event.asDynamic().newState == "open"
                button.setAttribute("aria-expanded", open.toString())
                if (open) {
                    place(menu, button)
                    (menu.querySelector("[role=menuitem]") as? HTMLElement)?.focus()
                } else {
                    // Back to the button, unless the click that closed it focused something else.
                    val focused = document.activeElement
                    if (focused == null || focused == document.body || menu.contains(focused)) {
                        button.focus()
                    }
                }
            }
            val onKey: (Event) -> Unit = { event ->
                val key = (event as KeyboardEvent).key
                val entries = menu.querySelectorAll("[role=menuitem]")
                val count = entries.length
                val current = (0 until count).indexOfFirst { entries.item(it) == menu.ownerDocument?.activeElement }
                val next = when (key) {
                    "ArrowDown" -> (current + 1) % count
                    "ArrowUp" -> (current - 1 + count) % count
                    "Home" -> 0
                    "End" -> count - 1
                    else -> null
                }
                if (next != null && count > 0) {
                    event.preventDefault()
                    (entries.item(next) as HTMLElement).focus()
                }
            }
            button.setAttribute("aria-expanded", "false")
            menu.addEventListener("toggle", onToggle)
            menu.addEventListener("keydown", onKey)
            onDispose {
                menu.removeEventListener("toggle", onToggle)
                menu.removeEventListener("keydown", onKey)
            }
        }
        items.forEach { item ->
            Button({
                classes("menu-item")
                if (item.danger) {
                    classes("danger")
                }
                attr("type", "button")
                attr("role", "menuitem")
                onClick { event ->
                    (event.target as HTMLElement).closest("[popover]")?.asDynamic()?.hidePopover()
                    item.onClick()
                }
            }) {
                Text(item.label)
            }
        }
    }
}

/** Right-aligns [menu] under [button], or above it when there is no room below. */
private fun place(menu: HTMLElement, button: HTMLElement) {
    val anchor = button.getBoundingClientRect()
    val gap = 4.0
    val left = (anchor.right - menu.offsetWidth).coerceAtLeast(8.0)
    val below = anchor.bottom + gap
    val top = if (below + menu.offsetHeight > window.innerHeight - 8) anchor.top - gap - menu.offsetHeight else below
    menu.style.left = "${left}px"
    menu.style.top = "${top.coerceAtLeast(8.0)}px"
}

private var nextMenuId = 0
