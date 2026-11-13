package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.routing.Location
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.PageTabModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.StatusLineModel
import homegap.presentation.ui.StatusMessage
import homegap.web.routing.HashRouter
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement

/** The top of a screen: caption, headline and at most one status line. */
@Composable
fun PageHead(
    head: PageHeadModel,
    onOpen: (Location) -> Unit,
) {
    Header({ classes("page-head") }) {
        Div({ classes("page-head-text") }) {
            Div({ classes("caption") }) { Text(head.caption) }
            if (head.tabs.isNotEmpty()) {
                PageTabs(
                    tabs = head.tabs,
                    onOpen = onOpen,
                )
            }
            H1 { Text(head.headline) }
            head.statusLine?.let { line ->
                StatusLine(
                    line = line,
                    onOpen = onOpen,
                )
            }
        }
        if (head.actions.isNotEmpty()) {
            Div({ classes("page-head-actions") }) {
                head.actions.forEach { action ->
                    Action(
                        action = action,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/**
 * The most important message, on one line. "+N more" opens the rest: a box under the line on
 * desktops, a dialog on narrower screens (CSS). A native `<details>` holds it open, so it needs no
 * state of its own.
 */
@Composable
fun StatusLine(
    line: StatusLineModel,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("status-line") }) {
        Message(
            message = line.first,
            onOpen = onOpen,
            className = "status-first",
        )
        if (line.more > 0) {
            Details({
                classes("status-more")
                onKeyDown { event ->
                    if (event.key == "Escape") {
                        (event.nativeEvent.currentTarget as? HTMLElement)?.removeAttribute("open")
                    }
                }
            }) {
                Summary({
                    attr("aria-label", "Show all ${line.messages.size} messages")
                }) {
                    line.messages.drop(1).take(3).forEach { message ->
                        Span({ classes("status-dot-small", message.severity.className) })
                    }
                    Text("+${line.more} more")
                }
                Div({
                    classes("status-box")
                    attr("role", "dialog")
                    attr("aria-label", "All status messages")
                }) {
                    Div({ classes("status-box-head") }) {
                        Div({ classes("caption") }) { Text("Status · ${line.messages.size} messages") }
                        IconButton(
                            iconPath = Icons.CLOSE,
                            label = "Close the status messages",
                            onClick = { document.querySelector(".status-more[open]")?.removeAttribute("open") },
                        )
                    }
                    Ul({ classes("status-list") }) {
                        line.messages.forEach { message ->
                            Li {
                                Message(
                                    message = message,
                                    onOpen = onOpen,
                                    className = "status-item",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    message: StatusMessage,
    onOpen: (Location) -> Unit,
    className: String,
) {
    val location = message.location
    if (location != null) {
        NavLink(
            href = HashRouter.href(location),
            onNavigate = { onOpen(location) },
            classNames = listOf(className, message.severity.className),
        ) {
            Text(message.text)
        }
    } else {
        Span({ classes(className, message.severity.className) }) { Text(message.text) }
    }
}

/** The CSS class that colors a severity. */
val Severity.className: String
    get() = when (this) {
        Severity.Info -> "sev-info"
        Severity.Warn -> "sev-warn"
        Severity.Down -> "sev-down"
    }

/** The pages of a section, as links: the current one is marked for screen readers too. */
@Composable
private fun PageTabs(tabs: List<PageTabModel>, onOpen: (Location) -> Unit) {
    Nav({
        classes("page-tabs")
        attr("aria-label", "Pages of this section")
    }) {
        tabs.forEach { tab ->
            A(href = HashRouter.href(tab.location), {
                classes("page-tab")
                if (tab.selected) {
                    classes("active")
                    attr("aria-current", "page")
                }
                onClick { event ->
                    if (!event.ctrlKey && !event.metaKey && !event.shiftKey) {
                        event.preventDefault()
                        onOpen(tab.location)
                    }
                }
            }) {
                Text(tab.label)
                tab.attention?.let { health ->
                    Span({
                        classes("page-tab-dot", health.toneClass)
                        attr("aria-label", "needs attention")
                    })
                }
            }
        }
    }
}
