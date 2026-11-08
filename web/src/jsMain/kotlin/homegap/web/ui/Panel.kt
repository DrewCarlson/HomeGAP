package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

/** A titled card. Content is [flush] (edge to edge, for tables) or padded. */
@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    flush: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Section({ classes("panel") }) {
        Div({ classes("panel-head") }) {
            Div({ classes("panel-titles") }) {
                H2 { Text(title) }
                subtitle?.let { P({ classes("panel-sub") }) { Text(it) } }
            }
            Div({ classes("panel-actions") }) { actions() }
        }
        Div({ classes(if (flush) "panel-flush" else "panel-body") }) { content() }
    }
}
