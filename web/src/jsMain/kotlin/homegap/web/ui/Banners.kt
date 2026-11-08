package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.app.BannerKind
import homegap.presentation.app.BannerModel
import homegap.presentation.app.RolloutModel
import homegap.presentation.app.TokenPromptModel
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

private val BannerKind.className: String
    get() = when (this) {
        BannerKind.Info -> "info"
        BannerKind.Warn -> "warn"
        BannerKind.Error -> "error"
    }

@Composable
fun Banner(banner: BannerModel) {
    Div({ classes("banner", banner.kind.className) }) { Text(banner.text) }
}

/** Shown while a failed node update holds the rollout to the other nodes. */
@Composable
fun RolloutBanner(rollout: RolloutModel) {
    Div({ classes("banner", BannerKind.Error.className, "rollout-banner") }) {
        Div {
            Text(rollout.summary)
            rollout.detail?.let { Div({ classes("mono", "banner-detail") }) { Text(it) } }
        }
        Div({ classes("row-actions") }) {
            rollout.failedNodes.forEach { nodeId ->
                ActionButton("Retry $nodeId", small = true) { rollout.onRetry(nodeId) }
                ActionButton(
                    "Skip $nodeId",
                    small = true,
                    tooltip = "Leave $nodeId on its current stack and continue updating the other nodes",
                ) { if (confirmSkipUpdate(nodeId)) rollout.onSkip(nodeId) }
            }
        }
    }
}

/** Asks before releasing a held rollout without updating [nodeId]. */
fun confirmSkipUpdate(nodeId: String): Boolean =
    window.confirm("Skip the update on $nodeId? It stays on its current stack and the other nodes are updated.")

/** Shown when the API asks for a dashboard token (or cannot be reached). */
@Composable
fun TokenBox(prompt: TokenPromptModel) {
    Section({ classes("panel") }) {
        Div({ classes("panel-body", "token-box") }) {
            TextField("Dashboard token", prompt.value, "Paste HOMEGAP_DASHBOARD_TOKEN", InputType.Password, prompt.onChange)
            ActionButton("Save", ButtonStyle.Primary, onClick = prompt.onSave)
        }
    }
}
