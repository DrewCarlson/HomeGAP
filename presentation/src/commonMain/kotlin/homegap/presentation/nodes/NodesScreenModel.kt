package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel

@Immutable
data class NodesScreenModel(
    val nodes: List<NodeRowModel>,
    val addNode: AddNodeFormModel,
    val events: NodeEvents,
) : ScreenModel

/** Per-node actions; the view asks for confirmation where an action is destructive. */
@Immutable
class NodeEvents(
    val onRetryUpdate: (nodeId: String) -> Unit,
    val onSkipUpdate: (nodeId: String) -> Unit,
    val onReprovision: (nodeId: String) -> Unit,
    val onTogglePause: (nodeId: String, paused: Boolean) -> Unit,
    val onRemove: (nodeId: String) -> Unit,
    val onDecommission: (nodeId: String, typedId: String) -> Unit,
)

/** Fields of the add-node form, in display order. */
enum class NodeField(val label: String, val placeholder: String) {
    Id("Node id", "eu1"),
    Host("SSH host / IP", "203.0.113.30"),
    Region("Region code", "EU"),
    Provider("Provider", "optional"),
    Os("OS", "ubuntu-24.04"),
    User("SSH user", "deploy"),
    KeyFile("SSH key file", "/secrets/ssh/eu1"),
    Latitude("Latitude", "50.1"),
    Longitude("Longitude", "8.7"),
}

@Immutable
data class AddNodeFormModel(
    val values: Map<NodeField, String>,
    val canSubmit: Boolean,
    val onChange: (NodeField, String) -> Unit,
    val onSubmit: () -> Unit,
)
