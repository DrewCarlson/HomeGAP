package homegap.presentation.nodes

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.NodeConfigDto
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
        val events = remember { nodeEvents() }
        val busy by actions.busy.collectAsState()
        return NodesScreenModel(rows, addNodeForm(busy), events)
    }

    @Composable
    private fun addNodeForm(busy: Boolean): AddNodeFormModel {
        var values by remember { mutableStateOf(DEFAULT_VALUES) }
        val onChange = remember { { field: NodeField, value: String -> values = values + (field to value) } }
        val onSubmit = remember {
            { actions.run("Add node") { api.addNode(values.toNodeConfig()) } }
        }
        val complete = listOf(NodeField.Id, NodeField.Host, NodeField.KeyFile).all { values[it].orEmpty().isNotBlank() }
        return AddNodeFormModel(values, canSubmit = complete && !busy, onChange, onSubmit)
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

    private companion object {
        val DEFAULT_VALUES = mapOf(NodeField.Os to "ubuntu-24.04", NodeField.User to "deploy")

        fun Map<NodeField, String>.toNodeConfig(): NodeConfigDto {
            fun field(f: NodeField) = this[f].orEmpty().trim()
            return NodeConfigDto(
                id = field(NodeField.Id),
                region = field(NodeField.Region),
                provider = field(NodeField.Provider),
                host = field(NodeField.Host),
                os = field(NodeField.Os),
                sshUser = field(NodeField.User),
                sshKeyFile = field(NodeField.KeyFile),
                latitude = field(NodeField.Latitude).toDoubleOrNull(),
                longitude = field(NodeField.Longitude).toDoubleOrNull(),
            )
        }
    }
}
