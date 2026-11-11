package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.TagElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent

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
        if (style == OverlayStyle.Sheet) {
            dismiss?.let { SheetGrip(onDismiss = it) }
        }
        content()
    }
}

/**
 * The grip at the top of a bottom sheet: dragging it down moves the sheet with the finger, and
 * releasing past a third of the sheet's height (or after a quick flick) dismisses it; anything
 * less springs back. It is hidden from screen readers, which use the sheet's own Close or Cancel.
 */
@Composable
private fun SheetGrip(onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Div({
        classes("sheet-grip")
        attr("aria-hidden", "true")
    }) {
        DisposableEffect(Unit) {
            val grip = scopeElement
            val sheet = grip.parentElement as HTMLElement
            var dragging = false
            var startY = 0.0
            var startTime = 0.0
            var offset = 0.0
            val onDown: (Event) -> Unit = { event ->
                val pointer = event as PointerEvent
                dragging = true
                startY = pointer.clientY.toDouble()
                startTime = pointer.timeStamp.toDouble()
                offset = 0.0
                grip.setPointerCapture(pointer.pointerId)
                sheet.style.transition = "none"
            }
            val onMove: (Event) -> Unit = { event ->
                if (dragging) {
                    offset = ((event as PointerEvent).clientY - startY).coerceAtLeast(0.0)
                    sheet.style.transform = "translateY(${offset}px)"
                }
            }
            val onUp: (Event) -> Unit = { event ->
                if (dragging) {
                    dragging = false
                    val elapsed = (event.timeStamp.toDouble() - startTime).coerceAtLeast(1.0)
                    val flick = offset > SHEET_FLICK_MIN_PX && offset / elapsed > SHEET_FLICK_PX_PER_MS
                    sheet.style.transition = "transform .18s ease-out"
                    if (flick || offset > sheet.offsetHeight / 3.0) {
                        dismiss()
                    } else {
                        sheet.style.transform = ""
                    }
                }
            }
            grip.addEventListener("pointerdown", onDown)
            grip.addEventListener("pointermove", onMove)
            grip.addEventListener("pointerup", onUp)
            grip.addEventListener("pointercancel", onUp)
            onDispose {
                grip.removeEventListener("pointerdown", onDown)
                grip.removeEventListener("pointermove", onMove)
                grip.removeEventListener("pointerup", onUp)
                grip.removeEventListener("pointercancel", onUp)
            }
        }
    }
}

/** A flick: at least this far, at least this fast. */
private const val SHEET_FLICK_MIN_PX = 40.0
private const val SHEET_FLICK_PX_PER_MS = 0.5

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
