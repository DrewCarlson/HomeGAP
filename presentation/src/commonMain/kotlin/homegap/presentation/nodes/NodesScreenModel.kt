package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.presentation.core.ScreenModel

@Immutable
data class NodesScreenModel(
    val nodes: List<NodeRowModel>,
    val events: NodeEvents,
    /** The cluster mesh; null when the cluster is not configured. */
    val mesh: MeshPanelModel? = null,
    /** The cluster's database layer; null when the cluster is not configured. */
    val database: DatabasePanelModel? = null,
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
    /** Make a cluster member the database primary now. */
    val onSwitchPrimary: (memberId: String) -> Unit = {},
)
