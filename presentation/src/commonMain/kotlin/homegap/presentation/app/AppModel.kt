package homegap.presentation.app

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.presentation.core.ScreenModel
import homegap.presentation.data.Toast
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.search.SearchHit
import homegap.presentation.search.SearchKind
import homegap.presentation.ui.ConfirmModel

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
    /** A page over the screen: the Add node wizard over Nodes, Locations over Config. */
    val overlay: ScreenModel? = null,
    val toasts: List<Toast>,
    val search: SearchModel,
    /** Change location for a node, over whichever screen opened it. */
    val nodeLocation: homegap.presentation.nodes.NodeLocationModel? = null,
    /** The terminal panel, while a shell is open on a node. */
    val terminal: homegap.presentation.terminal.TerminalPanelModel? = null,
    /** The confirm dialog, while a presenter asks. */
    val confirm: ConfirmDialogModel?,
    val onNavigate: (Route) -> Unit,
    /** Opens a page with an item selected (or none, which closes the Inspector). */
    val onOpen: (Location) -> Unit,
) {
    val isLoading: Boolean get() = screen == null && connection == null
}

/** A navigation entry; [attention] counts the items in its section that need attention. */
@Immutable
data class NavItem(
    val route: Route,
    val selected: Boolean,
    val attention: AttentionCount?,
) {
    val needsAttention: Boolean get() = attention != null
}

/** How many items need attention, and how bad the worst is ([Health.DOWN] or [Health.DEGRADED]). */
@Immutable
data class AttentionCount(val count: Int, val severity: Health)

@Immutable
data class HeaderModel(
    val domain: String,
    val versionLine: String,
    val pass: PassStatusModel,
    val paused: Boolean,
    val busy: Boolean,
    val onResync: () -> Unit,
    val onTogglePause: () -> Unit,
    /** While paused: one pass that applies the waiting changes. */
    val onApplyOnce: () -> Unit = {},
    /** Removes the dashboard token from this browser. */
    val onSignOut: () -> Unit = {},
)

/** "Synced 12s ago" with a colored dot; details in the tooltip. */
@Immutable
data class PassStatusModel(val health: Health, val text: String, val tooltip: String?)

enum class BannerKind { Info, Warn, Error }

/** A banner over every screen; [resume] adds the Resume button (the paused banner). */
@Immutable
data class BannerModel(
    val kind: BannerKind,
    val text: String,
    val resume: Boolean = false,
)

/**
 * The controller cannot be reached. [signInRequired] when the API wants a dashboard token: the
 * sign-in dialog blocks the page until one works. [tokenPrompt] is set when a token is needed or
 * may help.
 */
@Immutable
data class ConnectionModel(
    val message: String,
    val tokenPrompt: TokenPromptModel?,
    val signInRequired: Boolean = false,
    /** The controller can't be reached (rather than refusing the token). */
    val lost: LostConnectionModel? = null,
)

/**
 * The controller has been out of reach for a few seconds: what failed, when it tries again,
 * and a way to try now. The data on the page behind it is the last that arrived.
 */
@Immutable
data class LostConnectionModel(
    val error: String,
    /** "Trying again in 4 s", or "Trying now…" while an attempt runs. */
    val retry: String,
    /** "3 attempts failed", or null before the first one finished. */
    val attempts: String?,
    /** "Last update 2m ago", or null when nothing has arrived yet. */
    val lastUpdate: String?,
    val reconnecting: Boolean,
    val onReconnect: () -> Unit,
)

/** The token field; [rejected] when the token that was tried wasn't accepted. */
@Immutable
data class TokenPromptModel(
    val value: String,
    val rejected: Boolean = false,
    /** The token shows as text, so a pasted value can be checked. */
    val revealed: Boolean = false,
    /** The orchestrator checks the token now. */
    val checking: Boolean = false,
    val onChange: (String) -> Unit,
    val onToggleReveal: () -> Unit = {},
    val onSave: () -> Unit,
)

/** Shown while a failed node update holds the rollout to the other nodes. */
@Immutable
data class RolloutModel(
    val summary: String,
    val detail: String?,
    val failedNodes: List<String>,
    val onRetry: (nodeId: String) -> Unit,
    val onSkip: (nodeId: String) -> Unit,
)

/**
 * The search overlay. [groups] hold the results (or suggestions while [query] is empty);
 * [highlighted] counts through all of them, in order.
 */
@Immutable
data class SearchModel(
    val isOpen: Boolean,
    val query: String,
    val kind: SearchKind?,
    val kinds: List<SearchKind>,
    val groups: List<SearchGroup>,
    val highlighted: Int,
    /** What the list says when it has no result, or null when it has one. */
    val emptyText: String? = null,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onQuery: (String) -> Unit,
    val onKind: (SearchKind?) -> Unit,
    val onMove: (Int) -> Unit,
    val onChoose: (SearchHit) -> Unit,
) {
    val count: Int get() = groups.sumOf { it.hits.size }

    /** The result Enter opens. */
    val highlightedHit: SearchHit? get() = groups.flatMap { it.hits }.getOrNull(highlighted)
}

@Immutable
data class SearchGroup(val title: String, val hits: List<SearchHit>)

/** The confirm dialog: the question, what has been typed so far, and its two answers. */
@Immutable
data class ConfirmDialogModel(
    val model: ConfirmModel,
    val typed: String,
    val onType: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    val onSecondary: () -> Unit = {},
) {
    /** Typing the name is needed only for irreversible actions. */
    val canConfirm: Boolean get() = model.typeToConfirm == null || (typed.isNotBlank() && typed.trim() == model.typeToConfirm.trim())
}
