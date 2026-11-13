package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.ElementScope
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Marks the hidden content of this element. While the element can scroll (sideways first, else
 * down) and is at its start, that edge fades out and a small arrow points the way. The fade goes
 * away after a short scroll and comes back at the start. It takes no clicks, so the content under
 * it stays in reach. It is decoration for sighted users.
 */
@Composable
fun ElementScope<HTMLElement>.ScrollHint() {
    DisposableEffect(Unit) {
        val box = scopeElement
        val host = box.parentElement as? HTMLElement
        val hint = document.createElement("div") as HTMLElement
        hint.className = "scroll-hint"
        hint.setAttribute("aria-hidden", "true")
        hint.innerHTML = ARROW_SVG
        host?.classList?.add("scroll-hint-host")
        host?.appendChild(hint)
        var direction: String? = null

        fun update() {
            direction = when {
                box.scrollWidth - box.clientWidth > OVERFLOW_PX && box.scrollLeft < OVERFLOW_PX -> "right"
                box.scrollHeight - box.clientHeight > OVERFLOW_PX && box.scrollTop < OVERFLOW_PX -> "down"
                else -> null
            }
            val showing = direction != null
            hint.classList.toggle("shown", showing)
            if (showing) {
                // Set only while showing, so a fading arrow keeps pointing the way it did.
                hint.classList.toggle("right", direction == "right")
                hint.classList.toggle("down", direction == "down")
                // The host is the offset parent, so offsets place the fade along the edge of the box.
                if (direction == "right") {
                    hint.style.left = "${box.offsetLeft + box.offsetWidth - FADE_PX}px"
                    hint.style.top = "${box.offsetTop}px"
                    hint.style.width = "${FADE_PX}px"
                    hint.style.height = "${box.offsetHeight}px"
                } else {
                    hint.style.left = "${box.offsetLeft}px"
                    hint.style.top = "${box.offsetTop + box.offsetHeight - FADE_PX}px"
                    hint.style.width = "${box.offsetWidth}px"
                    hint.style.height = "${FADE_PX}px"
                }
            }
        }

        val onScroll: (Event) -> Unit = {
            if (box.scrollLeft > SCROLLED_PX || box.scrollTop > SCROLLED_PX) {
                hint.classList.remove("shown")
            } else {
                update()
            }
        }
        val onResize: () -> Unit = { update() }
        // Size changes of the box or of its content (rows arriving) re-check the overflow.
        val sizes = js("new ResizeObserver(function () { onResize(); })")
        sizes.observe(box)
        box.firstElementChild?.let { sizes.observe(it) }
        box.addEventListener("scroll", onScroll)
        window.setTimeout({ update() }, SETTLE_MS)
        onDispose {
            sizes.disconnect()
            box.removeEventListener("scroll", onScroll)
            hint.remove()
        }
    }
}

/**
 * Returns this element to its start (top and left) whenever [key] changes: a new tab, item or
 * page should not open part-way through the last one's scroll.
 */
@Composable
fun ElementScope<HTMLElement>.ScrollReset(key: Any?) {
    DisposableEffect(key) {
        scopeElement.scrollTop = 0.0
        scopeElement.scrollLeft = 0.0
        onDispose {}
    }
}

/** More than this beyond the edge counts as scrollable. */
private const val OVERFLOW_PX = 8

/** Scrolling this far shows the hint did its job. */
private const val SCROLLED_PX = 32

/** The depth of the fade. */
private const val FADE_PX = 28

/** Wait for the first layout (fonts, data) before measuring. */
private const val SETTLE_MS = 300

private const val ARROW_SVG =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>"""
