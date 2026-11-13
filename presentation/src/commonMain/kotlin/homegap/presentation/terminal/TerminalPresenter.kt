package homegap.presentation.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.presentation.core.Presenter

/** [connected] once the orchestrator answers: the open sessions are read then. */
data class TerminalProps(val connected: Boolean)

/** The terminal panel of the shell; null while no session is open. */
@SingleIn(AppScope::class)
@Inject
class TerminalPresenter(
    private val sessions: TerminalSessions,
) : Presenter<TerminalProps, TerminalPanelModel?> {
    @Composable
    override fun model(props: TerminalProps): TerminalPanelModel? {
        LaunchedEffect(props.connected) {
            if (props.connected) {
                sessions.restore()
            }
        }
        val state by sessions.state.collectAsState()
        val commands = remember { COMMANDS }
        val onEvent = remember { sessions::onEvent }
        val selected = state.selected ?: return null
        return TerminalPanelModel(
            tabs = state.tabs.map { tab ->
                val sameNode = state.tabs.filter { it.nodeId == tab.nodeId }
                TerminalTabModel(
                    id = tab.id,
                    label = if (sameNode.size > 1) "${tab.nodeId} · ${sameNode.indexOf(tab) + 1}" else tab.nodeId,
                    ticket = tab.ticket,
                    notice = notice(tab),
                    ended = !tab.running,
                )
            },
            selected = selected,
            layout = state.layout,
            height = state.height,
            fontSize = state.fontSize,
            commands = commands,
            focusRequests = state.focusRequests,
            onEvent = onEvent,
        )
    }

    private fun notice(tab: TerminalTab): TerminalNoticeModel? {
        val exitCode = tab.exitCode
        return when {
            exitCode != null -> TerminalNoticeModel("The shell ended (exit code $exitCode)")
            tab.status == TerminalStatus.Connecting -> TerminalNoticeModel("Connecting…")
            tab.status == TerminalStatus.Reconnecting -> TerminalNoticeModel("Reconnecting…")
            tab.status == TerminalStatus.Elsewhere -> TerminalNoticeModel(
                text = "Opened in another window",
                actionLabel = "Use here",
                action = TerminalEvent.UseHere(tab.id),
            )

            tab.status == TerminalStatus.Gone -> TerminalNoticeModel(
                text = "The session ended",
                actionLabel = "Close",
                action = TerminalEvent.Close(tab.id),
            )

            else -> null
        }
    }

    private companion object {
        val COMMANDS = listOf(
            "docker ps --format 'table {{.Names}}\\t{{.Status}}'",
            "docker compose logs -f --tail 100 traefik",
            "docker compose logs -f --tail 100 pangolin",
            "docker compose logs -f --tail 100 gerbil",
            "sudo wg show",
            "df -h /",
            "free -m",
        )
    }
}
