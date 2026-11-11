package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.app.BannerKind
import homegap.presentation.app.BannerModel
import homegap.presentation.app.RolloutModel
import homegap.presentation.app.TokenPromptModel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private val BannerKind.className: String
    get() = when (this) {
        BannerKind.Info -> "info"
        BannerKind.Warn -> "warn"
        BannerKind.Error -> "error"
    }

@Composable
fun Banner(banner: BannerModel, onResume: () -> Unit = {}) {
    Div({
        classes("banner", banner.kind.className)
        if (banner.resume) {
            classes("paused-banner")
        }
    }) {
        Span { Text(banner.text) }
        if (banner.resume) {
            ActionButton(
                text = "Resume",
                style = ButtonStyle.Primary,
                onClick = onResume,
            )
        }
    }
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
                ActionButton(
                    text = "Retry $nodeId",
                    small = true,
                    onClick = { rollout.onRetry(nodeId) },
                )
                ActionButton(
                    text = "Skip $nodeId",
                    small = true,
                    tooltip = "Leave $nodeId on its current stack and continue updating the other nodes",
                    onClick = { rollout.onSkip(nodeId) },
                )
            }
        }
    }
}

/** Shown when the API asks for a dashboard token (or cannot be reached). */
@Composable
fun TokenBox(prompt: TokenPromptModel) {
    Section({ classes("figure") }) {
        Div({ classes("figure-body", "token-box") }) {
            TextField(
                label = "Dashboard token",
                value = prompt.value,
                placeholder = "Paste HOMEGAP_DASHBOARD_TOKEN",
                type = InputType.Password,
                onChange = prompt.onChange,
            )
            ActionButton(
                text = "Save",
                style = ButtonStyle.Primary,
                mutates = false,
                onClick = prompt.onSave,
            )
        }
    }
}
