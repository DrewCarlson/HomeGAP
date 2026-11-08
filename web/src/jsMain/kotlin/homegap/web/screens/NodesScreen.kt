package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.nodes.NodesScreenModel
import homegap.presentation.routing.Route
import homegap.web.routing.HashRouter
import homegap.web.ui.NavLink
import homegap.web.ui.Panel
import org.jetbrains.compose.web.dom.Text

@Composable
fun NodesScreen(model: NodesScreenModel, onNavigate: (Route) -> Unit) {
    Panel("Nodes", actions = {
        NavLink(HashRouter.href(Route.AddNode), { onNavigate(Route.AddNode) }, listOf("btn", "primary", "sm")) { Text("Add node") }
    }) {
        NodeTable(model.nodes, model.events)
    }
}
