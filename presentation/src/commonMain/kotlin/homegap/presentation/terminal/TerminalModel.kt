package homegap.presentation.terminal

import androidx.compose.runtime.Immutable

/** How the terminal panel sits in the shell. Phones show [Docked] and [Full] as a full-screen sheet. */
enum class TerminalLayout {
    Docked,
    Full,
    Minimized,
}

/**
 * The terminal panel under every screen, while at least one session is open: one tab per shell
 * on a node. The view owns the terminal emulators and their sockets, and reports what happens to
 * them through [onEvent].
 */
@Immutable
data class TerminalPanelModel(
    val tabs: List<TerminalTabModel>,
    /** The id of the tab that shows. */
    val selected: String,
    val layout: TerminalLayout,
    /** The height of the docked panel, in pixels. */
    val height: Int,
    /** The font size of the terminals, in pixels. */
    val fontSize: Int,
    /** Read-only commands that the Commands menu types into the shell. */
    val commands: List<String>,
    /** Goes up each time the user opens or selects a tab: the view then focuses the terminal. */
    val focusRequests: Int,
    val onEvent: (TerminalEvent) -> Unit,
)

@Immutable
data class TerminalTabModel(
    val id: String,
    /** The node id, with an index when the node has two shells. */
    val label: String,
    /** A key for one connection. The view connects each time it changes. */
    val ticket: String?,
    /** What to say over the terminal: reconnecting, the session ended, the shell ended. */
    val notice: TerminalNoticeModel?,
    /** The shell or its session ended: closing the tab loses nothing. */
    val ended: Boolean,
)

/** A line over the terminal, with at most one button. */
@Immutable
data class TerminalNoticeModel(
    val text: String,
    val actionLabel: String? = null,
    val action: TerminalEvent? = null,
)

/** What the user, or the connection of a tab, did in the terminal panel. */
sealed interface TerminalEvent {
    data class Select(val id: String) : TerminalEvent

    /** Closes the tab and ends its session; asks first while the shell runs. */
    data class Close(val id: String) : TerminalEvent

    data class SetLayout(val layout: TerminalLayout) : TerminalEvent

    /** The drag handle was released with the panel [height] pixels tall. */
    data class Resize(val height: Int) : TerminalEvent

    /** Font size up (+1) or down (-1). */
    data class Zoom(val delta: Int) : TerminalEvent

    /** Shows the session here after another window took it. */
    data class UseHere(val id: String) : TerminalEvent

    /** The socket of the tab is open and the orchestrator answered. */
    data class Connected(val id: String) : TerminalEvent

    /** The socket of the tab closed, with the [reason] that the orchestrator gave. */
    data class Disconnected(val id: String, val reason: String) : TerminalEvent

    /** The shell ended with [code]. */
    data class Exited(val id: String, val code: Int) : TerminalEvent
}
