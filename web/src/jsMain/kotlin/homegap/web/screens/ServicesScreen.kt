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
        Div({
            classes("segmented")
            attr("role", "group")
            attr("aria-label", "Show")
        }) {
            Button({
                if (!model.problemsOnly) {
                    classes("active")
                }
                attr("aria-pressed", (!model.problemsOnly).toString())
                onClick { model.onProblemsOnly(false) }
            }) {
                Text("All ${model.total}")
            }
            Button({
                if (model.problemsOnly) {
                    classes("active")
                }
                attr("aria-pressed", model.problemsOnly.toString())
                onClick { model.onProblemsOnly(true) }
            }) {
                Text("Problems ${model.problems}")
            }
        }
    }
    Figure(
        caption = "Fig. 01 · Services × edges · response time, ms",
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
                                onClick { onOpen(row.location) }
                            }) {
                                Td({ classes("matrix-host") }) {
                                    NavLink(
                                        href = HashRouter.href(row.location),
                                        onNavigate = { onOpen(row.location) },
                                    ) {
                                        Span({ classes(row.health.toneClass) }) { Text(row.hostname) }
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
            Text("Cells: response time through each edge, ms. Amber is slow (over 1 s), rose is failing with its status code, — means the edge doesn't serve it.")
        }
    }
}
