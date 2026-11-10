package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import homegap.presentation.overview.OverviewScreenModel
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr

@Composable
fun OverviewScreen(model: OverviewScreenModel, onNavigate: (Route) -> Unit) {
    Div({ classes("stats") }) { model.stats.forEach { Stat(it) } }
    if (!model.map.isEmpty) {
        val subtitle = remember(model.homelabUnplaced) {
            if (model.homelabUnplaced) {
                "Set the homelab's location on the Locations page to see its tunnels"
            } else {
                "Hover a marker for details; lines are the homelab's tunnels"
            }
        }
        Panel("Map", subtitle, actions = {
            PanelLink("Locations →", Route.Locations, onNavigate)
        }) {
            WorldMap(
                model = model.map,
                heightPx = 380,
                onMarkerClick = { id -> if (id != "homelab") onNavigate(Route.Nodes) }
            )
        }
    }
    if (model.labelErrors.isNotEmpty()) {
        Panel(
            title = "Label errors",
            subtitle = "Fix these labels; the affected containers are ignored until then"
        ) {
            DataTable(listOf("Container", "Problem")) {
                model.labelErrors.forEach { error ->
                    key(error) {
                        Tr {
                            Cell("Container", "mono") { Text(error.container) }
                            Cell("Problem") { Text(error.message) }
                        }
                    }
                }
            }
        }
    }
    Panel(
        title = "Nodes",
        actions = { PanelLink("Manage →", Route.Nodes, onNavigate) }
    ) {
        NodeTable(
            model.nodes,
            events = null
        )
    }
    Panel(
        title = "Services",
        actions = { PanelLink("All services →", Route.Services, onNavigate) }
    ) {
        ServiceTable(model.services, onTogglePause = null)
    }
}

@Composable
private fun PanelLink(text: String, route: Route, onNavigate: (Route) -> Unit) {
    NavLink(
        href = HashRouter.href(route),
        onNavigate = { onNavigate(route) },
        classNames = listOf("link")
    ) {
        Text(text)
    }
}
