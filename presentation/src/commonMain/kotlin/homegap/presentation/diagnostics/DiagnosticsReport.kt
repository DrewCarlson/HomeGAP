package homegap.presentation.diagnostics

import androidx.compose.runtime.Immutable
import homegap.api.Health

/** One section of a node's diagnostics bundle, with a verdict and a one-line summary. */
@Immutable
data class ReportSection(
    val title: String,
    val health: Health,
    val summary: String,
)

/**
 * Splits a diagnostics bundle (sections start with `=== Title`) into a report: each section's
 * verdict and summary, from what the text says. Sections it has no rule for are only summarized.
 */
fun reportOf(bundle: String): List<ReportSection> {
    val sections = mutableListOf<Pair<String, MutableList<String>>>()
    for (line in AnsiText.plain(bundle).lines()) {
        if (line.startsWith("=== ")) {
            sections += line.removePrefix("=== ").trim() to mutableListOf()
        } else if (line.isNotBlank()) {
            sections.lastOrNull()?.second?.add(line)
        }
    }
    return sections.map { (title, lines) -> verdict(title, lines) }
}

private fun verdict(title: String, lines: List<String>): ReportSection {
    val text = lines.joinToString("\n")
    val lower = text.lowercase()
    fun section(health: Health, summary: String) = ReportSection(title, health, summary)
    return when {
        lines.isEmpty() -> section(Health.UNKNOWN, "no output")
        title.endsWith("stack") || title == "Cluster member" || title == "Exit connector" -> {
            if (lower.startsWith("no ")) {
                section(Health.UNKNOWN, lines.first())
            } else {
                val containers = lines.drop(1)
                val bad = containers.filter { line ->
                    listOf("exited", "restarting", "unhealthy", "dead", "created").any { it in line.lowercase() }
                }
                if (bad.isEmpty()) {
                    section(Health.OK, "${containers.size} containers up")
                } else {
                    section(Health.DEGRADED, "${bad.size} of ${containers.size} not running: ${bad.first().substringBefore(' ')}")
                }
            }
        }

        title.startsWith("Firewall") -> when {
            "status: active" in lower -> section(Health.OK, "active · ${lines.count { "allow" in it.lowercase() }} allow rules")
            "inactive" in lower -> section(Health.DEGRADED, "inactive")
            else -> section(Health.UNKNOWN, lines.first())
        }

        title == "Mesh interface" -> if (lower.startsWith("no mesh")) {
            section(Health.UNKNOWN, lines.first())
        } else {
            section(Health.OK, "${lines.count { it.startsWith("peer:") }} peers")
        }

        title == "Connection tracking" -> {
            val numbers = Regex("\\d+").findAll(text).map { it.value.toLong() }.toList()
            val used = numbers.getOrNull(0)
            val max = numbers.getOrNull(1)
            if (used != null && max != null && max > 0) {
                val share = used * 100 / max
                section(if (share >= 80) Health.DEGRADED else Health.OK, "$used of $max entries ($share%)")
            } else {
                section(Health.UNKNOWN, lines.first())
            }
        }

        "log" in title.lowercase() || title == "Kernel messages" -> {
            val errors = lines.count { line -> listOf("error", "fatal", "panic", "oom").any { it in line.lowercase() } }
            if (errors == 0) {
                section(Health.OK, "${lines.size} lines, no errors")
            } else {
                section(Health.DEGRADED, "$errors error lines of ${lines.size}")
            }
        }

        else -> section(Health.UNKNOWN, lines.first().take(80))
    }
}
