package homegap.presentation.format

import homegap.api.Health
import kotlin.math.roundToLong

/** One decimal place ("12.3"). */
fun Double.oneDecimal(): String {
    val tenths = (this * 10).roundToLong()
    val sign = if (tenths < 0) "-" else ""
    val abs = kotlin.math.abs(tenths)
    return "$sign${abs / 10}.${abs % 10}"
}

/**
 * "just now", "42s ago", "5m ago", "3h ago" for [epochMillis] as seen at [now]. From 48 hours on it
 * is the day, "Nov 12", in UTC: a count of days tells less than the date.
 */
fun ago(epochMillis: Long, now: Long): String {
    val seconds = (now - epochMillis) / 1000
    return when {
        seconds < 5 -> "just now"
        seconds < 90 -> "${seconds}s ago"
        seconds < 90 * 60 -> "${seconds / 60}m ago"
        seconds < 48 * 3600 -> "${seconds / 3600}h ago"
        else -> shortDate(epochMillis)
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The UTC day of [epochMillis] as "Nov 12" (the civil-from-days algorithm). */
internal fun shortDate(epochMillis: Long): String {
    val days = epochMillis.floorDiv(86_400_000L) + 719_468
    val era = days.floorDiv(146_097L)
    val dayOfEra = days - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    return "${MONTHS[(month - 1).toInt()]} $day"
}

fun formatBytes(n: Long): String {
    val kib = 1024.0
    return when {
        n < kib -> "$n B"
        n < kib * kib -> "${(n / kib).oneDecimal()} KB"
        n < kib * kib * kib -> "${(n / (kib * kib)).oneDecimal()} MB"
        else -> "${(n / (kib * kib * kib)).oneDecimal()} GB"
    }
}

fun formatMillis(value: Double?): String = value?.let { "${it.oneDecimal()} ms" } ?: "—"

fun formatMbps(value: Double?): String = when {
    value == null -> "—"
    value >= 100 -> "${value.toInt()} Mb/s"
    else -> "${value.oneDecimal()} Mb/s"
}

fun plural(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

/** Condenses a browser user agent (or a "node …" client label) to something readable. */
fun shortClient(userAgent: String): String {
    if (userAgent.startsWith("node ")) return userAgent
    val os = listOf("iPhone", "iPad", "Android", "Mac OS X", "Windows", "Linux")
        .firstOrNull { it in userAgent }
        ?.let { if (it == "Mac OS X") "macOS" else it }
    val browser = listOf("Firefox", "Edg", "Chrome", "Safari")
        .firstOrNull { it in userAgent }
        ?.let { if (it == "Edg") "Edge" else it }
    return listOfNotNull(browser, os).joinToString(" on ").ifEmpty { userAgent.take(60) }
}

val Health.label: String
    get() = when (this) {
        Health.OK -> "healthy"
        Health.DEGRADED -> "warning"
        Health.DOWN -> "failing"
        Health.UNKNOWN -> "unknown"
    }

fun Boolean.toHealth(): Health = if (this) Health.OK else Health.DOWN

fun Boolean?.toHealth(): Health = when (this) {
    true -> Health.OK
    false -> Health.DOWN
    null -> Health.UNKNOWN
}
