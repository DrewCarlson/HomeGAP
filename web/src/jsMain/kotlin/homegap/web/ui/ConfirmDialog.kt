package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.app.ConfirmDialogModel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoFocus
import org.jetbrains.compose.web.dom.*

/**
 * Replaces the browser's confirm() and prompt(): what will happen, what won't, and for
 * irreversible actions the name to type. The destructive button is never the default; Esc cancels.
 */
@Composable
fun ConfirmDialog(
    dialog: ConfirmDialogModel,
    viewport: Viewport,
) {
    val model = dialog.model
    Overlay(
        label = model.title,
        titleId = "confirm-title",
        style = if (viewport == Viewport.Phone) OverlayStyle.Sheet else OverlayStyle.Modal,
        onDismiss = dialog.onCancel,
    ) {
        Div({
            classes("overlay-panel", "confirm")
            if (model.danger) {
                classes("danger")
            }
        }) {
            H2({ id("confirm-title") }) { Text(model.title) }
            P({ classes("confirm-text") }) { Text(model.text) }
            if (model.consequences.isNotEmpty()) {
                Ul({ classes("confirm-list") }) {
                    model.consequences.forEach { Li { Text(it) } }
                }
            }
            model.typeToConfirm?.let { name ->
                Label {
                    Span {
                        Text("Type ")
                        B { Text(name) }
                        Text(" to confirm")
                    }
                    Input(InputType.Text) {
                        value(dialog.typed)
                        attr("autocomplete", "off")
                        attr("spellcheck", "false")
                        autoFocus()
                        onInput { dialog.onType(it.value) }
                    }
                }
            }
            Div({ classes("confirm-actions") }) {
                ActionButton(
                    text = "Cancel",
                    onClick = dialog.onCancel,
                )
                model.secondaryLabel?.let { label ->
                    ActionButton(
                        text = label,
                        onClick = dialog.onSecondary,
                    )
                }
                ActionButton(
                    text = model.confirmLabel,
                    style = if (model.danger) ButtonStyle.Danger else ButtonStyle.Primary,
                    enabled = dialog.canConfirm,
                    onClick = dialog.onConfirm,
                )
            }
        }
    }
}
