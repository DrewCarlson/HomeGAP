package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.*

/**
 * A titled figure for the screens that name their parts in words rather than figure numbers:
 * the same frame as [Figure], with [title] as the heading and an optional [subtitle] under it.
 * Content is [flush] (edge to edge, for tables) or padded.
 */
@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    flush: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Section({ classes("figure") }) {
        Div({ classes("figure-head") }) {
            Div({ classes("figure-titles") }) {
                H2 { Text(title) }
                subtitle?.let { P({ classes("figure-sub") }) { Text(it) } }
            }
            Div({ classes("figure-actions") }) { actions() }
        }
        Div({ classes(if (flush) "figure-flush" else "figure-body") }) { content() }
    }
}
