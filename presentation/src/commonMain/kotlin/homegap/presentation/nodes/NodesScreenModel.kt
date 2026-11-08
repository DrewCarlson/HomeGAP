package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel

@Immutable
data class NodesScreenModel(
    val nodes: List<NodeRowModel>,
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
