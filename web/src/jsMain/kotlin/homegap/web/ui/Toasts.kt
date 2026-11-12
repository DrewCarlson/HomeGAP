package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.data.Toast
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Where a [ToastStack] floats, so it never covers the Inspector's buttons. */
enum class ToastPlacement(val className: String?) {
    /** The bottom right of the window. */
    Corner(null),

    /** Bottom, just left of the Inspector's column. */
    BesideInspector("beside-inspector"),

    /** Just above the action bar of the Inspector's drawer or sheet, inside its dialog. */
    AboveInspectorActions("above-actions"),
}

/** The toasts, newest last. */
@Composable
fun ToastStack(toasts: List<Toast>, placement: ToastPlacement = ToastPlacement.Corner) {
    if (toasts.isEmpty()) {
        return
    }
    Div({
        classes("toasts")
        placement.className?.let { classes(it) }
    }) {
        toasts.forEach { toast ->
            key(toast) {
                ToastCard(toast)
            }
        }
    }
}

@Composable
private fun ToastCard(toast: Toast) {
    Div({
        classes("toast")
        if (toast.isError) {
            classes("toast-error")
        }
        attr("role", if (toast.isError) "alert" else "status")
    }) {
        Span({ classes("toast-icon") }) {
            Icon(
                path = if (toast.isError) Icons.ALERT else Icons.CHECK,
                size = 14,
            )
        }
        Span({ classes("toast-text") }) { Text(toast.text) }
        val action = toast.action
        val dismiss = toast.dismiss
        if (action != null || dismiss != null) {
            Div({ classes("toast-buttons") }) {
                if (action != null) {
                    Button({
                        classes("toast-action")
                        attr("type", "button")
                        onClick { action() }
                    }) {
                        Text(toast.actionLabel ?: "Undo")
                    }
                }
                if (dismiss != null) {
                    Button({
                        classes("toast-dismiss")
                        attr("type", "button")
                        attr("aria-label", "Dismiss")
                        attr("title", "Dismiss")
                        onClick { dismiss() }
                    }) {
                        Icon(
                            path = Icons.CLOSE,
                            size = 14,
                        )
                    }
                }
            }
        }
        // How long is left before it goes, most useful for an Undo.
        Span({
            classes("toast-timer")
            attr("aria-hidden", "true")
            style { property("animation-duration", "${toast.duration.inWholeMilliseconds}ms") }
        })
    }
}
