package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/**
 * A blueprint panel: a mono [caption] ("Fig. 01 · Network"), an optional [title], [actions] on
 * the right, and a [flush] (edge to edge, for tables) or padded body. Without [content] the
 * figure is its head alone.
 */
@Composable
fun Figure(
    caption: String,
    title: String? = null,
    flush: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: (@Composable () -> Unit)? = null,
) {
    Section({ classes("figure") }) {
        Div({ classes("figure-head") }) {
            Div({ classes("figure-titles") }) {
                Div({ classes("caption") }) { Text(caption) }
                title?.let { H2 { Text(it) } }
            }
            Div({ classes("figure-actions") }) { actions() }
        }
        content?.let { body ->
            Div({ classes(if (flush) "figure-flush" else "figure-body") }) { body() }
        }
    }
}
