package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/**
 * A panel: a short [title], an optional [note] of one short sentence under it, [actions] on the
 * right, and a [flush] (edge to edge, for tables) or padded body. Without [content] the panel is
 * its head alone.
 */
@Composable
fun Figure(
    title: String,
    note: String? = null,
    flush: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    Section({ classes("figure") }) {
        Div({ classes("figure-head") }) {
            Div({ classes("figure-titles") }) {
                H2 { Text(title) }
                note?.let { Div({ classes("figure-sub") }) { Text(it) } }
            }
            Div({ classes("figure-actions") }) { actions() }
        }
        content?.let { body ->
            Div({ classes(if (flush) "figure-flush" else "figure-body") }) { body() }
        }
    }
}
