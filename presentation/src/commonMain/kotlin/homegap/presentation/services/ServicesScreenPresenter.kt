package homegap.presentation.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.ServiceStatusDto
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.overview.MatrixCellModel
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.on
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.rememberProblemsFilter
import homegap.presentation.ui.statusLineOf

data class ServicesScreenProps(
    val status: StatusSnapshot,
    /** The selected hostname. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class ServicesScreenPresenter(
    private val serviceActions: ServiceActions,
) : Presenter<ServicesScreenProps, ServicesScreenModel> {
    @Composable
    override fun model(props: ServicesScreenProps): ServicesScreenModel {
        val status = props.status
        var query by remember { mutableStateOf("") }
        val edges = status.nodes.map { it.id }
        // Hostnames that more than one service has: each of those rows needs its own Inspector.
        val shared = status.services.groupingBy { it.hostname }.eachCount().filterValues { it > 1 }.keys
        val filter = rememberProblemsFilter(
            total = status.services.size,
            problems = status.services.count { it.health() != Health.OK },
        )
        val rows = status.services
            .filter { !filter.problemsOnly || it.health() != Health.OK }
            .filter { query.isBlank() || query.trim().lowercase().let { q -> q in it.hostname.lowercase() || q in it.container.lowercase() } }
            .sortedWith(compareBy<ServiceStatusDto> { it.health().rank() }.thenBy { it.hostname })
            .map { it.toRow(edges, shared) }
        val selected = status.services.firstOrNull { it.key == props.selection }
            ?: status.services.firstOrNull { it.hostname == props.selection }
        return ServicesScreenModel(
            head = PageHeadModel(
                caption = "Services",
                headline = "${status.services.size} services published.",
                statusLine = statusLineOf(status.attention.on(Route.Services)),
            ),
            query = query,
            filter = filter,
            edges = edges,
            rows = rows,
            onQuery = { query = it },
            selected = selected?.let { Location(Route.Services, it.selection(shared)) },
            inspector = selected?.let { service ->
                serviceInspector(
                    service = service,
                    nodes = status.nodes,
                    actions = serviceActions.forService(service),
                )
            },
            empty = if (status.services.isEmpty()) {
                EmptyStateModel(
                    title = "No services published yet",
                    text = "Label a container on the homelab with homegap.expose and HomeGAP publishes it through every node " +
                        "on its next pass. A failing service shows up here with the reason.",
                    action = ActionModel(
                        label = "How to label a container",
                        href = "/docs/services/",
                    ),
                )
            } else {
                null
            },
        )
    }

    /** The hostname selects a service, or its key when another service has the same hostname. */
    private fun ServiceStatusDto.selection(shared: Set<String>): String = if (hostname in shared) key else hostname

    private fun ServiceStatusDto.toRow(edges: List<String>, shared: Set<String>): ServiceRowModel = ServiceRowModel(
        hostname = hostname,
        container = container + name?.let { " · $it" }.orEmpty(),
        publishedAs = if (ports.isNotEmpty()) ports.joinToString(", ") else listOfNotNull(protocol, tlsLine(tls) ?: tls).joinToString(" · "),
        access = access + if (auth != "none") " · $auth" else "",
        paused = paused,
        health = health(),
        cells = edges.map { edge ->
            val through = perNode.firstOrNull { it.nodeId == edge }
            val code = through?.code
            val ms = through?.ms
            when {
                through == null -> MatrixCellModel("—", Health.UNKNOWN, "$edge does not serve it")
                code != null && code >= 500 -> MatrixCellModel(code.toString(), Health.DOWN, "HTTP $code")
                through.health == Health.DOWN -> MatrixCellModel("✗", Health.DOWN, through.describe())
                ms != null -> MatrixCellModel(ms.toLong().toString(), through.health, "${ms.toLong()} ms · ${through.describe()}")
                through.health == Health.OK -> MatrixCellModel("✓", Health.OK, through.describe())
                else -> MatrixCellModel("▲", through.health, through.describe())
            }
        },
        location = Location(Route.Services, selection(shared)),
    )
}

/** The worst of the edges' health for this service. */
internal fun ServiceStatusDto.health(): Health {
    val healths = perNode.map { it.health }
    return when {
        Health.DOWN in healths -> Health.DOWN
        Health.DEGRADED in healths -> Health.DEGRADED
        healths.isNotEmpty() && healths.all { it == Health.OK } -> Health.OK
        else -> Health.UNKNOWN
    }
}

private fun Health.rank(): Int = when (this) {
    Health.DOWN -> 0
    Health.DEGRADED -> 1
    Health.UNKNOWN -> 2
    Health.OK -> 3
}
