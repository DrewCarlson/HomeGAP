package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.api.Health
import homegap.presentation.common.Badge
import homegap.presentation.locations.LocationField
import homegap.presentation.locations.LocationsEvent
import homegap.presentation.locations.LocationsScreenModel
import homegap.web.ui.*
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*

private val KNOWN_LOCATIONS_HEADERS = listOf("Name", "Country", "Region", "Coordinates", "Providers", "Source", "")

@Composable
fun LocationsScreen(model: LocationsScreenModel) {
    val send = model.onEvent
    val home = model.homelab
    Panel(
        title = "Homelab",
        subtitle = "Where the homelab is: drawn on the maps with its tunnels. The public status page only shows its region.",
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
            placeholder = "Search a nearby city, or click the map below"
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
    Panel(
        title = "Place the homelab",
        subtitle = "Click the map to set a point; nearby known cities name it"
    ) {
        WorldMap(
            homegap.presentation.map.MapModel(),
            heightPx = 280,
            onPick = { lat, lon ->
                send(LocationsEvent.HomelabFromMap(lat, lon))
            },
            pick = home.pick
        )
    }

    model.editor?.let { e ->
        Panel(
            title = if (e.isNew) "Add a location" else "Edit ${e[LocationField.Name]}",
            subtitle = "Click the map to set the coordinates",
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
        Panel("Coordinates") {
            WorldMap(
                homegap.presentation.map.MapModel(),
                heightPx = 280,
                onPick = { lat, lon ->
                    send(LocationsEvent.PickForEditor(lat, lon))
                },
                pick = e.pick,
                fitKey = e.isNew
            )
        }
    }

    Panel(
        "Known locations",
        "${model.total} data-center cities, offered when adding a node. Shipped ones can be edited or hidden.",
        actions = {
            ActionButton(
                text = "Add location",
                style = ButtonStyle.Primary,
                small = true
            ) {
                send(LocationsEvent.Add)
            }
        }) {
        Div({ classes("panel-body") }) {
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
            isEmpty = model.rows.isEmpty()
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
                        Cell("", "actions") {
                            ActionButton("Edit", small = true) {
                                send(LocationsEvent.Edit(r.id))
                            }
                            if (r.source == "edited") {
                                ActionButton("Restore", small = true) {
                                    send(LocationsEvent.Reset(r.id))
                                }
                            }
                            ActionButton("Remove", ButtonStyle.Danger, small = true) {
                                if (window.confirm("Remove ${r.name} from the known locations?")) {
                                    send(LocationsEvent.Delete(r.id))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Panel("All known locations") { WorldMap(model.map, heightPx = 320) }
}
