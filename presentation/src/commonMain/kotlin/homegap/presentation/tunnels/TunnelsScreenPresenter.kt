package homegap.presentation.tunnels

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.StatusSnapshot
import homegap.api.TunnelStatusDto
import homegap.presentation.core.Presenter
import homegap.presentation.data.Samples
import homegap.presentation.format.formatBytes
import homegap.presentation.format.plural
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.chartOf
import homegap.presentation.ui.on
import homegap.presentation.ui.statusLineOf

data class TunnelsScreenProps(
    val status: StatusSnapshot,
    val now: Long = 0,
    val metrics: Map<String, Map<String, Samples>> = emptyMap(),
    /** The selected node id. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class TunnelsScreenPresenter(
    private val api: homegap.presentation.data.ControllerApi,
    private val actions: homegap.presentation.data.ActionRunner,
    private val confirmations: homegap.presentation.data.Confirmations,
) : Presenter<TunnelsScreenProps, TunnelsScreenModel> {
    @Composable
    override fun model(props: TunnelsScreenProps): TunnelsScreenModel {
        val status = props.status
        val nowSeconds = props.now / 1000
        val up = status.tunnels.count { it.up }
        val selected = status.tunnels.firstOrNull { it.nodeId == props.selection }
        return TunnelsScreenModel(
            head = PageHeadModel(
                caption = "Tunnels · Newt connectors from the homelab",
                headline = "$up of ${status.tunnels.size} tunnels up.",
                statusLine = statusLineOf(status.attention.on(Route.Tunnels)),
            ),
            tunnels = status.tunnels.map { tunnel ->
                TunnelCardModel(
                    nodeId = tunnel.nodeId,
                    endpoint = tunnel.endpoint,
                    health = tunnel.health(),
                    status = certificateCause(status, tunnel)?.let { "down · $it" } ?: tunnel.statusText(),
                    facts = tunnel.facts(),
                    chart = chartOf(
                        label = "In, homelab to edge",
                        samples = props.metrics[tunnel.nodeId]?.get("in"),
                        nowSeconds = nowSeconds,
                        unit = "Mb/s",
                    ),
                    location = Location(Route.Tunnels, tunnel.nodeId),
                )
            },
            selected = selected?.let { Location(Route.Tunnels, it.nodeId) },
            inspector = selected?.let { tunnel ->
                inspector(
                    tunnel = tunnel,
                    samples = props.metrics[tunnel.nodeId],
                    nowSeconds = nowSeconds,
                    cause = certificateCause(status, tunnel),
                )
            },
            empty = if (status.tunnels.isEmpty()) {
                EmptyStateModel(
                    title = "No tunnels yet",
                    text = "Each edge node gets a Newt connector from the homelab once it is provisioned. " +
                        "Add a node and its tunnel shows up here.",
                    action = ActionModel(
                        label = "+ Add node",
                        location = Location(Route.AddNode),
                    ),
                )
            } else {
                null
            },
        )
    }

    private fun inspector(tunnel: TunnelStatusDto, samples: Map<String, Samples>?, nowSeconds: Long, cause: String?): InspectorModel =
        InspectorModel(
            kind = "Tunnel",
            title = "${tunnel.nodeId} connector",
            status = tunnel.statusText(),
            health = tunnel.health(),
            sections = buildList {
                cause?.let {
                    add(
                        InspectorSection.Note(
                            text = "The cause: ${tunnel.nodeId} $it, so Newt cannot connect. It connects once the node has the certificate with its name.",
                            severity = Severity.Warn,
                        )
                    )
                }
                tunnel.lastExit?.let { add(InspectorSection.Note(text = "Last exit: $it", severity = Severity.Warn)) }
                add(
                    InspectorSection.Charts(
                        listOf(
                            chartOf(
                                label = "In, homelab to edge",
                                samples = samples?.get("in"),
                                nowSeconds = nowSeconds,
                                unit = "Mb/s",
                            ),
                            chartOf(
                                label = "Out, edge to homelab",
                                samples = samples?.get("out"),
                                nowSeconds = nowSeconds,
                                unit = "Mb/s",
                            ),
                        )
                    )
                )
                add(
                    InspectorSection.Facts(
                        title = "Connector",
                        rows = listOfNotNull(
                            FactRow("Endpoint", tunnel.endpoint, mono = true),
                            FactRow("Site", tunnel.siteNiceId, mono = true),
                            FactRow("Process", if (tunnel.processRunning) "running" else "stopped"),
                            FactRow("Restarts", tunnel.restarts.toString()),
                            tunnel.newtVersion?.let { FactRow("Newt", it, mono = true) },
                            tunnel.bytesIn?.let { FactRow("Traffic", "${formatBytes(it)} in · ${formatBytes(tunnel.bytesOut ?: 0)} out") },
                        ),
                    )
                )
                if (tunnel.exits.isNotEmpty()) {
                    add(
                        InspectorSection.Marks(
                            title = "Stops",
                            rows = tunnel.exits.map { exit ->
                                MarkRow(
                                    health = if (exit.reason.startsWith("restarted")) Health.UNKNOWN else Health.DEGRADED,
                                    name = exit.reason,
                                    value = homegap.presentation.format.ago(exit.at, nowSeconds * 1000),
                                )
                            },
                        )
                    )
                }
            },
            actions = listOfNotNull(
                cause?.let {
                    ActionModel(
                        label = "Certificates",
                        location = Location(Route.Certificates, "node:${tunnel.nodeId}"),
                    )
                },
                ActionModel(
                    label = "Restart connector",
                    onClick = {
                        confirmations.ask(
                            homegap.presentation.ui.ConfirmModel(
                                title = "Restart the ${tunnel.nodeId} connector?",
                                text = "Newt stops and starts again. Traffic through ${tunnel.nodeId} pauses for a few seconds; " +
                                    "visitors on other edges are not affected.",
                                confirmLabel = "Restart",
                            )
                        ) {
                            actions.run("Restart connector") { api.restartTunnel(tunnel.nodeId) }
                        }
                    },
                ),
                ActionModel(
                    label = "Edge node",
                    location = Location(Route.Nodes, tunnel.nodeId),
                ),
                ActionModel(
                    label = "Connector log",
                    location = Location(Route.Diagnostics, tunnel.nodeId),
                ),
            ),
        )
}

private fun TunnelStatusDto.health(): Health = when {
    up -> Health.OK
    processRunning -> Health.DEGRADED
    else -> Health.DOWN
}

private fun TunnelStatusDto.statusText(): String = when {
    up -> "up"
    processRunning -> "connecting · site ${if (siteOnline == false) "offline" else "unknown"}"
    else -> "down · process stopped"
}

private fun TunnelStatusDto.facts(): List<String> = listOfNotNull(
    if (processRunning) "process running" else "process stopped",
    if (restarts > 0) plural(restarts, "restart") else null,
    bytesIn?.let { "${formatBytes(it)} in" },
)

/** Why [tunnel] is down when the cause is the certificate: its node answers its own name with the default one. */
private fun certificateCause(status: homegap.api.StatusSnapshot, tunnel: TunnelStatusDto): String? {
    if (tunnel.up) return null
    val delivery = status.certificates?.delivery?.firstOrNull { it.node == tunnel.nodeId } ?: return null
    return when (delivery.state) {
        "waiting" -> "waits for its certificate: its management name serves the default certificate"
        "default", "none" -> "serves the default certificate on its management name"
        else -> null
    }
}
