package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
        Panel("Map", if (model.homelabUnplaced) "Set the homelab's location on the Locations page to see its tunnels" else "Hover a marker for details; lines are the homelab's tunnels", actions = {
            PanelLink("Locations →", Route.Locations, onNavigate)
        }) {
            WorldMap(model.map, heightPx = 380, onMarkerClick = { id -> if (id != "homelab") onNavigate(Route.Nodes) })
        }
    }
    if (model.labelErrors.isNotEmpty()) {
        Panel("Label errors", "Fix these labels; the affected containers are ignored until then") {
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
    Panel("Nodes", actions = { PanelLink("Manage →", Route.Nodes, onNavigate) }) { NodeTable(model.nodes, events = null) }
    Panel("Services", actions = { PanelLink("All services →", Route.Services, onNavigate) }) {
        ServiceTable(model.services, onTogglePause = null)
    }
}

@Composable
private fun PanelLink(text: String, route: Route, onNavigate: (Route) -> Unit) {
    NavLink(HashRouter.href(route), { onNavigate(route) }, listOf("link")) { Text(text) }
}
