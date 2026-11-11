package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.routing.Location
import homegap.presentation.tunnels.TunnelsScreenModel
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

/** One card per Newt connector. */
@Composable
fun TunnelsScreen(
    model: TunnelsScreenModel,
    onOpen: (Location) -> Unit,
) {
    Figure(
        caption = "Fig. 01 · Connectors · last 60 min",
        flush = true,
    ) {
        Div({
            classes("edge-cards")
            if (model.tunnels.size <= 2) {
                classes("two")
            }
        }) {
            model.tunnels.forEach { tunnel ->
                key(tunnel.nodeId) {
                    NavLink(
                        href = HashRouter.href(tunnel.location),
                        onNavigate = { onOpen(tunnel.location) },
                        classNames = listOf("edge-card"),
                    ) {
                        Div({ classes("edge-card-head") }) {
                            B { Text(tunnel.nodeId) }
                            Span({ classes("mono", tunnel.health.toneClass) }) { Text(tunnel.status) }
                        }
                        Div({ classes("mono", "muted", "small", "ellipsis") }) { Text(tunnel.endpoint) }
                        Chart(
                            chart = tunnel.chart,
                            compact = true,
                        )
                        Div({ classes("edge-card-facts", "mono") }) {
                            tunnel.facts.forEach { Span { Text(it) } }
                        }
                    }
                }
            }
        }
    }
}
