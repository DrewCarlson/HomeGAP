package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.dns.DnsScreenModel
import homegap.presentation.routing.Location
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

/** The records HomeGAP manages, one block per record set. */
@Composable
fun DnsScreen(
    model: DnsScreenModel,
    onOpen: (Location) -> Unit,
) {
    if (model.steering.isNotEmpty()) {
        Div({
            classes("steering")
            attr("aria-label", "Geo steering")
        }) {
            Span({ classes("caption") }) { Text("Geo steering") }
            model.steering.forEach { edge ->
                Span({ classes("steering-edge", if (edge.serving) "tone-ok" else "tone-warn") }) {
                    Text(if (edge.serving) "${edge.edge} ✓" else "${edge.edge} withdrawn" + edge.since?.let { " for $it" }.orEmpty())
                }
            }
            Span({ classes("muted", "small") }) { Text("Visitors go to the nearest node that answers.") }
        }
    }
    Figure(
        title = "Record sets",
        flush = true,
    ) {
        model.sets.forEach { set ->
            key("${set.name} ${set.type}") {
                Div({
                    classes("record-set")
                    if (set.location == model.selected) {
                        classes("selected")
                    }
                }) {
                    NavLink(
                        href = HashRouter.href(set.location),
                        onNavigate = { onOpen(set.location) },
                        classNames = listOf("record-set-head"),
                    ) {
                        Span({ classes("mark", set.health.toneClass) }) { Text(set.health.symbol) }
                        Span({ classes("mono", "record-name") }) { Text(set.name) }
                        Span({ classes("mono", "muted") }) { Text("${set.type} · ${set.routing}") }
                        Span({ classes("muted", "record-summary") }) { Text(set.summary) }
                    }
                    Ul({ classes("record-members") }) {
                        set.members.forEach { member ->
                            Li {
                                Span({ classes("mono") }) { Text(member.node) }
                                Span({ classes("mono", "muted") }) { Text(member.value) }
                                Span({ classes("muted", "wide-only") }) { Text(member.monitor) }
                                Span({ classes(member.health.toneClass) }) { Text(member.state) }
                            }
                        }
                    }
                }
            }
        }
    }
}
