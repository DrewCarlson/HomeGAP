package homegap.presentation.app

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.data.Toast
import homegap.presentation.routing.Route

/** Everything the page shell renders, plus the active screen's model. */
@Immutable
data class AppModel(
    val route: Route,
    val nav: List<NavItem>,
    /** Present once the first status snapshot arrived. */
    val header: HeaderModel?,
    val connection: ConnectionModel?,
    val banners: List<BannerModel>,
    val rollout: RolloutModel?,
    /** Null until the first snapshot (or when the controller cannot be reached). */
    val screen: ScreenModel?,
    val toast: Toast?,
    val onNavigate: (Route) -> Unit,
) {
    val isLoading: Boolean get() = screen == null && connection == null
}

@Immutable
data class NavItem(val route: Route, val selected: Boolean, val needsAttention: Boolean)

@Immutable
data class HeaderModel(
    val domain: String,
    val versionLine: String,
    val pass: PassStatusModel,
    val paused: Boolean,
    val busy: Boolean,
    val onResync: () -> Unit,
    val onTogglePause: () -> Unit,
)

/** "Synced 12s ago" with a colored dot; details in the tooltip. */
@Immutable
data class PassStatusModel(val health: Health, val text: String, val tooltip: String?)

enum class BannerKind { Info, Warn, Error }

@Immutable
data class BannerModel(val kind: BannerKind, val text: String)

/** The controller cannot be reached; [tokenPrompt] is set when a dashboard token is needed or may help. */
@Immutable
data class ConnectionModel(val message: String, val tokenPrompt: TokenPromptModel?)

@Immutable
data class TokenPromptModel(val value: String, val onChange: (String) -> Unit, val onSave: () -> Unit)

/** Shown while a failed node update holds the rollout to the other nodes. */
@Immutable
data class RolloutModel(
    val summary: String,
    val detail: String?,
    val failedNodes: List<String>,
    val onRetry: (nodeId: String) -> Unit,
    val onSkip: (nodeId: String) -> Unit,
)
