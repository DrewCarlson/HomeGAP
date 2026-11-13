package homegap.web.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import homegap.presentation.terminal.TerminalEvent
import homegap.presentation.terminal.TerminalLayout
import homegap.presentation.terminal.TerminalNoticeModel
import homegap.presentation.terminal.TerminalPanelModel
import homegap.presentation.terminal.TerminalSessions
import homegap.presentation.terminal.TerminalTabModel
import homegap.web.ui.ActionButton
import homegap.web.ui.Icon
import homegap.web.ui.IconButton
import homegap.web.ui.Icons
import homegap.web.ui.KebabMenu
import homegap.web.ui.MenuItem
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent

/**
 * The shells on the nodes, docked under the page on every screen: a tab per session, and the
 * terminal of the selected one. It can fill the window or shrink to its tab bar; phones show it
 * as a full-screen sheet with a row of the keys their keyboards lack. [covered] while a drawer
 * or a sheet lies over the page, so the terminal takes the focus only after it closed.
 */
@Composable
fun TerminalPanel(
    model: TerminalPanelModel,
    covered: Boolean,
) {
    // The terminals of the tabs, so the tools of the panel reach the one that shows.
    val connections = remember { mutableMapOf<String, TerminalConnection>() }
    val current = { connections[model.selected] }
    Section({
        classes("terminal-panel", model.layout.className)
        attr("aria-label", "Terminal")
    }) {
        DockedHeight(model.height)
        PhoneViewport()
        if (model.layout == TerminalLayout.Docked) {
            ResizeGrip(onResize = { model.onEvent(TerminalEvent.Resize(it)) })
        }
        Div({ classes("terminal-bar") }) {
            Div({
                classes("terminal-tabs")
                attr("role", "tablist")
            }) {
                model.tabs.forEach { tab ->
                    key(tab.id) {
                        Tab(
                            tab = tab,
                            selected = tab.id == model.selected,
                            onEvent = model.onEvent,
                        )
                    }
                }
            }
            Tools(
                model = model,
                current = current,
            )
        }
        Div({ classes("terminal-body") }) {
            model.tabs.forEach { tab ->
                key(tab.id) {
                    Screen(
                        tab = tab,
                        selected = tab.id == model.selected,
                        model = model,
                        covered = covered,
                        connections = connections,
                    )
                }
            }
        }
        KeyRow(current)
    }
}

@Composable
private fun Tab(
    tab: TerminalTabModel,
    selected: Boolean,
    onEvent: (TerminalEvent) -> Unit,
) {
    Div({
        classes("terminal-tab")
        if (selected) {
            classes("active")
        }
        if (tab.ended) {
            classes("ended")
        }
    }) {
        Button({
            classes("terminal-tab-name")
            attr("type", "button")
            attr("role", "tab")
            attr("aria-selected", selected.toString())
            onClick { onEvent(TerminalEvent.Select(tab.id)) }
        }) {
            Text(tab.label)
        }
        Button({
            classes("terminal-tab-close")
            attr("type", "button")
            attr("aria-label", "Close the terminal ${tab.label}")
            title("Close")
            onClick { onEvent(TerminalEvent.Close(tab.id)) }
        }) {
            Icon(Icons.CLOSE, size = 12)
        }
    }
}

/** Search, font size and the commands (hidden while minimized), then the size of the panel. */
@Composable
private fun Tools(
    model: TerminalPanelModel,
    current: () -> TerminalConnection?,
) {
    Div({ classes("terminal-tools") }) {
        Div({ classes("terminal-shell-tools") }) {
            Input(InputType.Search) {
                classes("terminal-search")
                placeholder("Find")
                attr("aria-label", "Find in the scrollback")
                attr("autocomplete", "off")
                attr("spellcheck", "false")
                onInput { current()?.find(it.value) }
                onKeyDown { event ->
                    if (event.key == "Enter") {
                        current()?.find(
                            text = (event.target as HTMLInputElement).value,
                            backwards = event.shiftKey,
                        )
                    }
                }
            }
            ActionButton(
                text = "A−",
                small = true,
                tooltip = "Smaller text",
                onClick = { model.onEvent(TerminalEvent.Zoom(-1)) },
            )
            ActionButton(
                text = "A+",
                small = true,
                tooltip = "Larger text",
                onClick = { model.onEvent(TerminalEvent.Zoom(1)) },
            )
            KebabMenu(
                label = "Commands",
                text = "Commands",
                items = model.commands.map { command ->
                    MenuItem(
                        label = command,
                        onClick = { current()?.run(command) },
                    )
                },
            )
        }
        if (model.layout == TerminalLayout.Minimized) {
            IconButton(
                iconPath = Icons.RESTORE,
                label = "Show the terminal",
                onClick = { model.onEvent(TerminalEvent.SetLayout(TerminalLayout.Docked)) },
            )
        } else {
            IconButton(
                iconPath = Icons.MINIMIZE,
                label = "Minimize the terminal",
                onClick = { model.onEvent(TerminalEvent.SetLayout(TerminalLayout.Minimized)) },
            )
        }
        Span({ classes("terminal-full-toggle") }) {
            if (model.layout == TerminalLayout.Full) {
                IconButton(
                    iconPath = Icons.SHRINK,
                    label = "Dock the terminal",
                    onClick = { model.onEvent(TerminalEvent.SetLayout(TerminalLayout.Docked)) },
                )
            } else {
                IconButton(
                    iconPath = Icons.EXPAND,
                    label = "Full screen",
                    onClick = { model.onEvent(TerminalEvent.SetLayout(TerminalLayout.Full)) },
                )
            }
        }
    }
}

