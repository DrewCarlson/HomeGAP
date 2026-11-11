package homegap.presentation.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.api.NodeStatusDto
import homegap.api.ServiceStatusDto
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.data.Preferences
import homegap.presentation.data.Samples
import homegap.presentation.format.TimeFormatter
import homegap.presentation.format.ago
import homegap.presentation.format.formatBytes
import homegap.presentation.format.label
import homegap.presentation.format.plural
import homegap.presentation.map.overviewMap
import homegap.presentation.nodes.NodeActions
import homegap.presentation.nodes.nodeInspector
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.services.ServiceActions
import homegap.presentation.services.serviceInspector
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.chartOf
import homegap.presentation.ui.formatMeasure
import homegap.presentation.ui.location

data class OverviewScreenProps(
    val status: StatusSnapshot,
    val now: Long,
    val metrics: Map<String, Map<String, Samples>> = emptyMap(),
    val log: List<LogEntryDto> = emptyList(),
    /** The selected edge id or service hostname. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class OverviewScreenPresenter(
    private val preferences: Preferences,
    private val nodeActions: NodeActions,
    private val serviceActions: ServiceActions,
    private val times: TimeFormatter,
) : Presenter<OverviewScreenProps, OverviewScreenModel> {
    @Composable
    override fun model(props: OverviewScreenProps): OverviewScreenModel {
        val status = props.status
        val now = props.now
        var view by remember {
            mutableStateOf(
                preferences.get(VIEW_KEY)?.let { saved -> NetworkView.entries.firstOrNull { it.name == saved } }
                    ?: NetworkView.Path
            )
        }
        val onView = remember {
            { chosen: NetworkView ->
                view = chosen
                preferences.set(VIEW_KEY, chosen.name)
            }
        }
        val map = remember(status, now / 60_000) { status.overviewMap(now) }
        return OverviewScreenModel(
            head = head(status, now),
            gauges = remember(status) { gauges(status) },
            attention = status.attention.map { item ->
                AttentionRowModel(
                    severity = Severity.of(item.severity),
                    title = item.title,
                    detail = item.detail,
                    since = ago(item.since, now),
                    location = item.location,
                )
            },
            network = NetworkModel(
                view = view,
                homelab = homelab(status),
                edges = status.nodes.map { edge(it, status, props.metrics[it.id]) },
                map = map,
                onView = onView,
            ),
            edges = status.nodes.map { edgeCard(it, props.metrics[it.id], now) },
            matrix = remember(status.services, status.nodes) { matrix(status) },
            system = system(status, now),
            recent = recent(props.log),
            inspector = inspector(status, props, now),
            empty = if (status.nodes.isEmpty()) {
                EmptyStateModel(
                    title = "No edge nodes yet",
                    text = "Add an edge node: a small VPS near your visitors. Then label a container on the homelab " +
                        "with homegap.expose and check it's reachable from the Services page.",
                    action = ActionModel(
                        label = "+ Add node",
                        style = ActionStyle.Primary,
                        location = Location(Route.AddNode),
                        mutates = true,
                    ),
                )
            } else {
                null
            },
        )
    }

    private fun head(status: StatusSnapshot, now: Long): PageHeadModel {
        val edges = status.nodes.size
        val healthy = status.nodes.count { it.health == Health.OK }
        val services = status.services.size
        val reachable = status.services.count { it.reachable() }
        val headline = when {
            services == 0 -> "$healthy of $edges edges healthy."
            reachable == services -> "All $services services reachable on $healthy of $edges edges."
            else -> "$reachable of $services services reachable on $healthy of $edges edges."
        }
        return PageHeadModel(
            caption = "Overview · whole system · updated ${ago(status.generatedAt, now)}",
            headline = headline,
        )
    }

    private fun gauges(status: StatusSnapshot): List<GaugeModel> = buildList {
        add(ratio("Edges ok", status.nodes.count { it.health == Health.OK }, status.nodes.size))
        add(ratio("Tunnels up", status.tunnels.count { it.up }, status.tunnels.size))
        add(ratio("Services up", status.services.count { it.reachable() }, status.services.size))
        add(ratio("DNS in sync", status.dns.count { it.inSync }, status.dns.size))
        status.mesh?.let { mesh ->
            val links = mesh.members.flatMap { it.peers }
            val up = links.count { peer -> peer.probe?.let { it.rttMs != null } ?: (peer.lastHandshakeAt != null) }
            add(ratio("Mesh links", up, links.size))
        }
        status.cluster?.let { cluster ->
            val lag = cluster.members.mapNotNull { it.lagBytes }.maxOrNull()
            val lagTime = cluster.members.mapNotNull { it.lagMs }.maxOrNull()
            add(
                GaugeModel(
                    label = "Replica lag",
                    value = lagTime?.let { homegap.presentation.nodes.formatDuration(it) } ?: lag?.let { formatBytes(it) } ?: "—",
                    fraction = ((lag ?: 0L).toDouble() / LAG_FULL_SCALE).coerceIn(0.0, 1.0),
                    health = when {
                        lag == null -> Health.UNKNOWN
                        lag > LAG_FULL_SCALE -> Health.DEGRADED
                        else -> Health.OK
                    },
                )
            )
        }
    }

    private fun ratio(label: String, ok: Int, total: Int) = GaugeModel(
        label = label,
        value = "$ok / $total",
        fraction = if (total == 0) 0.0 else ok.toDouble() / total,
        health = when {
            total == 0 -> Health.UNKNOWN
            ok == total -> Health.OK
            ok == 0 -> Health.DOWN
            else -> Health.DEGRADED
        },
    )

    private fun homelab(status: StatusSnapshot): HomelabModel = HomelabModel(
        place = status.homelab?.name ?: "Homelab",
        facts = buildList {
            status.homelabContainers?.let { add(plural(it, "container")) }
            add(plural(status.services.size, "service"))
            status.cluster?.primary?.let { add("database primary: $it") }
            status.mesh?.let { mesh ->
                val links = mesh.members.flatMap { it.peers }
                add("mesh ${links.count { it.probe?.rttMs != null }}/${links.size}")
            }
        },
    )

    private fun edge(node: NodeStatusDto, status: StatusSnapshot, samples: Map<String, Samples>?): EdgeModel {
        val names = status.services
            .filter { service -> service.perNode.any { it.nodeId == node.id } }
            .map { it.hostname.substringBefore('.') }
        return EdgeModel(
            id = node.id,
            place = node.location?.name ?: node.region,
            health = node.health,
            api = listOfNotNull(
                "API ${node.apiProbe() ?: "—"} ms",
                samples?.get("rps")?.latest?.let { "${formatMeasure(it, "r/s")} r/s" },
            ).joinToString(" · "),
            mesh = samples?.get("mesh")?.latest?.let { "mesh ${it.toLong()} ms" },
            names = when {
                names.isEmpty() -> "no services"
                names.size <= 3 -> names.joinToString(", ")
                else -> names.take(3).joinToString(", ") + " +${names.size - 3}"
            },
            location = Location(Route.Overview, node.id),
        )
    }

    private fun edgeCard(node: NodeStatusDto, samples: Map<String, Samples>?, now: Long): EdgeCardModel {
        val api = node.checks.firstOrNull { it.name == "pangolin-api" }
        return EdgeCardModel(
            id = node.id,
            place = node.location?.name ?: node.region,
            health = node.health,
            status = node.health.label,
            chart = chartOf(
                label = "Pangolin API probe",
                samples = samples?.get("api"),
                nowSeconds = now / 1000,
                threshold = api?.threshold,
            ),
            facts = listOfNotNull(
                samples?.get("rps")?.latest?.let { "${formatMeasure(it, "r/s")} r/s" },
                samples?.get("egress")?.latest?.let { "egress ${formatMeasure(it, "Mb/s")} Mb/s" },
                samples?.get("mesh")?.latest?.let { "mesh ${it.toLong()} ms" },
                samples?.get("in")?.latest?.let { "in ${formatMeasure(it, "Mb/s")} Mb/s" },
                samples?.get("out")?.latest?.let { "out ${formatMeasure(it, "Mb/s")} Mb/s" },
            ),
            location = Location(Route.Overview, node.id),
        )
    }

    private fun matrix(status: StatusSnapshot): MatrixModel {
        val edges = status.nodes.map { it.id }
        val rows = status.services
            .sortedWith(compareBy<ServiceStatusDto> { it.rank() }.thenBy { it.hostname })
            .take(MATRIX_ROWS)
            .map { service ->
                MatrixRowModel(
                    label = service.hostname,
                    location = Location(Route.Overview, service.hostname),
                    cells = edges.map { edge ->
                        val through = service.perNode.firstOrNull { it.nodeId == edge }
                        val code = through?.code
                        val ms = through?.ms
                        when {
                            through == null -> MatrixCellModel("—", Health.UNKNOWN, "$edge doesn't serve it")
                            code != null && code >= 500 -> MatrixCellModel(code.toString(), Health.DOWN, "HTTP $code")
                            through.health == Health.DOWN -> MatrixCellModel("✗", Health.DOWN, through.detail)
                            ms != null -> MatrixCellModel(ms.toLong().toString(), through.health, "${ms.toLong()} ms")
                            through.health == Health.OK -> MatrixCellModel("✓", Health.OK, through.detail)
                            else -> MatrixCellModel("▲", through.health, through.detail)
                        }
                    },
                )
            }
        return MatrixModel(
            edges = edges,
            rows = rows,
            total = status.services.size,
        )
    }

    private fun system(status: StatusSnapshot, now: Long): List<FactRow> = buildList {
        status.cluster?.let { cluster ->
            add(FactRow("Database", "primary ${cluster.primary ?: "none"} · ${plural(cluster.members.count { it.role == "replica" }, "replica")}"))
            cluster.valkeyPrimary?.let { add(FactRow("Valkey", "primary $it")) }
            cluster.quorum?.let { add(FactRow("Vote", if (it) "quorum" else "no quorum")) }
        }
        status.lastReconcile?.let { pass ->
            add(FactRow("Reconcile", "last pass ${ago(pass.finishedAt, now)} · ${plural(pass.changes, "change")}"))
        }
        add(FactRow("DNS", "${status.dns.count { it.inSync }} of ${status.dns.size} records in sync"))
        add(FactRow("Version", "${status.project} · ${status.version}"))
    }

    private fun recent(log: List<LogEntryDto>): List<RecentChangeModel> =
        log.filter { it.level != LogLevel.INFO }.take(RECENT_CHANGES).map { entry ->
            RecentChangeModel(
                time = times.time(entry.time),
                absolute = times.dateTime(entry.time),
                text = listOfNotNull(entry.target, entry.message).joinToString(": "),
                severity = when (entry.level) {
                    LogLevel.ERROR -> Severity.Down
                    LogLevel.WARN -> Severity.Warn
                    else -> Severity.Info
                },
            )
        }

    private fun inspector(status: StatusSnapshot, props: OverviewScreenProps, now: Long): InspectorModel? {
        val selection = props.selection ?: return null
        status.nodes.firstOrNull { it.id == selection }?.let { node ->
            return nodeInspector(
                node = node,
                samples = props.metrics[node.id],
                now = now,
                actions = nodeActions.forNode(node),
            )
        }
        status.services.firstOrNull { it.hostname == selection }?.let { service ->
            return serviceInspector(
                service = service,
                nodes = status.nodes,
                actions = serviceActions.forService(service),
            )
        }
        return null
    }

    private companion object {
        const val VIEW_KEY = "overview.network"
        const val MATRIX_ROWS = 8
        const val RECENT_CHANGES = 6
        const val LAG_FULL_SCALE = 1024L * 1024L
    }
}

/** Reachable on every edge that serves it. */
internal fun ServiceStatusDto.reachable(): Boolean =
    perNode.isNotEmpty() && perNode.all { it.health == Health.OK || it.health == Health.DEGRADED }

/** Problems first in the matrix: failing, then slow, then the rest. */
private fun ServiceStatusDto.rank(): Int = when {
    perNode.any { it.health == Health.DOWN } -> 0
    perNode.any { it.health == Health.DEGRADED } -> 1
    else -> 2
}

/** The node's last Pangolin API probe, in whole milliseconds. */
internal fun NodeStatusDto.apiProbe(): Long? = checks.firstOrNull { it.name == "pangolin-api" }?.value?.toLong()
