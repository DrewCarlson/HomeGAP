package homegap.presentation.ui

import androidx.compose.runtime.Immutable
import homegap.presentation.data.Samples
import homegap.presentation.format.ago
import homegap.presentation.format.oneDecimal
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/**
 * A small time-series graph that always prints its [current] value, its scale ([max] and 0) and
 * [p95]. [segments] are runs of points in a unit square (x right, y up); a gap in the data starts
 * a new run, and a run of one point is drawn as a dot. [empty] says why there is nothing to draw.
 */
@Immutable
data class ChartModel(
    val label: String,
    val unit: String,
    val current: String,
    val max: String,
    val p95: String?,
    val tone: ChartTone,
    val segments: List<List<ChartPoint>>,
    /** Where the threshold sits on the y axis (0 to 1), if there is one. */
    val threshold: Double? = null,
    val empty: String? = null,
)

/** A sample in the unit square, with what a hover over it says: "427 ms · 12m ago". */
@Immutable
data class ChartPoint(
    val x: Double,
    val y: Double,
    val label: String,
)

/** Colors carry meaning: cyan for healthy, amber over a threshold, violet and white for speed tests. */
enum class ChartTone {
    Cyan,
    Amber,
    Violet,
    White,
}

/**
 * A [ChartModel] for [samples] over the last [rangeSeconds] before [nowSeconds]. The current value
 * turns the line amber when it is above [threshold]. [unit] labels the chart until samples arrive.
 */
fun chartOf(
    label: String,
    samples: Samples?,
    nowSeconds: Long,
    rangeSeconds: Long = 3600,
    threshold: Double? = null,
    tone: ChartTone = ChartTone.Cyan,
    unit: String = "ms",
): ChartModel {
    val unit = samples?.unit ?: unit
    val start = nowSeconds - rangeSeconds
    val pairs = samples?.times.orEmpty().zip(samples?.values.orEmpty()).filter { it.first >= start }
    val values = pairs.mapNotNull { it.second }
    val current = pairs.lastOrNull()?.second
    // The scale follows the values. A threshold far over them would press the line flat.
    val top = niceCeiling((values.maxOrNull() ?: threshold ?: 0.0) * 1.2)
    val over = threshold != null && current != null && current > threshold
    return ChartModel(
        label = label,
        unit = unit,
        current = current?.let { formatMeasure(it, unit) } ?: "—",
        max = formatMeasure(top, unit),
        p95 = percentile(values, 0.95)?.let { formatMeasure(it, unit) },
        tone = if (over) ChartTone.Amber else tone,
        segments = segmentsOf(
            pairs = pairs,
            start = start,
            rangeSeconds = rangeSeconds,
            top = top,
            unit = unit,
            nowSeconds = nowSeconds,
        ),
        threshold = threshold?.takeIf { it <= top }?.let { it / top },
        empty = when {
            pairs.isEmpty() -> "No samples yet. The graph fills as measurements arrive."
            values.isEmpty() -> "Every measurement in this range failed."
            else -> null
        },
    )
}

/** Runs of points; a failed sample or a gap longer than 2.5 sample steps breaks the line. */
internal fun segmentsOf(
    pairs: List<Pair<Long, Double?>>,
    start: Long,
    rangeSeconds: Long,
    top: Double,
    unit: String,
    nowSeconds: Long,
): List<List<ChartPoint>> {
    if (pairs.isEmpty() || top <= 0) return emptyList()
    val steps = pairs.zipWithNext { a, b -> b.first - a.first }.filter { it > 0 }.sorted()
    val step = steps.getOrNull(steps.size / 2) ?: rangeSeconds
    val segments = mutableListOf<List<ChartPoint>>()
    var current = mutableListOf<ChartPoint>()
    var previousTime: Long? = null
    for ((time, value) in pairs) {
        val gap = previousTime != null && time - previousTime > step * 2.5
        if (value == null || gap) {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        }
        if (value != null) {
            current += ChartPoint(
                x = ((time - start).toDouble() / rangeSeconds).coerceIn(0.0, 1.0),
                y = (value / top).coerceIn(0.0, 1.0),
                label = "${formatMeasure(value, unit)} $unit · ${ago(time * 1000, nowSeconds * 1000)}",
            )
        }
        previousTime = time
    }
    if (current.isNotEmpty()) segments += current
    return segments
}

/** The [fraction] percentile (0.95 for p95) of [values], or null when there are none. */
fun percentile(values: List<Double>, fraction: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val index = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1
    return sorted[index]
}

/** A round number at or above [value] for the top of a scale: 1, 2 or 5 times a power of ten. */
fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(value)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).first { it * magnitude >= value }
    return step * magnitude
}

/** A measurement as the dashboard prints it: whole milliseconds, Mb/s with one decimal under 100. */
fun formatMeasure(value: Double, unit: String): String = when {
    unit == "ms" -> value.toLong().toString()
    value >= 100 -> value.toLong().toString()
    else -> value.oneDecimal()
}
