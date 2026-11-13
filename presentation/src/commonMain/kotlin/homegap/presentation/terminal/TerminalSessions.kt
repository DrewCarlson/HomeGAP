package homegap.presentation.terminal

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ApiException
import homegap.presentation.data.AppCoroutineScope
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.Preferences
import homegap.presentation.ui.ConfirmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the connection of a tab is doing. */
enum class TerminalStatus {
    Connecting,
    Connected,
    Reconnecting,

    /** Another window shows the session. */
    Elsewhere,

    /** The orchestrator no longer has the session. */
    Gone,
}

/** One shell on a node, as a tab. */
data class TerminalTab(
    val id: String,
    val nodeId: String,
    val status: TerminalStatus = TerminalStatus.Connecting,
    /** A key for one connection; a new one arrives for each attempt. */
    val ticket: String? = null,
    val exitCode: Int? = null,
    /** Connections that failed in a row. */
    val failures: Int = 0,
) {
    /** Closing the tab ends a shell that still runs. */
    val running: Boolean get() = exitCode == null && status != TerminalStatus.Gone
}

data class TerminalState(
    /** False until the orchestrator says that `dashboard.terminal` is on. */
    val enabled: Boolean = false,
    val tabs: List<TerminalTab> = emptyList(),
    val selected: String? = null,
    val layout: TerminalLayout = TerminalLayout.Docked,
    val height: Int = TerminalSessions.DEFAULT_HEIGHT,
    val fontSize: Int = TerminalSessions.DEFAULT_FONT_SIZE,
    val focusRequests: Int = 0,
)

/**
 * The shells that this dashboard shows: it opens and closes them on the orchestrator, and gets a
 * ticket for each connection of a tab, again with a growing wait when a connection drops. The
 * sessions live on the orchestrator, so [restore] brings back the tabs after a reload.
 */
