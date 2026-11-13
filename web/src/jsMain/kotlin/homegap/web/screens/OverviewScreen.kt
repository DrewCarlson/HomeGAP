package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.overview.AttentionRowModel
import homegap.presentation.overview.EdgeCardModel
import homegap.presentation.overview.EdgeModel
import homegap.presentation.overview.GaugeModel
import homegap.presentation.overview.MatrixModel
import homegap.presentation.overview.NetworkModel
import homegap.presentation.overview.NetworkView
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.*

/** The whole system at a glance. */
@Composable
fun OverviewScreen(
    model: OverviewScreenModel,
    onOpen: (Location) -> Unit,
) {
    Gauges(model.gauges)
    if (model.attention.isNotEmpty()) {
        NeedsAttention(
            rows = model.attention,
            onOpen = onOpen,
        )
    }
    Network(
        network = model.network,
        onOpen = onOpen,
    )
    if (model.edges.isNotEmpty()) {
        EdgeCards(
            cards = model.edges,
            onOpen = onOpen,
        )
    }
    if (model.matrix.rows.isNotEmpty()) {
        Matrix(
            matrix = model.matrix,
            onOpen = onOpen,
        )
    }
    Figure(caption = "Fig. 04 · System and recent changes") {
        Div({ classes("system-grid") }) {
            Dl({ classes("fact-list") }) {
                model.system.forEach { row ->
                    Div {
                        Dt { Text(row.label) }
                        Dd { Text(row.value) }
                    }
                }
            }
            Ol({ classes("recent") }) {
                if (model.recent.isEmpty()) {
                    Li({ classes("muted") }) { Text("No changes since the orchestrator started.") }
                }
                model.recent.forEach { change ->
                    Li {
                        Span({
                            classes("recent-time", "mono")
                            title(change.absolute)
                        }) {
                            Text(change.time)
                        }
                        Span({ classes(change.severity.className) }) { Text(change.text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gauges(gauges: List<GaugeModel>) {
    Div({ classes("gauges") }) {
        gauges.forEach { gauge ->
            Div({ classes("tile") }) {
                Div({ classes("tile-label") }) { Text(gauge.label) }
                Div({ classes("tile-value", gauge.health.toneClass) }) { Text(gauge.value) }
                Div({ classes("tile-bar") }) {
                    Div({
                        classes("tile-fill", gauge.health.toneClass)
                        style { width((gauge.fraction * 100).percent) }
                    })
                }
            }
        }
    }
}

@Composable
private fun NeedsAttention(rows: List<AttentionRowModel>, onOpen: (Location) -> Unit) {
    Section({
        classes("attention")
        attr("aria-label", "Needs attention")
    }) {
        Div({ classes("attention-head") }) {
            Span({ classes("caption") }) { Text("Needs attention · ${rows.size}") }
            Span({ classes("caption", "muted") }) { Text("select to inspect") }
        }
        rows.forEach { row ->
            NavLink(
                href = HashRouter.href(row.location),
                onNavigate = { onOpen(row.location) },
                classNames = listOf("attention-row"),
            ) {
                Span({ classes("attention-mark", row.severity.className) }) {
                    Text(if (row.severity == homegap.presentation.ui.Severity.Down) "✗" else "▲")
                }
                Span({ classes("attention-text") }) {
                    Span({ classes(row.severity.className) }) { Text(row.title) }
                    if (row.detail.isNotEmpty()) {
                        Span({ classes("muted") }) { Text(" · ${row.detail}") }
                    }
                }
                Span({ classes("attention-since") }) { Text(row.since) }
            }
            if (row.children.isNotEmpty()) {
                Ul({ classes("attention-children") }) {
                    row.children.forEach { child ->
                        Li {
                            NavLink(
                                href = HashRouter.href(child.location),
                                onNavigate = { onOpen(child.location) },
                                classNames = listOf("attention-child"),
                            ) {
                                Span({ classes(child.severity.className) }) { Text("● ") }
                                Text(child.title)
                                if (child.detail.isNotEmpty()) {
                                    Span({ classes("muted") }) { Text(" · ${child.detail}") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Network(network: NetworkModel, onOpen: (Location) -> Unit) {
    Figure(
        caption = "Fig. 01 · Network · ${network.edges.size} edges",
        flush = true,
        actions = {
            Div({
                classes("segmented")
                attr("role", "group")
                attr("aria-label", "Network view")
            }) {
                ViewButton(
                    label = "Traffic path",
                    active = network.view == NetworkView.Path,
                    onClick = { network.onView(NetworkView.Path) },
                )
                ViewButton(
                    label = "Map",
                    active = network.view == NetworkView.Map,
                    onClick = { network.onView(NetworkView.Map) },
                )
            }
            NavLink(
                href = HashRouter.href(Route.Locations),
                onNavigate = { onOpen(Location(Route.Locations)) },
                classNames = listOf("link"),
            ) {
                Text("Locations")
            }
        },
    ) {
        when (network.view) {
            NetworkView.Path -> TrafficPath(
                network = network,
                onOpen = onOpen,
            )

            NetworkView.Map -> WorldMap(
                model = network.map,
                heightPx = 380,
                onMarkerClick = { id ->
                    if (network.edges.any { it.id == id }) {
                        onOpen(Location(Route.Overview, id))
                    }
                },
            )
        }
    }
}

@Composable
private fun ViewButton(label: String, active: Boolean, onClick: () -> Unit) {
    Button({
        if (active) {
            classes("active")
        }
        attr("aria-pressed", active.toString())
        onClick { onClick() }
    }) {
        Text(label)
    }
}

/** Homelab on the left, a row per edge: tunnel, edge with its probe, the names it serves. */
@Composable
private fun TrafficPath(network: NetworkModel, onOpen: (Location) -> Unit) {
    Div({ classes("path") }) {
        Div({ classes("path-homelab") }) {
            Div({ classes("caption") }) { Text("Homelab") }
            Div({ classes("path-homelab-place") }) { Text(network.homelab.place) }
            network.homelab.facts.forEach { fact ->
                Div({ classes("muted", "mono") }) { Text(fact) }
            }
        }
        Div({ classes("path-edges") }) {
            network.edges.forEach { edge ->
                key(edge.id) {
                    PathRow(
                        edge = edge,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
    P({ classes("figure-note") }) {
        Text("Line: mesh round trip between the homelab and the edge. Box: the Pangolin API probe through the edge.")
    }
}

@Composable
private fun PathRow(edge: EdgeModel, onOpen: (Location) -> Unit) {
    Div({ classes("path-row") }) {
        Div({ classes("path-line", edge.health.toneClass) }) {
            Span({ classes("mono") }) { Text(edge.mesh ?: "tunnel") }
        }
        NavLink(
            href = HashRouter.href(edge.location),
            onNavigate = { onOpen(edge.location) },
            classNames = listOf("path-edge", edge.health.toneClass),
        ) {
            Span {
                B { Text(edge.id) }
                Span({ classes("muted") }) { Text(" ${edge.place}") }
            }
            Span({ classes("mono") }) { Text(edge.api) }
        }
        Div({ classes("path-names", "mono") }) { Text(edge.names) }
    }
}

@Composable
private fun EdgeCards(cards: List<EdgeCardModel>, onOpen: (Location) -> Unit) {
    Figure(
        caption = "Fig. 02 · Edges · last 60 min",
        actions = {
            NavLink(
                href = HashRouter.href(Route.Nodes),
                onNavigate = { onOpen(Location(Route.Nodes)) },
                classNames = listOf("link"),
            ) {
                Text("Nodes →")
            }
        },
        flush = true,
    ) {
        Div({
            classes("edge-cards")
            if (cards.size <= 2) {
                classes("two")
            }
        }) {
            cards.forEach { card ->
                key(card.id) {
                    NavLink(
                        href = HashRouter.href(card.location),
                        onNavigate = { onOpen(card.location) },
                        classNames = listOf("edge-card"),
                    ) {
                        Div({ classes("edge-card-head") }) {
                            Span {
                                B { Text(card.id) }
                                Span({ classes("muted") }) { Text(" ${card.place}") }
                            }
                            Span({ classes("mono", card.health.toneClass) }) { Text(card.status) }
                        }
                        Chart(
                            chart = card.chart,
                            compact = true,
                        )
                        if (card.facts.isNotEmpty()) {
                            Div({ classes("edge-card-facts", "mono") }) {
                                card.facts.forEach { Span { Text(it) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Matrix(matrix: MatrixModel, onOpen: (Location) -> Unit) {
    Figure(
        caption = "Fig. 03 · Services × edges · response time, ms",
        flush = true,
        actions = {
            NavLink(
                href = HashRouter.href(Route.Services),
                onNavigate = { onOpen(Location(Route.Services)) },
                classNames = listOf("link"),
            ) {
                Text("All ${matrix.total} →")
            }
        },
    ) {
        Div({ classes("matrix-wrap") }) {
            ScrollHint()
            Table({ classes("matrix") }) {
                Thead {
                    Tr {
                        Th { Text("Host") }
                        matrix.edges.forEach { edge -> Th { Text(edge) } }
                    }
                }
                Tbody {
                    matrix.rows.forEach { row ->
                        key(row.label) {
                            Tr {
                                Td({ classes("matrix-host") }) {
                                    NavLink(
                                        href = HashRouter.href(row.location),
                                        onNavigate = { onOpen(row.location) },
                                    ) {
                                        Text(row.label)
                                    }
                                }
                                row.cells.forEach { cell ->
                                    Td({
                                        classes("matrix-cell", cell.health.toneClass)
                                        attr("title", cell.title)
                                    }) {
                                        Text(cell.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
