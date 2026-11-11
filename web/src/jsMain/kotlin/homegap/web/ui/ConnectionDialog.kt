package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.app.LostConnectionModel
import homegap.presentation.app.TokenPromptModel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.*

/**
 * Shown while the orchestrator is out of reach: what failed, a live countdown to the next try,
 * Reconnect now, and a way to enter a different token. It closes by itself once a request gets
 * through; the page behind it keeps the last data that arrived.
 */
@Composable
fun LostConnectionDialog(lost: LostConnectionModel, tokenPrompt: TokenPromptModel?) {
    Overlay(
        label = "Connection lost",
        titleId = "lost-title",
        style = OverlayStyle.Modal,
    ) {
        Div({ classes("overlay-panel", "sign-in", "lost") }) {
            Div({ classes("lost-status", "caption") }) {
                Span({ classes("status-dot", "DOWN", "pulse") })
                Text("Connection lost")
            }
            H2({ id("lost-title") }) { Text("Can't reach the orchestrator") }
            P({ classes("sign-in-text") }) {
                Text("The page shows the last data it received. HomeGAP keeps trying on its own.")
            }
            Code({ classes("lost-error") }) { Text(lost.error) }
            Div({ classes("lost-meta", "mono") }) {
                Span { Text(lost.retry) }
                Span({
                    attr("role", "status")
                    attr("aria-live", "polite")
                }) {
                    Text(listOfNotNull(lost.attempts, lost.lastUpdate).joinToString(" · "))
                }
            }
            Button({
                classes("btn", "primary", "wide")
                if (lost.reconnecting) {
                    disabled()
                }
                onClick { lost.onReconnect() }
            }) {
                Text(if (lost.reconnecting) "Trying…" else "Reconnect now")
            }
            tokenPrompt?.let { prompt ->
                Details({ classes("lost-token") }) {
                    Summary { Text("Use a different token") }
                    Form(attrs = {
                        onSubmit { event ->
                            event.preventDefault()
                            prompt.onSave()
                        }
                    }) {
                        TextField(
                            label = "Dashboard token",
                            value = prompt.value,
                            placeholder = "Paste HOMEGAP_DASHBOARD_TOKEN",
                            type = InputType.Password,
                            onChange = prompt.onChange,
                        )
                        Button({
                            classes("btn")
                            attr("type", "submit")
                        }) {
                            Text("Save and reconnect")
                        }
                    }
                }
            }
        }
    }
}
