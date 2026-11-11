package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import homegap.presentation.ui.ChartModel
import homegap.presentation.ui.ChartPoint
import homegap.presentation.ui.ChartTone
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.ElementScope
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.svg.Path
import org.jetbrains.compose.web.svg.Svg
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.abs

/**
 * A time-series graph with its current value, unit, max and p95 printed above it and its scale
 * (max and 0) beside it. With nothing to draw it says why, never an empty box.
 */
@Composable
fun Chart(chart: ChartModel, compact: Boolean = false) {
    Div({
        classes("chart", chart.tone.className)
        if (compact) {
            classes("compact")
        }
    }) {
        Div({ classes("chart-head") }) {
            Span({ classes("chart-label") }) { Text(chart.label) }
            Span({ classes("chart-value") }) {
                Span({ classes("chart-now") }) { Text(chart.current) }
                Text(" ${chart.unit}")
                chart.p95?.let { Text(" · p95 $it") }
            }
        }
        val empty = chart.empty
        if (empty != null) {
            Div({ classes("chart-empty") }) { Text(empty) }
        } else {
            Div({ classes("chart-body") }) {
                Div({ classes("chart-scale") }) {
                    Span { Text(chart.max) }
                    Span { Text("0") }
                }
                Div({ classes("chart-plot") }) {
                    ChartHover(chart)
                    Sparkline(chart)
                    Div({
                        classes("chart-guide")
                        attr("aria-hidden", "true")
                    })
                    Div({
                        classes("chart-dot")
                        attr("aria-hidden", "true")
                    })
                    Div({
                        classes("chart-tip")
                        attr("aria-hidden", "true")
                    })
                }
            }
        }
    }
}

/**
 * Pointing at the plot (a mouse, or a finger: a tap or a sideways drag) shows the nearest sample:
 * a guide line, a dot and its label ("427 ms · 12m ago"). It hides when the mouse leaves, or two
 * seconds after the finger lifts. It updates the DOM directly, so moving costs no recomposition.
 */
@Composable
private fun ElementScope<HTMLElement>.ChartHover(chart: ChartModel) {
    val points by rememberUpdatedState(chart.segments.flatten())
    DisposableEffect(Unit) {
        val plot = scopeElement
        val guide = plot.querySelector(".chart-guide") as HTMLElement
        val dot = plot.querySelector(".chart-dot") as HTMLElement
        val tip = plot.querySelector(".chart-tip") as HTMLElement
        var hideTimer = 0

        fun hide() {
            plot.classList.remove("hovering")
        }

        val onPoint: (Event) -> Unit = { event ->
            val pointer = event as PointerEvent
            val box = plot.getBoundingClientRect()
            val x = ((pointer.clientX - box.left) / box.width).coerceIn(0.0, 1.0)
            points.minByOrNull { abs(it.x - x) }?.let { point ->
                window.clearTimeout(hideTimer)
                val left = point.x * box.width
                val top = (1 - point.y) * box.height
                guide.style.left = "${left}px"
                dot.style.left = "${left}px"
                dot.style.top = "${top}px"
                tip.textContent = point.label
                // Centered over the sample, but kept inside the plot.
                val half = tip.offsetWidth / 2.0
                tip.style.left = "${left.coerceIn(half, (box.width - half).coerceAtLeast(half))}px"
                plot.classList.add("hovering")
            }
        }
        val onLeave: (Event) -> Unit = { event ->
            if ((event as PointerEvent).pointerType == "mouse") {
                hide()
            } else {
                hideTimer = window.setTimeout({ hide() }, TOUCH_TIP_MS)
            }
        }
        plot.addEventListener("pointerdown", onPoint)
        plot.addEventListener("pointermove", onPoint)
        plot.addEventListener("pointerleave", onLeave)
        plot.addEventListener("pointerup", onLeave)
        plot.addEventListener("pointercancel", onLeave)
        onDispose {
            window.clearTimeout(hideTimer)
            plot.removeEventListener("pointerdown", onPoint)
            plot.removeEventListener("pointermove", onPoint)
            plot.removeEventListener("pointerleave", onLeave)
            plot.removeEventListener("pointerup", onLeave)
            plot.removeEventListener("pointercancel", onLeave)
        }
    }
}

/** How long a touch's reading stays after the finger lifts. */
private const val TOUCH_TIP_MS = 2000

/** The line itself: runs of points, a dot for a run of one, and a dashed threshold. */
@OptIn(ExperimentalComposeWebSvgApi::class)
@Composable
fun Sparkline(chart: ChartModel) {
    Svg(
        viewBox = "0 0 $WIDTH $HEIGHT",
        attrs = {
            classes("sparkline")
            attr("preserveAspectRatio", "none")
            attr("role", "img")
            attr("aria-label", "${chart.label}: ${chart.current} ${chart.unit}, max ${chart.max}")
        },
    ) {
        chart.threshold?.let { level ->
            Path(
                d = "M0 ${y(level)}H$WIDTH",
                attrs = { classes("sparkline-threshold") },
            )
        }
        chart.segments.forEach { segment ->
            Path(
                d = pathOf(segment),
                attrs = {
                    classes(if (segment.size == 1) "sparkline-dot" else "sparkline-line")
                },
            )
        }
    }
}

private const val WIDTH = 300
private const val HEIGHT = 60

private fun y(level: Double): Double = (1 - level) * HEIGHT

private fun pathOf(points: List<ChartPoint>): String {
    val first = points.first()
    if (points.size == 1) {
        return "M${first.x * WIDTH} ${y(first.y)}l0 0"
    }
    return points.mapIndexed { index, point ->
        "${if (index == 0) "M" else "L"}${point.x * WIDTH} ${y(point.y)}"
    }.joinToString(" ")
}

/** The CSS class that colors a chart or gauge. */
val ChartTone.className: String
    get() = when (this) {
        ChartTone.Cyan -> "tone-cyan"
        ChartTone.Amber -> "tone-amber"
        ChartTone.Violet -> "tone-violet"
        ChartTone.White -> "tone-white"
    }
