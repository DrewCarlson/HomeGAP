package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.common.Badge
import homegap.presentation.common.ChecksModel
import homegap.presentation.overview.StatModel
import homegap.presentation.speedtest.MetricModel
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Coloured status pill; [small] for dense lists such as checks. */
@Composable
fun Pill(badge: Badge, small: Boolean = false) {
    Span({
        classes("pill", badge.health.name)
        if (small) classes("sm")
        badge.tooltip?.let { title(it) }
    }) { Text(badge.text) }
}

@Composable
fun Tag(text: String) {
    Span({ classes("tag") }) { Text(text) }
}

@Composable
fun Muted(text: String, mono: Boolean = false) {
    Div({
        classes("muted")
        if (mono) classes("mono")
    }) { Text(text) }
}

/** Inline muted text, for "none" and "—" placeholders. */
@Composable
fun Placeholder(text: String = "—") {
    Span({ classes("muted") }) { Text(text) }
}

@Composable
fun ErrorText(text: String, mono: Boolean = true) {
    Div({
        classes("error-text")
        if (mono) classes("mono")
    }) { Text(text) }
}

/** Overview counter tile. */
@Composable
fun Stat(stat: StatModel) {
    Div({ classes("stat", stat.health.name) }) {
        Div({ classes("stat-label") }) { Text(stat.label) }
        Div({ classes("stat-value") }) { Text(stat.value) }
        stat.hint?.let { Div({ classes("stat-hint") }) { Text(it) } }
    }
}

/** Labelled number in a speed test result. */
@Composable
fun Metric(metric: MetricModel) {
    Div({ classes("metric") }) {
        Div({ classes("metric-label") }) { Text(metric.label) }
        Div({ classes("metric-value") }) { Text(metric.value) }
    }
}

/** A row of small pills. */
@Composable
fun Badges(badges: List<Badge>) {
    Div({ classes("checks") }) { badges.forEach { Pill(it, small = true) } }
}

@Composable
fun Checks(checks: ChecksModel) {
    if (checks.isEmpty) {
        Placeholder()
        return
    }
    Badges(checks.problems + listOfNotNull(checks.passing))
}
