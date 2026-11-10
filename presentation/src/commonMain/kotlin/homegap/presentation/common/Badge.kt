package homegap.presentation.common

import androidx.compose.runtime.Immutable
import homegap.api.CheckDto
import homegap.api.Health
import homegap.presentation.format.label
import homegap.presentation.format.toHealth

/** A colored status pill. */
@Immutable
data class Badge(val health: Health, val text: String, val tooltip: String? = null) {
    companion object {
        fun of(health: Health): Badge = Badge(health, health.label)

        /** Green [ok] text when [isOk], red [notOk] text otherwise. */
        fun status(isOk: Boolean, ok: String, notOk: String): Badge = Badge(isOk.toHealth(), if (isOk) ok else notOk)
    }
}

/** A node's checks: problems listed individually, healthy ones folded into one counter. */
@Immutable
data class ChecksModel(val problems: List<Badge>, val passing: Badge?) {
    val isEmpty: Boolean get() = problems.isEmpty() && passing == null

    companion object {
        fun from(checks: List<CheckDto>): ChecksModel {
            val (good, bad) = checks.partition { it.health == Health.OK }
            val passing = good.takeIf { it.isNotEmpty() }?.let {
                Badge(
                    Health.OK,
                    if (bad.isEmpty()) "all ${it.size} passing" else "${it.size} passing",
                    it.joinToString("\n") { check -> check.describe() },
                )
            }
            return ChecksModel(bad.map { Badge(it.health, it.name, it.detail.ifEmpty { it.name }) }, passing)
        }

        private fun CheckDto.describe(): String = if (detail.isEmpty()) name else "$name — $detail"
    }
}
