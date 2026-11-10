package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.addnode.LocationOptionModel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*

/** A search over the locations catalog: type, then pick a match. */
@Composable
fun LocationSearchBox(
    query: String,
    matches: List<LocationOptionModel>,
    onQuery: (String) -> Unit,
    onPick: (String) -> Unit,
    placeholder: String = "Search cities or providers: dallas, london, hetzner…",
) {
    Div({ classes("location-search") }) {
        Input(InputType.Search) {
            value(query)
            placeholder(placeholder)
            attr("aria-label", "Search locations")
            onInput { onQuery(it.value) }
            onKeyDown { e ->
                if (e.key == "Enter" && matches.isNotEmpty()) {
                    e.preventDefault()
                    onPick(matches.first().id)
                }
            }
        }
        if (query.isNotBlank()) {
            Ul({ classes("location-results") }) {
                if (matches.isEmpty()) Li({ classes("muted") }) { Text("No known location matches. Click the map or type the coordinates.") }
                matches.forEach { m ->
                    key(m.id) {
                        Li {
                            Button({ onClick { onPick(m.id) } }) {
                                Span({ classes("location-name") }) { Text(m.label) }
                                if (m.detail.isNotBlank()) Span({ classes("muted") }) { Text(m.detail) }
                            }
                        }
                    }
                }
            }
        }
    }
}
