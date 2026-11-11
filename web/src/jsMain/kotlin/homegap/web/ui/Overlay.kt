package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import kotlinx.browser.window
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
 * The dialog is named by its visible heading when [titleId] points at one (`aria-labelledby`),
 * otherwise by [label]. While it is open the page behind it does not scroll ([PageScrollLock]).
 *
 * Composing it opens it; leaving the composition closes it. Esc and a click on the dimmed page
 * call [onDismiss]; without one (sign-in) the overlay can't be dismissed. A click counts only when
 * it both starts and ends on the dimmed page, so a drag that starts inside (selecting text, say)
 * and ends outside keeps the overlay open.
 */
@Composable
fun Overlay(
    label: String,
    style: OverlayStyle,
    titleId: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    TagElement<HTMLElement>(
        elementBuilder = DialogBuilder,
        applyAttrs = {
            classes("overlay", style.className)
            if (titleId != null) {
                attr("aria-labelledby", titleId)
            } else {
                attr("aria-label", label)
            }
        },
    ) {
        DisposableEffect(Unit) {
            val dialog = scopeElement
            var pressedOnBackdrop = false
            val onCancel: (Event) -> Unit = { event ->
                // Esc: the dialog stays open unless the caller closes it.
                event.preventDefault()
                dismiss?.invoke()
            }
            val onPointerDown: (Event) -> Unit = { event ->
                // The dialog box itself, outside its panel, is the dimmed page.
                pressedOnBackdrop = event.target == dialog
            }
            val onClick: (Event) -> Unit = { event ->
                if (pressedOnBackdrop && event.target == dialog) {
                    dismiss?.invoke()
                }
                pressedOnBackdrop = false
            }
            dialog.addEventListener("cancel", onCancel)
            dialog.addEventListener("pointerdown", onPointerDown)
            dialog.addEventListener("click", onClick)
            PageScrollLock.acquire()
            dialog.asDynamic().showModal()
            onDispose {
                dialog.removeEventListener("cancel", onCancel)
                dialog.removeEventListener("pointerdown", onPointerDown)
                dialog.removeEventListener("click", onClick)
                dialog.asDynamic().close()
                PageScrollLock.release()
            }
        }
        content()
    }
}

/**
 * Stops the page scrolling while any overlay is open, as Bootstrap's modal does: the root's
 * overflow is hidden, and the width of the scrollbar that disappears is added as padding so the
 * layout does not shift. Overlays can stack (a confirmation over a drawer), so it counts them and
 * unlocks when the last one closes.
 */
private object PageScrollLock {
    private var open = 0

    fun acquire() {
        open++
        if (open == 1) {
            val root = document.documentElement as HTMLElement
            val scrollbar = window.innerWidth - root.clientWidth
            root.style.setProperty("--scrollbar-width", "${scrollbar}px")
            root.classList.add("overlay-open")
        }
    }

    fun release() {
        open = (open - 1).coerceAtLeast(0)
        if (open == 0) {
            val root = document.documentElement as HTMLElement
            root.classList.remove("overlay-open")
            root.style.removeProperty("--scrollbar-width")
        }
    }
}

private val DialogBuilder = ElementBuilder.createBuilder<HTMLElement>("dialog")
