package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.routing.Location
import homegap.presentation.services.ServicesScreenModel
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

/** Every published service and how each edge serves it. */
@Composable
fun ServicesScreen(
    model: ServicesScreenModel,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("filters") }) {
        Label(attrs = { classes("filter-field") }) {
            Icon(Icons.SEARCH, size = 14)
            Input(InputType.Search) {
                value(model.query)
                attr("placeholder", "Filter by hostname or container")
                attr("aria-label", "Filter services")
                onInput { model.onQuery(it.value) }
            }
        }
        ProblemsFilter(model.filter)
    }
    Figure(
        title = "Services by node",
        note = "Response time in ms.",
        flush = true,
    ) {
        if (model.rows.isEmpty()) {
            P({ classes("figure-note") }) { Text("Nothing matches the filter.") }
        }
        Div({ classes("matrix-wrap") }) {
            ScrollHint()
            Table({ classes("matrix", "services") }) {
                Thead {
                    Tr {
                        Th { Text("Hostname · container") }
                        Th({ classes("wide-only") }) { Text("Published as") }
                        Th({ classes("wide-only") }) { Text("Access") }
                        model.edges.forEach { edge -> Th { Text(edge) } }
                    }
                }
                Tbody {
                    model.rows.forEach { row ->
                        key(row.hostname) {
                            Tr({
                                classes("matrix-row")
                                if (row.location == model.selected) {
                                    classes("selected")
                                }
                                onClick { onOpen(row.location) }
                                opensOnEnter { onOpen(row.location) }
                            }) {
                                Td({ classes("matrix-host") }) {
                                    NavLink(
                                        href = HashRouter.href(row.location),
                                        onNavigate = { onOpen(row.location) },
                                        tabStop = false,
                                    ) {
                                        Span({ classes("status-dot", row.health.name) })
                                        Text(" ${row.hostname}")
                                    }
                                    Div({ classes("muted", "mono", "small") }) {
                                        Text(row.container)
                                        if (row.paused) {
                                            Span({ classes("tone-warn") }) { Text(" · paused") }
                                        }
                                    }
                                }
                                Td({ classes("wide-only", "mono", "small") }) { Text(row.publishedAs) }
                                Td({ classes("wide-only", "small") }) { Text(row.access) }
                                row.cells.forEach { cell ->
                                    Td({
                                        classes("matrix-cell", cell.health.toneClass)
                                        title(cell.title)
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
        P({ classes("figure-note") }) {
            Text("Amber is slow (over 1 s). Rose is failing. A dash means the node does not serve it.")
        }
    }
}
