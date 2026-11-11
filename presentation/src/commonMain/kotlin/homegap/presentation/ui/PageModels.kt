package homegap.presentation.ui

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.routing.Location

/**
 * The top of every screen: a mono [caption] ("Nodes · 6 edges"), a [headline] that states the
 * answer ("5 of 6 edges healthy."), and at most one [statusLine] under it.
 */
@Immutable
data class PageHeadModel(
    val caption: String,
    val headline: String,
    val statusLine: StatusLineModel? = null,
    /** Buttons beside the headline (Add node). */
    val actions: List<ActionModel> = emptyList(),
)

/** A status message: what it says, how bad it is, and where it leads. */
@Immutable
data class StatusMessage(
    val severity: Severity,
    val text: String,
    val location: Location? = null,
)

/** The first [messages] entry is shown; the rest open from "+N more". */
@Immutable
data class StatusLineModel(val messages: List<StatusMessage>) {
    val first: StatusMessage get() = messages.first()
    val more: Int get() = messages.size - 1
}

/** How bad a message is; the color follows it (cyan, amber, rose). */
enum class Severity {
    Info,
    Warn,
    Down,
    ;

    companion object {
        fun of(health: Health): Severity = when (health) {
            Health.DOWN -> Down
            Health.DEGRADED -> Warn
            else -> Info
        }
    }
}

/** What a screen shows when it has nothing yet: what is missing, why, and one way forward. */
@Immutable
data class EmptyStateModel(
    val title: String,
    val text: String,
    val action: ActionModel? = null,
)

/** How an action button looks. */
enum class ActionStyle {
    Default,
    Primary,
    Danger,
}

/**
 * A button: it either runs [onClick] or opens [location]. [href] is for links that leave the
 * dashboard (documentation, a service). Actions that need confirming ask through
 * [homegap.presentation.data.Confirmations] inside [onClick]. [mutates] when it changes something,
 * so a read-only view turns it off: by default the ones that run [onClick].
 */
@Immutable
data class ActionModel(
    val label: String,
    val style: ActionStyle = ActionStyle.Default,
    val enabled: Boolean = true,
    val tooltip: String? = null,
    val location: Location? = null,
    val href: String? = null,
    val onClick: (() -> Unit)? = null,
    val mutates: Boolean = onClick != null,
)

/**
 * A confirmation that replaces the browser's confirm(): what will happen ([consequences]), the
 * button that does it, and for irreversible actions the name to type first ([typeToConfirm]).
 * Destructive buttons are never the default.
 */
@Immutable
data class ConfirmModel(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val consequences: List<String> = emptyList(),
    val typeToConfirm: String? = null,
    val danger: Boolean = false,
    /** A second way forward, shown between Cancel and the confirm button ("Resume everything"). */
    val secondaryLabel: String? = null,
)
