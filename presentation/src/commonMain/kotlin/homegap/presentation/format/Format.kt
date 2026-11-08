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

/** "just now", "42s ago", "5m ago", "3h ago", "2d ago" for [epochMillis] as seen at [now]. */
fun ago(epochMillis: Long, now: Long): String {
    val seconds = (now - epochMillis) / 1000
    return when {
        seconds < 5 -> "just now"
        seconds < 90 -> "${seconds}s ago"
        seconds < 90 * 60 -> "${seconds / 60}m ago"
        seconds < 48 * 3600 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
}

fun formatBytes(n: Long): String {
    val kib = 1024.0
    return when {
        n < kib -> "$n B"
        n < kib * kib -> "${(n / kib).oneDecimal()} KiB"
        n < kib * kib * kib -> "${(n / (kib * kib)).oneDecimal()} MiB"
        else -> "${(n / (kib * kib * kib)).oneDecimal()} GiB"
    }
}

fun formatMillis(value: Double?): String = value?.let { "${it.oneDecimal()} ms" } ?: "—"

fun formatMbps(value: Double?): String = when {
    value == null -> "—"
    value >= 100 -> "${value.toInt()} Mbps"
    else -> "${value.oneDecimal()} Mbps"
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
        Health.DEGRADED -> "degraded"
        Health.DOWN -> "down"
        Health.UNKNOWN -> "unknown"
    }

fun Boolean.toHealth(): Health = if (this) Health.OK else Health.DOWN

fun Boolean?.toHealth(): Health = when (this) {
    true -> Health.OK
    false -> Health.DOWN
    null -> Health.UNKNOWN
}
