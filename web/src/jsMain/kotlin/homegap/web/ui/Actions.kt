package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.routing.Location
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.EmptyStateModel
import homegap.web.routing.HashRouter
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/** An [ActionModel] as a button, an in-app link or an outside link. */
@Composable
fun Action(
    action: ActionModel,
    onOpen: (Location) -> Unit,
    small: Boolean = false,
) {
    val style = when (action.style) {
        ActionStyle.Default -> ButtonStyle.Default
        ActionStyle.Primary -> ButtonStyle.Primary
        ActionStyle.Danger -> ButtonStyle.Danger
    }
    val location = action.location
    val href = action.href
    when {
        // A link into something that changes things (the Add node wizard) is a button that is off.
        location != null && action.mutates && LocalReadOnly.current -> ActionButton(
            text = action.label,
            style = style,
            small = small,
            onClick = {},
        )

        location != null -> NavLink(
            href = HashRouter.href(location),
            onNavigate = { onOpen(location) },
            classNames = listOfNotNull("btn", style.className, if (small) "sm" else null),
        ) {
            Text(action.label)
        }

        href != null -> A(href = href, {
            classes(*listOfNotNull("btn", style.className, if (small) "sm" else null).toTypedArray())
            target(ATarget.Blank)
            attr("rel", "noopener")
        }) {
            Text("${action.label} ↗")
        }

        else -> ActionButton(
            text = action.label,
            style = style,
            small = small,
            enabled = action.enabled,
            tooltip = action.tooltip,
            mutates = action.mutates,
            onClick = { action.onClick?.invoke() },
        )
    }
}

/** What a screen shows with nothing in it yet: what is missing, why, and one action. */
@Composable
fun EmptyState(
    empty: EmptyStateModel,
    onOpen: (Location) -> Unit,
) {
    Div({ classes("empty-state") }) {
        H2 { Text(empty.title) }
        P { Text(empty.text) }
        empty.action?.let { action ->
            Div({ classes("empty-actions") }) {
                Action(
                    action = action,
                    onOpen = onOpen,
                )
            }
        }
    }
}