/** The terminal of [tab]. It stays alive while another tab shows or the panel is minimized. */
@Composable
private fun Screen(
    tab: TerminalTabModel,
    selected: Boolean,
    model: TerminalPanelModel,
    covered: Boolean,
    connections: MutableMap<String, TerminalConnection>,
) {
    val connection = remember {
        TerminalConnection(
            onConnected = { model.onEvent(TerminalEvent.Connected(tab.id)) },
            onDisconnected = { reason -> model.onEvent(TerminalEvent.Disconnected(tab.id, reason)) },
            onExited = { code -> model.onEvent(TerminalEvent.Exited(tab.id, code)) },
        )
    }
    Div({
        classes("terminal-screen")
        if (!selected) {
            classes("behind")
        }
        attr("role", "tabpanel")
        attr("aria-label", tab.label)
    }) {
        Div({ classes("terminal-host") }) {
            DisposableEffect(Unit) {
                connection.mount(scopeElement)
                connections[tab.id] = connection
                onDispose {
                    connections.remove(tab.id)
                    connection.dispose()
                }
            }
        }
        tab.notice?.let { notice ->
            Notice(
                notice = notice,
                onEvent = model.onEvent,
            )
        }
    }
    LaunchedEffect(tab.ticket) {
        tab.ticket?.let { connection.connect(it) }
    }
    LaunchedEffect(model.fontSize) {
        connection.setFontSize(model.fontSize)
    }
    LaunchedEffect(model.focusRequests, covered, selected) {
        if (selected && !covered && model.focusRequests > connection.focusAnswered) {
            connection.focusAnswered = model.focusRequests
            connection.focus()
        }
    }
}

@Composable
private fun Notice(notice: TerminalNoticeModel, onEvent: (TerminalEvent) -> Unit) {
    Div({
        classes("terminal-notice")
        attr("role", "status")
    }) {
        Span { Text(notice.text) }
        val label = notice.actionLabel
        val action = notice.action
        if (label != null && action != null) {
            ActionButton(
                text = label,
                small = true,
                onClick = { onEvent(action) },
            )
        }
    }
}

/** A key that a phone keyboard lacks: it types [text], or is an arrow ([arrow] is its letter). */
private data class PhoneKey(
    val label: String,
    val name: String,
    val text: String = "",
    val arrow: Char? = null,
)

/** Phones: Esc, Tab, a Ctrl for the next key, the arrows and three symbols, over the keyboard. */
@Composable
private fun KeyRow(current: () -> TerminalConnection?) {
    val keys = remember {
        listOf(
            PhoneKey(label = "↑", name = "Up", arrow = 'A'),
            PhoneKey(label = "↓", name = "Down", arrow = 'B'),
            PhoneKey(label = "←", name = "Left", arrow = 'D'),
            PhoneKey(label = "→", name = "Right", arrow = 'C'),
            PhoneKey(label = "|", name = "Pipe", text = "|"),
            PhoneKey(label = "/", name = "Slash", text = "/"),
            PhoneKey(label = "-", name = "Minus", text = "-"),
        )
    }
    Div({
        classes("terminal-keys")
        attr("role", "group")
        attr("aria-label", "Keys")
    }) {
        PhoneKeyButton(
            label = "Esc",
            name = "Escape",
            onPress = { current()?.type("\u001b") },
        )
        PhoneKeyButton(
            label = "Tab",
            name = "Tab",
            onPress = { current()?.type("\t") },
        )
        PhoneKeyButton(
            label = "Ctrl",
            name = "Control, for the next key",
            pressed = current()?.ctrlArmed == true,
            onPress = { current()?.let { it.ctrlArmed = !it.ctrlArmed } },
        )
        keys.forEach { phoneKey ->
            PhoneKeyButton(
                label = phoneKey.label,
                name = phoneKey.name,
                onPress = {
                    val arrow = phoneKey.arrow
                    if (arrow != null) {
                        current()?.arrow(arrow)
                    } else {
                        current()?.type(phoneKey.text)
                    }
                },
            )
        }
    }
}

