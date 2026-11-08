package homegap.presentation.nodes

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerApi
import homegap.presentation.data.PauseScope

data class NodesScreenProps(val status: StatusSnapshot, val now: Long)

@SingleIn(AppScope::class)
@Inject
class NodesScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
) : Presenter<NodesScreenProps, NodesScreenModel> {
    @Composable
    override fun model(props: NodesScreenProps): NodesScreenModel {
        val rows = remember(props.status.nodes, props.now) { props.status.nodes.map { it.toRowModel(props.now) } }
        val mesh = remember(props.status.mesh, props.now) { props.status.mesh?.toPanelModel(props.now) }
        val events = remember { nodeEvents() }
        return NodesScreenModel(rows, events, mesh)
    }

    private fun nodeEvents() = NodeEvents(
        onRetryUpdate = { id -> actions.run("Retry update") { api.retryUpdate(id) } },
        onSkipUpdate = { id -> actions.run("Skip update") { api.skipUpdate(id) } },
        onReprovision = { id -> actions.run("Re-provision") { api.provision(id) } },
        onTogglePause = { id, paused ->
            actions.run(if (paused) "Resume" else "Pause") { api.pause(PauseScope.Node, id, !paused) }
        },
        onRemove = { id -> actions.run("Remove") { api.removeNode(id) } },
        onDecommission = { id, typed -> actions.run("Decommission") { api.decommission(id, typed) } },
    )
}
