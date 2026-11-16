package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.nodes.FleetRowModel
import homegap.presentation.nodes.MeshMatrixModel
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.nodes.RolloutStripModel
import homegap.presentation.routing.Location
import homegap.presentation.ui.ProblemsFilterModel
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

/** The fleet of nodes and the mesh they share. Per-node actions live in the Inspector. */
@Composable
fun NodesScreen(
    model: NodesScreenModel,
    onOpen: (Location) -> Unit,
) {
    model.rollout?.let { RolloutStrip(it) }
    Fleet(
        rows = model.fleet,
        filter = model.filter,
        selected = model.selected,
        onOpen = onOpen,
    )
    model.mesh?.let { Mesh(it) }
}

@Composable
private fun RolloutStrip(rollout: RolloutStripModel) {
    Section({
        classes("rollout")
        attr("aria-label", rollout.title)
    }) {
        Div({ classes("rollout-head") }) {
            Span({ classes("caption") }) { Text(rollout.title) }
            Span({ classes("caption") }) { Text(rollout.progress) }
        }
        Ol({ classes("rollout-steps") }) {
            rollout.steps.forEach { step ->
                Li({ classes("rollout-step", step.health.toneClass) }) {
                    B { Text(step.id) }
                    Div { Text(step.text) }
                    if (step.detail.isNotEmpty()) {
                        Div({ classes("muted", "rollout-detail") }) { Text(step.detail) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fleet(rows: List<FleetRowModel>, filter: ProblemsFilterModel, selected: Location?, onOpen: (Location) -> Unit) {
    val headers = remember {
        listOf(
            "Node",
            "Health",
            "Requests, last hour",
            "Mesh link",
            "Certificate",
        )
    }
    Figure(
        title = "Nodes",
        actions = { ProblemsFilter(filter) },
        flush = true,
    ) {
        Table({ classes("fleet") }) {
            Thead {
                Tr {
                    headers.forEach { header -> Th { Text(header) } }
                    if (rows.any { it.menu.isNotEmpty() }) {
                        Th({ attr("aria-label", "Actions") })
                    }
                }
            }
            Tbody {
                rows.forEach { row ->
                    key(row.id) {
                        Tr({
                            classes("fleet-row")
                            if (row.location == selected) {
                                classes("selected")
                            }
                            onClick { onOpen(row.location) }
                            opensOnEnter { onOpen(row.location) }
                        }) {
                            Td({ classes("fleet-node") }) {
                                NavLink(
                                    href = HashRouter.href(row.location),
                                    onNavigate = { onOpen(row.location) },
                                    tabStop = false,
                                ) {
                                    B { Text(row.id) }
                                    Span({ classes("muted") }) { Text(" ${row.place}") }
                                }
                                Div({ classes("muted", "mono", "small") }) { Text(row.meta) }
                            }
                            Td({ classes("fleet-health") }) {
                                Div({ classes(row.health.toneClass) }) { Text("${row.status} · ${row.passing}") }
                                row.note?.let { note ->
                                    Div({ classes("small", row.noteHealth.toneClass) }) { Text(note) }
                                }
                            }
                            Td({ classes("fleet-chart") }) {
                                Chart(
                                    chart = row.chart,
                                    compact = true,
                                )
                            }
                            Td({ classes("fleet-wide", row.meshLinkHealth.toneClass) }) { Text(row.meshLink) }
                            Td({ classes("fleet-wide", "mono", row.certificateHealth.toneClass) }) { Text(row.certificate) }
                            if (row.menu.isNotEmpty()) {
                                Td({
                                    classes("fleet-menu")
                                    // The menu is not the row: a click on it does not open the Inspector.
                                    onClick { it.stopPropagation() }
                                }) {
                                    KebabMenu(
                                        label = "Actions for ${row.id}",
                                        items = row.menu.map { action ->
                                            MenuItem(
                                                label = action.label,
                                                onClick = { action.onClick?.invoke() },
                                            )
                                        },
                                    )
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
private fun Mesh(mesh: MeshMatrixModel) {
    Figure(
        title = "Mesh",
        note = "${mesh.links}.",
        flush = true,
    ) {
        Div({ classes("matrix-wrap") }) {
            ScrollHint()
            Table({ classes("matrix", "mesh") }) {
                Thead {
                    Tr {
                        Th { Text("From ↓ to →") }
                        mesh.ids.forEach { id -> Th { Text(id) } }
                    }
                }
                Tbody {
                    mesh.rows.forEach { row ->
                        key(row.id) {
                            Tr {
                                Th({ classes("matrix-host") }) { Text(row.id) }
                                row.cells.forEach { cell ->
                                    Td({
                                        classes("matrix-cell", cell.health.toneClass)
                                        if (cell.title.isNotEmpty()) {
                                            title(cell.title)
                                        }
                                    }) {
                                        Text(cell.text)
                                        if (cell.detail.isNotEmpty()) {
                                            Div({ classes("cell-detail") }) { Text(cell.detail) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        P({ classes("figure-note") }) { Text(mesh.legend) }
    }
}

