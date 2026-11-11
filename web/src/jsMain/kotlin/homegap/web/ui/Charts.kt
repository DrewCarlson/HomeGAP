package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.ui.ChartModel
import homegap.presentation.ui.ChartPoint
import homegap.presentation.ui.ChartTone
import org.jetbrains.compose.web.ExperimentalComposeWebSvgApi
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.svg.Path
import org.jetbrains.compose.web.svg.Svg

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
                Sparkline(chart)
            }
        }
    }
}

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
