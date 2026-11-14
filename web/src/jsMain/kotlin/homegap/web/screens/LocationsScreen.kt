package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.locations.LocationField
import homegap.presentation.locations.LocationsEvent
import homegap.presentation.locations.LocationsScreenModel
import homegap.web.ui.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*

private val KNOWN_LOCATIONS_HEADERS = listOf("Name", "Country", "Region", "Coordinates", "Providers", "Source", "")

@Composable
fun LocationsScreen(model: LocationsScreenModel) {
    val send = model.onEvent
    val home = model.homelab
    Panel(
        title = "Known locations",
        subtitle = "${model.total} cities, offered when you add a node.",
        actions = {
            ActionButton(
                text = "Add location",
                style = ButtonStyle.Primary,
                small = true,
                onClick = { send(LocationsEvent.Add) },
            )
        },
    ) {
        Div({ classes("figure-body") }) {
            Input(InputType.Search) {
                value(model.query)
                placeholder("Filter by city, country or provider")
                onInput { send(LocationsEvent.Search(it.value)) }
            }
        }
        val empty = if (model.loading) "Loading…" else "No locations match."
        DataTable(
            headers = KNOWN_LOCATIONS_HEADERS,
            emptyText = empty,
            isEmpty = model.rows.isEmpty(),
        ) {
            model.rows.forEach { r ->
                key(r.id) {
                    Tr {
                        Cell("Name", "primary-cell") {
                            B { Text(r.name) }
                        }
                        Cell("Country") { Text(r.country) }
                        Cell("Region") { Text(r.region) }
                        Cell("Coordinates", "mono") {
                            Text(r.coordinates)
                        }
                        Cell("Providers") {
                            Text(r.providers.ifBlank { "—" })
                        }
                        Cell("Source") {
                            Pill(
                                Badge(
                                    health = if (r.source == "shipped") Health.UNKNOWN else Health.OK,
                                    text = r.source
                                ),
                                small = true
                            )
                        }
                        Cell("", "actions", "row-menu") {
                            KebabMenu(
                                label = "Actions for ${r.name}",
                                items = listOfNotNull(
                                    MenuItem(
                                        label = "Edit",
                                        onClick = { send(LocationsEvent.Edit(r.id)) },
                                    ),
                                    MenuItem(
                                        label = "Restore the shipped version",
                                        onClick = { send(LocationsEvent.Reset(r.id)) },
                                    ).takeIf { r.source == "edited" },
                                    MenuItem(
                                        label = "Remove",
                                        danger = true,
                                        onClick = { send(LocationsEvent.Delete(r.id)) },
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (model.rows.size < model.matching) {
            Div({ classes("figure-body", "show-all") }) {
                ActionButton(
                    text = "Show all ${model.matching}",
                    onClick = { send(LocationsEvent.ShowAll) },
                )
            }
        }
    }
    model.editor?.let { e ->
        Panel(
            title = if (e.isNew) "Add a location" else "Edit ${e[LocationField.Name]}",
            flush = false
        ) {
            Div({ classes("form-grid") }) {
                LocationField.entries.forEach { f ->
                    if (f == LocationField.Id && !e.isNew) return@forEach
                    TextField(
                        label = f.label,
                        value = e[f],
                        placeholder = f.placeholder
                    ) {
                        send(LocationsEvent.Change(f, it))
                    }
                }
            }
            e.error?.let {
                Div({ classes("banner", "error") }) {
                    Text(it)
                }
            }
            Div({ classes("inline-row") }) {
                ActionButton("Save", ButtonStyle.Primary) {
                    send(LocationsEvent.Save)
                }
                ActionButton("Cancel") {
                    send(LocationsEvent.Cancel)
                }
            }
        }
    }
    Panel(
        title = "Homelab",
        subtitle = "The public status page shows only its region.",
        flush = false
    ) {
        Div({ classes("inline-row") }) {
            Muted("Now")
            Text(home.current ?: "not set")
            if (home.current != null) {
                ActionButton("Clear", small = true) {
                    send(LocationsEvent.ClearHomelab)
                }
            }
        }
        LocationSearchBox(
            query = home.query,
            matches = home.matches,
            onQuery = { send(LocationsEvent.SearchHomelab(it)) },
            onPick = { send(LocationsEvent.HomelabFromCatalog(it)) },
            placeholder = "Search a nearby city, or click the map"
        )
        home.pending?.let { pending ->
            Div({ classes("inline-row") }) {
                Muted("New")
                Text(pending)
                ActionButton(
                    "Save homelab location",
                    ButtonStyle.Primary,
                    small = true
                ) { send(LocationsEvent.SaveHomelab) }
            }
        }
    }
    // One map: it takes the point of the location in the editor, or else of the homelab.
    val editor = model.editor
    Panel(title = if (editor != null) "Coordinates" else "Map") {
        if (editor != null) {
            WorldMap(
                model = model.map,
                heightPx = 300,
                onPick = { lat, lon -> send(LocationsEvent.PickForEditor(lat, lon)) },
                pick = editor.pick,
                fitKey = editor.isNew,
            )
        } else {
            WorldMap(
                model = model.map,
                heightPx = 300,
                onPick = { lat, lon -> send(LocationsEvent.HomelabFromMap(lat, lon)) },
                pick = home.pick,
            )
        }
    }
}

/** Locations over the Config page: a wide dialog on desktops, a drawer on tablets, full screen on phones. */
@Composable
fun LocationsOverlay(
    model: LocationsScreenModel,
    viewport: Viewport,
    onClose: () -> Unit,
) {
    Overlay(
        label = "Locations",
        titleId = "locations-title",
        style = when (viewport) {
            Viewport.Phone -> OverlayStyle.FullScreen
            Viewport.Tablet -> OverlayStyle.Drawer
            else -> OverlayStyle.Modal
        },
        onDismiss = onClose,
    ) {
        Div({ classes("overlay-panel", "wide-panel") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text("Config") }
                    H2({ id("locations-title") }) { Text("Locations") }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Close Locations",
                    onClick = onClose,
                )
            }
            Div({ classes("wide-panel-body") }) {
                ScrollHint()
                LocationsScreen(model)
            }
        }
    }
}
