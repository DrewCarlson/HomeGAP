package homegap.web.screens

import androidx.compose.runtime.Composable
import homegap.presentation.map.MapModel
import homegap.presentation.nodes.NodeLocationModel
import homegap.web.ui.*
import org.jetbrains.compose.web.dom.*

/** Moves a node: search a known city or pick on the map, then save. A sheet on phones. */
@Composable
fun NodeLocationDialog(model: NodeLocationModel, viewport: Viewport) {
    Overlay(
        label = "Change the location of ${model.nodeId}",
        titleId = "node-location-title",
        style = if (viewport == Viewport.Phone) OverlayStyle.Sheet else OverlayStyle.Modal,
        onDismiss = model.onCancel,
    ) {
        Div({ classes("overlay-panel", "form-panel", "node-location") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text("Nodes › ${model.nodeId}") }
                    H2({ id("node-location-title") }) { Text("Change location") }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close",
                    onClick = model.onCancel,
                )
            }
            Div({ classes("form-body") }) {
                ScrollHint()
                P({ classes("muted") }) {
                    Text("Geo DNS sends visitors to the nearest node by these coordinates; the maps place it here too.")
                }
                Div({ classes("inline-row") }) {
                    Muted("Now")
                    Text(model.current ?: "not set")
                }
                LocationSearchBox(
                    query = model.query,
                    matches = model.matches,
                    onQuery = model.onQuery,
                    onPick = model.onChoose,
                    placeholder = "Search a city, or tap the map",
                )
                WorldMap(
                    model = MapModel(),
                    heightPx = 240,
                    onPick = model.onPickOnMap,
                    pick = model.pick,
                )
                model.pending?.let { pending ->
                    Div({ classes("inline-row") }) {
                        Muted("New")
                        B { Text(pending) }
                    }
                }
            }
            Div({ classes("form-actions") }) {
                ActionButton(
                    text = "Cancel",
                    onClick = model.onCancel,
                )
                ActionButton(
                    text = "Save location",
                    style = ButtonStyle.Primary,
                    enabled = model.pending != null,
                    onClick = model.onSave,
                )
            }
        }
    }
}
