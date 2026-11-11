package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.ElementScope
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Marks this element's hidden content: while it can scroll (sideways first, else down) and sits
 * at its start, a round arrow at that edge points the way and nudges now and then. A tap scrolls
 * most of a screen that way. Scrolling a little fades the arrow out; scrolling back to the start
 * brings it back. It is decoration for sighted users; the content stays reachable as before.
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
            hint.classList.toggle("right", direction == "right")
            hint.classList.toggle("down", direction == "down")
            if (showing) {
                // The host is the offset parent, so offsets place the arrow over the box's edge.
                if (direction == "right") {
                    hint.style.left = "${box.offsetLeft + box.offsetWidth - HINT_PX - EDGE_PX}px"
                    hint.style.top = "${box.offsetTop + (box.offsetHeight - HINT_PX) / 2}px"
                } else {
                    hint.style.left = "${box.offsetLeft + (box.offsetWidth - HINT_PX) / 2}px"
                    hint.style.top = "${box.offsetTop + box.offsetHeight - HINT_PX - EDGE_PX}px"
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
        val onTap: (Event) -> Unit = {
            if (direction == "right") {
                box.asDynamic().scrollBy(js("({ left: box.clientWidth * 0.8, behavior: 'smooth' })"))
            } else {
                box.asDynamic().scrollBy(js("({ top: box.clientHeight * 0.8, behavior: 'smooth' })"))
            }
        }
        val onResize: () -> Unit = { update() }
        // Size changes of the box or of its content (rows arriving) re-check the overflow.
        val sizes = js("new ResizeObserver(function () { onResize(); })")
        sizes.observe(box)
        box.firstElementChild?.let { sizes.observe(it) }
        box.addEventListener("scroll", onScroll)
        hint.addEventListener("click", onTap)
        window.setTimeout({ update() }, SETTLE_MS)
        onDispose {
            sizes.disconnect()
            box.removeEventListener("scroll", onScroll)
            hint.removeEventListener("click", onTap)
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

private const val HINT_PX = 32
private const val EDGE_PX = 10

/** Wait for the first layout (fonts, data) before measuring. */
private const val SETTLE_MS = 300

private const val ARROW_SVG =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>"""
