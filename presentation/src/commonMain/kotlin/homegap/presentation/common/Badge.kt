package homegap.presentation.common

import androidx.compose.runtime.Immutable
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
