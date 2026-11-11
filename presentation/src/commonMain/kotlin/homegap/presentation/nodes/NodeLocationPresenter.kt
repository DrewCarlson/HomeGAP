package homegap.presentation.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LocationDto
import homegap.api.NodeStatusDto
import homegap.api.PlaceDto
import homegap.api.StatusSnapshot
import homegap.presentation.addnode.LocationOptionModel
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.ControllerApi
import homegap.presentation.locations.LocationSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which node's location is being changed, if any; set by the Inspector's Change location. */
@SingleIn(AppScope::class)
@Inject
class NodeRelocation {
    private val _nodeId = MutableStateFlow<String?>(null)
    val nodeId: StateFlow<String?> = _nodeId.asStateFlow()

    fun open(id: String) {
        _nodeId.value = id
    }

    fun close() {
        _nodeId.value = null
    }
}

/**
 * Where a node is, being changed: search the known cities or pick a point on the map, then save.
 * The coordinates steer geo DNS and place the node on the maps.
 */
@Immutable
data class NodeLocationModel(
    val nodeId: String,
    /** Where it is now: "Mumbai · 19.08, 72.88", or null when not set. */
    val current: String?,
    val query: String,
    val matches: List<LocationOptionModel>,
    /** The place chosen and not saved yet. */
    val pending: String?,
    val pick: Pair<Double, Double>?,
    val onQuery: (String) -> Unit,
    /** A known city, by its catalog id. */
    val onChoose: (String) -> Unit,
    val onPickOnMap: (latitude: Double, longitude: Double) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The Change location dialog for whichever node [NodeRelocation] names, over any screen that
 * shows a node (Overview, Nodes).
 */
@SingleIn(AppScope::class)
@Inject
class NodeLocationPresenter(
    private val relocation: NodeRelocation,
    private val api: ControllerApi,
    private val actions: ActionRunner,
) {
    @Composable
    fun model(status: StatusSnapshot?): NodeLocationModel? {
        val nodeId by relocation.nodeId.collectAsState()
        val node = status?.nodes?.firstOrNull { it.id == nodeId } ?: return null
        // A fresh search and pick for each node.
        return key(node.id) {
            model(
                node = node,
                onClose = relocation::close,
            )
        }
    }

    @Composable
    private fun model(node: NodeStatusDto, onClose: () -> Unit): NodeLocationModel {
        val catalog by produceState(emptyList<LocationDto>()) {
            value = runCatchingCancellable { api.locations().locations }.getOrDefault(emptyList())
        }
        var query by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<PlaceDto?>(null) }
        val current = node.location
        val shown = pending ?: current
        return NodeLocationModel(
            nodeId = node.id,
            current = current?.let { describePlace(it) },
            query = query,
            matches = LocationSearch.search(catalog, query).map { location ->
                LocationOptionModel(
                    id = location.id,
                    label = LocationSearch.label(location),
                    detail = location.providers.take(4).joinToString(", "),
                )
            },
            pending = pending?.let { describePlace(it) },
            pick = shown?.let { it.latitude to it.longitude },
            onQuery = { query = it },
            onChoose = { id ->
                catalog.firstOrNull { it.id == id }?.let { location ->
                    query = ""
                    pending = PlaceDto(location.latitude, location.longitude, LocationSearch.label(location))
                }
            },
            onPickOnMap = { latitude, longitude ->
                val lat = LocationSearch.round2(latitude)
                val lon = LocationSearch.round2(longitude)
                pending = PlaceDto(lat, lon, LocationSearch.nearest(catalog, lat, lon)?.let { LocationSearch.label(it) })
            },
            onSave = {
                pending?.let { place ->
                    actions.run(
                        label = "Change location",
                        onSuccess = onClose,
                    ) {
                        api.setNodeLocation(node.id, place)
                    }
                }
            },
            onCancel = onClose,
        )
    }
}

/** "Mumbai, India · 19.08, 72.88", noting a point that is only the region's center. */
private fun describePlace(place: PlaceDto): String =
    place.name?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty() + "${place.latitude}, ${place.longitude}" +
        if (place.estimated) " (the region's center; not set)" else ""
