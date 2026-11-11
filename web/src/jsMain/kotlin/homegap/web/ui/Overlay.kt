package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.TagElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** How an overlay sits on the page; the caller picks it from the [Viewport]. */
enum class OverlayStyle(val className: String) {
    /** Centered over a dimmed page. */
    Modal("overlay-modal"),

    /** Slides in from the right edge. */
    Drawer("overlay-drawer"),

    /** Rises from the bottom edge, with a drag handle. */
    Sheet("overlay-sheet"),

    /** Covers the whole screen (phones). */
    FullScreen("overlay-full"),
}

/**
 * One wrapper for every overlay: Inspector drawers and sheets, forms, the wizard, dialogs and
 * search. It is a native `<dialog>` opened with `showModal()`, so the browser traps focus, makes
 * the page behind it inert, puts it on the top layer and gives focus back when it closes.
 *
 * Composing it opens it; leaving the composition closes it. Esc and a click on the dimmed page
 * call [onDismiss]; without one (sign-in) the overlay can't be dismissed.
 */
@Composable
fun Overlay(
    label: String,
    style: OverlayStyle,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    TagElement<HTMLElement>(
        elementBuilder = DialogBuilder,
        applyAttrs = {
            classes("overlay", style.className)
            attr("aria-label", label)
        },
    ) {
        DisposableEffect(Unit) {
            val dialog = scopeElement
            val onCancel: (Event) -> Unit = { event ->
                // Esc: the dialog stays open unless the caller closes it.
                event.preventDefault()
                dismiss?.invoke()
            }
            val onClick: (Event) -> Unit = { event ->
                // A click on the dialog box itself, outside its panel, lands on the dimmed page.
                if (event.target == dialog) {
                    dismiss?.invoke()
                }
            }
            dialog.addEventListener("cancel", onCancel)
            dialog.addEventListener("click", onClick)
            dialog.asDynamic().showModal()
            onDispose {
                dialog.removeEventListener("cancel", onCancel)
                dialog.removeEventListener("click", onClick)
                dialog.asDynamic().close()
            }
        }
        content()
    }
}

private val DialogBuilder = ElementBuilder.createBuilder<HTMLElement>("dialog")
