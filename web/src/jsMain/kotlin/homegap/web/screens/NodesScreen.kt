package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.nodes.DatabasePanelModel
import homegap.presentation.nodes.FleetRowModel
import homegap.presentation.nodes.MeshMatrixModel
import homegap.presentation.nodes.MeshMode
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.nodes.RolloutStripModel
import homegap.presentation.routing.Location
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

/** The fleet of edges and the cluster they share; per-node actions live in the Inspector. */
@Composable
fun NodesScreen(
    model: NodesScreenModel,
    onOpen: (Location) -> Unit,
) {
    model.rollout?.let { RolloutStrip(it) }
    Fleet(
        rows = model.fleet,
        onOpen = onOpen,
    )
    model.database?.let { database ->
        Database(
            database = database,
            onSwitchPrimary = model.onSwitchPrimary,
        )
    }
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
private fun Fleet(rows: List<FleetRowModel>, onOpen: (Location) -> Unit) {
    val headers = remember {
        listOf(
            "Node",
            "Health",
            "Pangolin API probe · 60 min",
            "Tunnel",
            "Certificate",
            "Stack",
            "Drift",
        )
    }
    Figure(
        caption = "Fig. 01 · Fleet · select a node",
        flush = true,
    ) {
        Table({ classes("fleet") }) {
            Thead {
                Tr {
                    headers.forEach { header -> Th { Text(header) } }
                }
            }
            Tbody {
                rows.forEach { row ->
                    key(row.id) {
                        Tr({
                            classes("fleet-row")
                            onClick { onOpen(row.location) }
                        }) {
                            Td({ classes("fleet-node") }) {
                                NavLink(
                                    href = HashRouter.href(row.location),
                                    onNavigate = { onOpen(row.location) },
                                ) {
                                    B { Text(row.id) }
                                    Span({ classes("muted") }) { Text(" ${row.place}") }
                                }
                                Div({ classes("muted", "mono", "small") }) { Text(row.meta) }
                            }
                            Td({ classes("fleet-health") }) {
                                Div({ classes(row.health.toneClass) }) { Text("${row.status} · ${row.passing}") }
                                Div({
                                    classes("check-dots")
                                    attr("aria-hidden", "true")
                                }) {
                                    row.checks.forEach { health -> Span({ classes("check-dot", health.toneClass) }) }
                                }
                            }
                            Td({ classes("fleet-chart") }) {
                                Chart(
                                    chart = row.chart,
                                    compact = true,
                                )
                            }
                            Td({ classes("fleet-wide", if (row.tunnel == "up") "tone-ok" else "tone-down") }) { Text(row.tunnel) }
                            Td({ classes("fleet-wide", "mono") }) { Text(row.certificate) }
                            Td({ attr("data-label", "Stack") }) { Text(row.stack) }
                            Td({
                                classes(if (row.drift == "none") "muted" else "tone-warn")
                                attr("data-label", "Drift")
                            }) {
                                Text(row.drift)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Database(
    database: DatabasePanelModel,
    onSwitchPrimary: (String) -> Unit,
) {
    Figure(
        caption = "Fig. 02 · Cluster database",
        title = "PostgreSQL (Patroni) and Valkey",
        flush = true,
    ) {
        Div({ classes("db-stats") }) {
            DbStat(label = "PostgreSQL primary", badge = database.primary)
            DbStat(label = "Valkey primary", badge = database.valkeyPrimary)
            DbStat(label = "Vote", badge = database.quorum)
        }
        database.switchBack?.let { note ->
            P({ classes("figure-note") }) { Text(note) }
        }
        database.alerts.forEach { alert ->
            P({ classes("figure-note", alert.health.toneClass) }) { Text(alert.text) }
        }
        Ul({ classes("db-members") }) {
            database.members.forEach { member ->
                key(member.id) {
                    Li {
                        B { Text(member.id) }
                        Span({ classes("mono", "muted", "db-address") }) { Text(member.address) }
                        Span({ classes(member.database.health.toneClass) }) { Text(member.database.text) }
                        Span({ classes("mono") }) { Text("lag ${member.lag}") }
                        Span({ classes("db-action") }) {
                            if (member.canBecomePrimary) {
                                KebabMenu(
                                    label = "Actions for ${member.id}",
                                    items = listOf(
                                        MenuItem(
                                            label = "Make primary…",
                                            onClick = { onSwitchPrimary(member.id) },
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        database.history.firstOrNull()?.let { last ->
            P({ classes("figure-note") }) {
                Text("Last change ${last.time}: ${last.change} (${last.cause.text})")
            }
        }
    }
}

@Composable
private fun DbStat(label: String, badge: homegap.presentation.common.Badge) {
    Div({ classes("db-stat") }) {
        Div({ classes("caption") }) { Text(label) }
        Div({
            classes("db-stat-value", badge.health.toneClass)
            badge.tooltip?.let { title(it) }
        }) {
            Text(badge.text)
        }
    }
}

@Composable
private fun Mesh(mesh: MeshMatrixModel) {
    Figure(
        caption = "Fig. 03 · Cluster mesh · ${mesh.links} · last hour",
        flush = true,
        actions = {
            Div({
                classes("segmented")
                attr("role", "group")
                attr("aria-label", "Mesh view")
            }) {
                MeshMode.entries.forEach { mode ->
                    Button({
                        if (mesh.mode == mode) {
                            classes("active")
                        }
                        attr("aria-pressed", (mesh.mode == mode).toString())
                        onClick { mesh.onMode(mode) }
                    }) {
                        Text(mode.label)
                    }
                }
            }
        },
    ) {
        Div({ classes("matrix-wrap") }) {
            ScrollHint()
            ScrollReset(mesh.mode)
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