@SingleIn(AppScope::class)
@Inject
class TerminalSessions(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val preferences: Preferences,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        TerminalState(
            height = preferences.get(HEIGHT_KEY)?.toIntOrNull() ?: DEFAULT_HEIGHT,
            fontSize = preferences.get(FONT_SIZE_KEY)?.toIntOrNull() ?: DEFAULT_FONT_SIZE,
        )
    )
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /** The Terminal actions show only when the orchestrator allows terminals. */
    val enabled: Boolean get() = _state.value.enabled

    private val attaching = mutableMapOf<String, Job>()

    /** Reads the open sessions and adds a tab for each one that has none. */
    suspend fun restore() {
        val overview = runCatchingCancellable { api.terminals() }.getOrNull() ?: return
        val known = _state.value.tabs.map { it.id }.toSet()
        val restored = overview.sessions.filter { it.id !in known }.map { session ->
            TerminalTab(
                id = session.id,
                nodeId = session.nodeId,
                exitCode = session.exitCode,
            )
        }
        _state.update { state ->
            state.copy(
                enabled = overview.enabled,
                tabs = state.tabs + restored,
                selected = state.selected ?: restored.firstOrNull()?.id,
            )
        }
        restored.forEach { attach(it.id) }
    }

    /** Opens a shell on [nodeId] in a new tab and shows it. */
    fun open(nodeId: String) {
        scope.launch {
            runCatchingCancellable { api.openTerminal(nodeId, INITIAL_ROWS, INITIAL_COLUMNS) }
                .onSuccess { session ->
                    _state.update { state ->
                        state.copy(
                            tabs = state.tabs + TerminalTab(id = session.id, nodeId = session.nodeId),
                            selected = session.id,
                            layout = state.layout.shown(),
                            focusRequests = state.focusRequests + 1,
                        )
                    }
                    attach(session.id)
                }
                .onFailure { actions.notify("Terminal on $nodeId failed: ${it.message}", isError = true) }
        }
    }

    fun onEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Select -> _state.update { state ->
                state.copy(
                    selected = event.id,
                    layout = state.layout.shown(),
                    focusRequests = state.focusRequests + 1,
                )
            }

            is TerminalEvent.Close -> close(event.id)
            is TerminalEvent.SetLayout -> _state.update { it.copy(layout = event.layout) }
            is TerminalEvent.Resize -> {
                val height = event.height.coerceAtLeast(MIN_HEIGHT)
                preferences.set(HEIGHT_KEY, height.toString())
                _state.update { it.copy(height = height) }
            }

            is TerminalEvent.Zoom -> {
                val fontSize = (_state.value.fontSize + event.delta).coerceIn(FONT_SIZES)
                preferences.set(FONT_SIZE_KEY, fontSize.toString())
                _state.update { it.copy(fontSize = fontSize) }
            }

            is TerminalEvent.UseHere -> {
                updateTab(event.id) { it.copy(status = TerminalStatus.Connecting, failures = 0) }
                _state.update { it.copy(focusRequests = it.focusRequests + 1) }
                attach(event.id)
            }

            is TerminalEvent.Connected -> updateTab(event.id) { it.copy(status = TerminalStatus.Connected, failures = 0) }
            is TerminalEvent.Disconnected -> disconnected(event.id, event.reason)
            is TerminalEvent.Exited -> updateTab(event.id) { it.copy(exitCode = event.code) }
        }
    }

    private fun disconnected(id: String, reason: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        when {
            // The scrollback stays; there is nothing more to receive.
            tab.exitCode != null || tab.status == TerminalStatus.Gone -> Unit
            reason == TAKEN_OVER -> {
                attaching.remove(id)?.cancel()
                updateTab(id) { it.copy(status = TerminalStatus.Elsewhere) }
            }

            else -> {
                updateTab(id) { it.copy(status = TerminalStatus.Reconnecting, failures = it.failures + 1) }
                attach(id)
            }
        }
    }

    /** Gets a ticket for [id], after the wait that its failures call for; the view then connects. */
    private fun attach(id: String) {
        attaching.remove(id)?.cancel()
        attaching[id] = scope.launch {
            while (true) {
                val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return@launch
                delay(retryDelayAfter(tab.failures))
                val result = runCatchingCancellable { api.terminalTicket(id) }
                val error = result.exceptionOrNull()
                when {
                    error == null -> {
                        updateTab(id) { it.copy(ticket = result.getOrThrow().ticket) }
                        return@launch
                    }

                    error is ApiException && error.status in SESSION_GONE -> {
                        updateTab(id) { it.copy(status = TerminalStatus.Gone) }
                        return@launch
                    }

                    else -> updateTab(id) { it.copy(status = TerminalStatus.Reconnecting, failures = it.failures + 1) }
                }
            }
        }
    }

    private fun close(id: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        if (!tab.running) {
            remove(tab)
            return
        }
        confirmations.ask(
            ConfirmModel(
                title = "Close the terminal on ${tab.nodeId}?",
                text = "The shell is still running. Closing the tab ends it, and what runs in it.",
                confirmLabel = "Close the terminal",
            )
        ) {
            remove(tab)
        }
    }

    private fun remove(tab: TerminalTab) {
        attaching.remove(tab.id)?.cancel()
        _state.update { state ->
            val index = state.tabs.indexOfFirst { it.id == tab.id }
            val tabs = state.tabs.filterNot { it.id == tab.id }
            state.copy(
                tabs = tabs,
                selected = if (state.selected == tab.id) tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id else state.selected,
                layout = if (tabs.isEmpty()) state.layout.shown() else state.layout,
            )
        }
        if (tab.status == TerminalStatus.Gone) return
        scope.launch {
            runCatchingCancellable { api.closeTerminal(tab.id) }
                .onFailure { actions.notify("Close terminal failed: ${it.message}", isError = true) }
        }
    }

    private fun updateTab(id: String, change: (TerminalTab) -> TerminalTab) {
        _state.update { state -> state.copy(tabs = state.tabs.map { if (it.id == id) change(it) else it }) }
    }

    /** A new or selected tab opens a minimized panel again. */
    private fun TerminalLayout.shown(): TerminalLayout = if (this == TerminalLayout.Minimized) TerminalLayout.Docked else this

    companion object {
        const val DEFAULT_HEIGHT = 320
        const val MIN_HEIGHT = 120
        const val DEFAULT_FONT_SIZE = 13
        val FONT_SIZES = 9..24

        /** The size of a new shell until the view has measured its own. */
        private const val INITIAL_ROWS = 24
        private const val INITIAL_COLUMNS = 80
        private const val HEIGHT_KEY = "terminal.height"
        private const val FONT_SIZE_KEY = "terminal.font"

        /** The close reason of the orchestrator when a newer connection shows the session. */
        const val TAKEN_OVER = "opened in another window"

        /** The ticket call answers 400 or 404 for a session that is gone. */
        private val SESSION_GONE = setOf(400, 404)

        /** No wait for the first connection; then 1 s, 2 s, and 5 s from the third failure on. */
        fun retryDelayAfter(failures: Int): Duration = when {
            failures <= 0 -> Duration.ZERO
            failures == 1 -> 1.seconds
            failures == 2 -> 2.seconds
            else -> 5.seconds
        }
    }
}