@Composable
private fun PhoneKeyButton(
    label: String,
    name: String,
    pressed: Boolean? = null,
    onPress: () -> Unit,
) {
    Button({
        classes("terminal-key")
        attr("type", "button")
        attr("aria-label", name)
        pressed?.let { attr("aria-pressed", it.toString()) }
        // The terminal keeps the focus, so the on-screen keyboard stays up.
        onMouseDown { it.preventDefault() }
        onClick { onPress() }
    }) {
        Text(label)
    }
}

/** Keeps `--terminal-height`, the height of the docked panel that the CSS reads. */
@Composable
private fun DockedHeight(height: Int) {
    LaunchedEffect(height) {
        setRootProperty("--terminal-height", "${height}px")
    }
}

/**
 * The top edge of the docked panel: dragging it changes the height as the pointer moves, and
 * [onResize] gets the height when it is released.
 */
@Composable
private fun ResizeGrip(onResize: (Int) -> Unit) {
    val resize by rememberUpdatedState(onResize)
    Div({
        classes("terminal-grip")
        attr("aria-hidden", "true")
    }) {
        DisposableEffect(Unit) {
            val grip = scopeElement
            val panel = grip.parentElement as HTMLElement
            var dragging = false
            var startY = 0.0
            var startHeight = 0
            var height = 0
            val onDown: (Event) -> Unit = { event ->
                val pointer = event as PointerEvent
                dragging = true
                startY = pointer.clientY.toDouble()
                startHeight = panel.offsetHeight
                height = startHeight
                grip.setPointerCapture(pointer.pointerId)
            }
            val onMove: (Event) -> Unit = { event ->
                if (dragging) {
                    val wanted = startHeight + (startY - (event as PointerEvent).clientY).toInt()
                    height = wanted.coerceIn(TerminalSessions.MIN_HEIGHT, (window.innerHeight - PAGE_LEFT_VISIBLE).coerceAtLeast(TerminalSessions.MIN_HEIGHT))
                    setRootProperty("--terminal-height", "${height}px")
                }
            }
            val onUp: (Event) -> Unit = {
                if (dragging) {
                    dragging = false
                    resize(height)
                }
            }
            grip.addEventListener("pointerdown", onDown)
            grip.addEventListener("pointermove", onMove)
            grip.addEventListener("pointerup", onUp)
            grip.addEventListener("pointercancel", onUp)
            onDispose {
                grip.removeEventListener("pointerdown", onDown)
                grip.removeEventListener("pointermove", onMove)
                grip.removeEventListener("pointerup", onUp)
                grip.removeEventListener("pointercancel", onUp)
            }
        }
    }
}

/**
 * Keeps `--terminal-viewport` and `--terminal-top`: the part of the screen that the on-screen
 * keyboard leaves free. The phone sheet fills it, so the key row sits on the keyboard.
 */
@Composable
private fun PhoneViewport() {
    DisposableEffect(Unit) {
        val viewport = window.asDynamic().visualViewport
        val update: (Event) -> Unit = {
            setRootProperty("--terminal-viewport", "${viewport.height}px")
            setRootProperty("--terminal-top", "${viewport.offsetTop}px")
        }
        viewport?.addEventListener("resize", update)
        viewport?.addEventListener("scroll", update)
        onDispose {
            viewport?.removeEventListener("resize", update)
            viewport?.removeEventListener("scroll", update)
            removeRootProperty("--terminal-viewport")
            removeRootProperty("--terminal-top")
        }
    }
}

private fun setRootProperty(name: String, value: String) {
    (document.documentElement as HTMLElement).style.setProperty(name, value)
}

private fun removeRootProperty(name: String) {
    (document.documentElement as HTMLElement).style.removeProperty(name)
}

private val TerminalLayout.className: String
    get() = when (this) {
        TerminalLayout.Docked -> "docked"
        TerminalLayout.Full -> "full"
        TerminalLayout.Minimized -> "minimized"
    }

/** How much of the page stays in view over the tallest docked panel, in pixels. */
private const val PAGE_LEFT_VISIBLE = 160
