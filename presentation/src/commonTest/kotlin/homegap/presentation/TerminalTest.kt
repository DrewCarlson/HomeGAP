package homegap.presentation

import homegap.api.TerminalOverviewDto
import homegap.api.TerminalSessionDto
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.AlwaysVisible
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerSession
import homegap.presentation.data.PausedGuard
import homegap.presentation.nodes.NodeActions
import homegap.presentation.nodes.NodeRelocation
import homegap.presentation.terminal.TerminalEvent
import homegap.presentation.terminal.TerminalLayout
import homegap.presentation.terminal.TerminalPanelModel
import homegap.presentation.terminal.TerminalPresenter
import homegap.presentation.terminal.TerminalProps
import homegap.presentation.terminal.TerminalSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTest {
    private class Graph(scope: CoroutineScope, enabled: Boolean = true, sessions: List<TerminalSessionDto> = emptyList()) {
        val api = FakeControllerApi().apply { terminalsValue = TerminalOverviewDto(enabled, sessions) }
        val session = ControllerSession(api, AlwaysVisible)
        val actions = ActionRunner(session, api, scope)
        val confirmations = Confirmations()
        val preferences = FakePreferences()
        val terminals = TerminalSessions(api, actions, confirmations, preferences, scope)
        val presenter = TerminalPresenter(terminals)
        val nodeActions = NodeActions(
            api = api,
            actions = actions,
            confirmations = confirmations,
            pausedGuard = PausedGuard(session, api, actions, confirmations),
            relocation = NodeRelocation(),
            terminals = terminals,
        )
    }

    private fun TestScope.panel(graph: Graph): StateFlow<TerminalPanelModel?> =
        present(graph.presenter, TerminalProps(connected = true))

    private suspend fun StateFlow<TerminalPanelModel?>.await(condition: (TerminalPanelModel) -> Boolean): TerminalPanelModel =
        first { it != null && condition(it) }!!

    @Test
    fun noPanelWithoutASession() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.state.first { it.enabled }
        assertNull(models.value)
    }

    @Test
    fun openAddsATabSelectsItAndGetsATicket() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.singleOrNull()?.ticket != null }
        assertEquals("t1", model.selected)
        assertEquals("a1", model.tabs.single().label)
        assertEquals("t1-ticket-1", model.tabs.single().ticket)
        assertEquals(1, model.focusRequests)
        assertTrue("terminal a1 24 80" in g.api.calls)

        g.terminals.open("b1")
        val two = models.await { it.tabs.size == 2 }
        assertEquals("t2", two.selected)
        assertEquals(listOf("a1", "b1"), two.tabs.map { it.label })
    }

    @Test
    fun aNodeWithTwoShellsNumbersItsTabs() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        g.terminals.open("a1")
        val model = models.await { it.tabs.size == 2 }
        assertEquals(listOf("a1 · 1", "a1 · 2"), model.tabs.map { it.label })
    }

    @Test
    fun openingATabShowsAMinimizedPanelAgain() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.size == 1 }
        model.onEvent(TerminalEvent.SetLayout(TerminalLayout.Minimized))
        assertEquals(TerminalLayout.Minimized, models.await { it.layout != TerminalLayout.Docked }.layout)
        g.terminals.open("b1")
        assertEquals(TerminalLayout.Docked, models.await { it.tabs.size == 2 }.layout)
    }

    @Test
    fun closingARunningShellAsksFirstThenEndsTheSession() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        g.terminals.open("b1")
        val model = models.await { it.tabs.size == 2 }

        model.onEvent(TerminalEvent.Close("t2"))
        assertEquals("Close the terminal on b1?", g.confirmations.pending.value?.model?.title)
        assertEquals(2, models.value?.tabs?.size, "nothing closes before the answer")
        g.confirmations.resolve(confirmed = true)
        val one = models.await { it.tabs.size == 1 }
        assertEquals("t1", one.selected, "the tab beside the closed one shows")
        g.terminals.state.first { "close terminal t2" in g.api.calls }

        one.onEvent(TerminalEvent.Close("t1"))
        g.confirmations.resolve(confirmed = true)
        models.first { it == null }
    }

    @Test
    fun aShellThatEndedClosesWithoutAQuestionAndKeepsItsNotice() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.singleOrNull()?.ticket != null }
        model.onEvent(TerminalEvent.Connected("t1"))
        model.onEvent(TerminalEvent.Exited("t1", 7))
        model.onEvent(TerminalEvent.Disconnected("t1", ""))
        val ended = models.await { it.tabs.single().ended }.tabs.single()
        assertEquals("The shell ended (exit code 7)", ended.notice?.text)
        assertEquals("t1-ticket-1", ended.ticket, "no reconnect after the shell ended")

        model.onEvent(TerminalEvent.Close("t1"))
        assertNull(g.confirmations.pending.value)
        models.first { it == null }
    }

    @Test
    fun theOpenSessionsComeBackAsTabs() = runTest {
        val g = Graph(
            scope = backgroundScope,
            sessions = listOf(
                TerminalSessionDto(id = "s1", nodeId = "a1", startedAt = NOW),
                TerminalSessionDto(id = "s2", nodeId = "b1", startedAt = NOW, exitCode = 0),
            ),
        )
        val model = panel(g).await { model -> model.tabs.size == 2 && model.tabs.all { it.ticket != null } }
        assertEquals(listOf("a1", "b1"), model.tabs.map { it.label })
        assertEquals("s1", model.selected)
        assertEquals(0, model.focusRequests, "a restored tab does not take the focus")
        assertEquals("The shell ended (exit code 0)", model.tabs[1].notice?.text)
    }

    @Test
    fun theTerminalActionsShowOnlyWhenTerminalsAreOn() = runTest {
        val off = Graph(backgroundScope, enabled = false)
        off.terminals.restore()
        assertFalse(off.nodeActions.forNode(node("a1")).any { it.label == "Terminal" })
        assertTrue(off.nodeActions.rowMenu("a1").isEmpty())

        val on = Graph(backgroundScope)
        on.terminals.restore()
        assertNotNull(on.nodeActions.forNode(node("a1")).singleOrNull { it.label == "Terminal" })
        on.nodeActions.rowMenu("a1").single().onClick!!()
        on.terminals.state.first { it.tabs.size == 1 }
        assertEquals("a1", on.terminals.state.value.tabs.single().nodeId)
    }

    @Test
    fun aFailedOpenSaysSo() = runTest {
        val g = Graph(backgroundScope)
        val api = g.api
        val failing = object : homegap.presentation.data.ControllerApi by api {
            override suspend fun openTerminal(node: String, rows: Int, columns: Int): TerminalSessionDto =
                throw homegap.presentation.data.ApiException(409, "a1 has 2 terminals open already")
        }
        val terminals = TerminalSessions(failing, g.actions, g.confirmations, g.preferences, backgroundScope)
        terminals.open("a1")
        val toast = g.actions.toasts.first { it.isNotEmpty() }.single()
        assertTrue(toast.isError)
        assertEquals("Terminal on a1 failed: a1 has 2 terminals open already", toast.text)
        assertTrue(terminals.state.value.tabs.isEmpty())
    }

    @Test
    fun aDroppedConnectionTriesAgainWithALongerWaitEachTime() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.singleOrNull()?.ticket != null }
        model.onEvent(TerminalEvent.Connected("t1"))
        assertNull(models.await { it.tabs.single().notice == null }.tabs.single().notice)

        val waits = listOf(1_000L, 2_000L, 5_000L, 5_000L)
        waits.forEachIndexed { index, wait ->
            val start = currentTime
            val before = models.value!!.tabs.single().ticket
            model.onEvent(TerminalEvent.Disconnected("t1", ""))
            models.await { it.tabs.single().notice?.text == "Reconnecting…" }
            models.await { it.tabs.single().ticket != before }
            assertEquals(wait, currentTime - start, "wait ${index + 1}")
        }

        model.onEvent(TerminalEvent.Connected("t1"))
        models.await { it.tabs.single().notice == null }
        val start = currentTime
        val before = models.value!!.tabs.single().ticket
        model.onEvent(TerminalEvent.Disconnected("t1", ""))
        models.await { it.tabs.single().ticket != before }
        assertEquals(1_000L, currentTime - start, "a connection that worked starts the waits again")
    }

    @Test
    fun aSessionThatIsGoneSaysSoAndClosesWithoutACall() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.singleOrNull()?.ticket != null }
        model.onEvent(TerminalEvent.Connected("t1"))
        g.api.goneTerminals += "t1"
        model.onEvent(TerminalEvent.Disconnected("t1", ""))
        val notice = assertNotNull(models.await { it.tabs.single().ended }.tabs.single().notice)
        assertEquals("The session ended", notice.text)
        assertEquals("Close", notice.actionLabel)

        model.onEvent(assertNotNull(notice.action))
        models.first { it == null }
        assertNull(g.confirmations.pending.value)
        assertFalse(g.api.calls.any { it.startsWith("close terminal") })
    }

    @Test
    fun aSessionTakenByAnotherWindowComesBackWithUseHere() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.singleOrNull()?.ticket != null }
        model.onEvent(TerminalEvent.Connected("t1"))
        model.onEvent(TerminalEvent.Disconnected("t1", TerminalSessions.TAKEN_OVER))
        val notice = assertNotNull(models.await { it.tabs.single().notice?.action != null }.tabs.single().notice)
        assertEquals("Opened in another window", notice.text)
        assertEquals("Use here", notice.actionLabel)
        assertEquals("t1-ticket-1", models.value!!.tabs.single().ticket, "it does not take the session back by itself")

        model.onEvent(assertNotNull(notice.action))
        assertEquals("t1-ticket-2", models.await { it.tabs.single().ticket != "t1-ticket-1" }.tabs.single().ticket)
    }

    @Test
    fun theFontSizeAndTheHeightAreKept() = runTest {
        val g = Graph(backgroundScope)
        val models = panel(g)
        g.terminals.open("a1")
        val model = models.await { it.tabs.size == 1 }
        model.onEvent(TerminalEvent.Zoom(1))
        model.onEvent(TerminalEvent.Resize(40))
        val changed = models.await { it.fontSize != TerminalSessions.DEFAULT_FONT_SIZE && it.height != TerminalSessions.DEFAULT_HEIGHT }
        assertEquals(TerminalSessions.DEFAULT_FONT_SIZE + 1, changed.fontSize)
        assertEquals(TerminalSessions.MIN_HEIGHT, changed.height)

        val again = TerminalSessions(g.api, g.actions, g.confirmations, g.preferences, backgroundScope)
        assertEquals(changed.fontSize, again.state.value.fontSize)
        assertEquals(changed.height, again.state.value.height)
    }
}
