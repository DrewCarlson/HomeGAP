package homegap.presentation.locations

import androidx.compose.runtime.*
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.Health
import homegap.api.LocationDto
import homegap.api.LocationsResponse
import homegap.api.PlaceDto
import homegap.presentation.addnode.LocationOptionModel
import homegap.presentation.core.Presenter
import homegap.presentation.core.ScreenModel
import homegap.presentation.core.runCatchingCancellable
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.map.MapInfo
import homegap.presentation.map.MapMarker
import homegap.presentation.map.MapModel
import homegap.presentation.map.MarkerKind
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

data object LocationsScreenProps

/** The fields of the location being added or edited. */
enum class LocationField(val label: String, val placeholder: String = "") {
    Id("Id", "de-frankfurt"),
    Name("Name", "Frankfurt"),
    Country("Country", "Germany"),
    CountryCode("Country code", "DE"),
    Region("Region code", "EU"),
    Latitude("Latitude", "50.11"),
    Longitude("Longitude", "8.68"),
    Providers("Providers", "Hetzner, Vultr"),
}

@Immutable
data class LocationRowModel(
    val id: String,
    val name: String,
    val country: String,
    val region: String,
    val coordinates: String,
    val providers: String,
    /** `shipped`, `edited` or `added`. */
    val source: String,
)

@Immutable
data class LocationEditorModel(
    val isNew: Boolean,
    val values: Map<LocationField, String>,
    val error: String?,
    val pick: Pair<Double, Double>?,
) {
    operator fun get(field: LocationField): String = values[field].orEmpty()
}

@Immutable
data class HomelabLocationModel(
    /** Where it is now, or null when not set. */
    val current: String?,
    val query: String,
    val matches: List<LocationOptionModel>,
    /** A point picked on the map, waiting to be saved: its label and coordinates. */
    val pending: String?,
    val pick: Pair<Double, Double>?,
    val canEdit: Boolean,
)

sealed interface LocationsEvent {
    data class Search(val query: String) : LocationsEvent
    data object Add : LocationsEvent
    data class Edit(val id: String) : LocationsEvent
    data class Change(val field: LocationField, val value: String) : LocationsEvent
    data class PickForEditor(val latitude: Double, val longitude: Double) : LocationsEvent
    data object Save : LocationsEvent
    data object Cancel : LocationsEvent
    data class Delete(val id: String) : LocationsEvent
    data class Reset(val id: String) : LocationsEvent
    data class SearchHomelab(val query: String) : LocationsEvent
    data class HomelabFromCatalog(val id: String) : LocationsEvent
    data class HomelabFromMap(val latitude: Double, val longitude: Double) : LocationsEvent
    data object SaveHomelab : LocationsEvent
    data object ClearHomelab : LocationsEvent
}

@Immutable
data class LocationsScreenModel(
    val loading: Boolean,
    val query: String,
    val rows: List<LocationRowModel>,
    val total: Int,
    val editor: LocationEditorModel?,
    val homelab: HomelabLocationModel,
    /** Every known location, for the overview map on this page. */
    val map: MapModel,
    val onEvent: (LocationsEvent) -> Unit,
) : ScreenModel

@SingleIn(AppScope::class)
@Inject
class LocationsScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
) : Presenter<LocationsScreenProps, LocationsScreenModel> {
    @Composable
    override fun model(props: LocationsScreenProps): LocationsScreenModel {
        var reload by remember { mutableIntStateOf(0) }
        val data by produceState<LocationsResponse?>(null, reload) {
            // Keeps trying while the orchestrator is unreachable (e.g. restarting).
            while (true) {
                runCatchingCancellable { api.locations() }
                    .onSuccess {
                        value = it
                        return@produceState
                    }
                delay(3.seconds)
            }
        }
        var query by remember { mutableStateOf("") }
        var editor by remember { mutableStateOf<LocationEditorModel?>(null) }
        var homeQuery by remember { mutableStateOf("") }
        var homePending by remember { mutableStateOf<PlaceDto?>(null) }
        val all = data?.locations.orEmpty()
        val latestAll by rememberUpdatedState(all)

        fun refresh() {
            reload++
        }

        val onEvent: (LocationsEvent) -> Unit = remember {
            { event ->
                when (event) {
                    is LocationsEvent.Search -> query = event.query
                    LocationsEvent.Add -> editor = LocationEditorModel(true, emptyMap(), null, null)
                    is LocationsEvent.Edit -> latestAll.firstOrNull { it.id == event.id }
                        ?.let { editor = it.toEditor() }

                    is LocationsEvent.Change -> editor = editor?.let { e ->
                        val values = e.values + (event.field to event.value)
                        e.copy(values = values, pick = pickOf(values), error = null)
                    }

                    is LocationsEvent.PickForEditor -> editor = editor?.let { e ->
                        val lat = LocationSearch.round2(event.latitude)
                        val lon = LocationSearch.round2(event.longitude)
                        val values = e.values + mapOf(
                            LocationField.Latitude to lat.toString(),
                            LocationField.Longitude to lon.toString()
                        ) +
                                (if (e[LocationField.Region].isBlank()) mapOf(
                                    LocationField.Region to LocationSearch.regionFor(
                                        lat,
                                        lon
                                    )
                                ) else emptyMap())
                        e.copy(values = values, pick = lat to lon, error = null)
                    }

                    LocationsEvent.Save -> editor?.let { e ->
                        val dto = e.toDto()
                        if (dto == null) {
                            editor = e.copy(error = "An id, a name and valid coordinates are required.")
                        } else {
                            actions.run(
                                label = "Save location",
                                onSuccess = {
                                    editor = null
                                    refresh()
                                }
                            ) {
                                api.saveLocation(
                                    dto
                                )
                            }
                        }
                    }

                    LocationsEvent.Cancel -> editor = null
                    is LocationsEvent.Delete -> {
                        val name = latestAll.firstOrNull { it.id == event.id }?.name ?: event.id
                        confirmations.ask(
                            ConfirmModel(
                                title = "Remove $name?",
                                text = "It leaves the known locations. Nodes that use it keep their coordinates.",
                                confirmLabel = "Remove",
                                danger = true,
                            )
                        ) {
                            actions.run(
                                label = "Remove location",
                                onSuccess = { refresh() },
                            ) {
                                api.deleteLocation(event.id, reset = false)
                            }
                        }
                    }

                    is LocationsEvent.Reset -> actions.run(
                        label = "Restore location",
                        onSuccess = { refresh() },
                    ) {
                        api.deleteLocation(event.id, reset = true)
                    }

                    is LocationsEvent.SearchHomelab -> homeQuery = event.query
                    is LocationsEvent.HomelabFromCatalog -> latestAll.firstOrNull { it.id == event.id }?.let { l ->
                        homeQuery = ""
                        homePending = PlaceDto(l.latitude, l.longitude, l.name)
                    }

                    is LocationsEvent.HomelabFromMap -> {
                        val lat = LocationSearch.round2(event.latitude)
                        val lon = LocationSearch.round2(event.longitude)
                        val near = LocationSearch.nearest(latestAll, lat, lon)
                        homePending = PlaceDto(lat, lon, near?.name)
                    }

                    LocationsEvent.SaveHomelab -> homePending?.let { p ->
                        actions.run(
                            label = "Save homelab location",
                            onSuccess = {
                                homePending = null
                                refresh()
                            }
                        ) { api.setHomelabLocation(p) }
                    }

                    LocationsEvent.ClearHomelab -> {
                        actions.run(
                            "Clear homelab location",
                            onSuccess = {
                                homePending = null
                                refresh()
                            }
                        ) {
                            api.setHomelabLocation(null)
                        }
                    }
                }
            }
        }

        val shown = if (query.isBlank()) all else LocationSearch.search(all, query, limit = all.size)
        val home = data?.homelab
        val homePick = homePending ?: home
        return LocationsScreenModel(
            loading = data == null,
            query = query,
            rows = shown.map { it.toRow() },
            total = all.size,
            editor = editor,
            homelab = HomelabLocationModel(
                current = home?.let { describe(it) },
                query = homeQuery,
                matches = LocationSearch.search(all, homeQuery).map {
                    LocationOptionModel(
                        it.id,
                        LocationSearch.label(it),
                        it.providers.take(4).joinToString(", ")
                    )
                },
                pending = homePending?.let { describe(it) },
                pick = homePick?.let { it.latitude to it.longitude },
                canEdit = true,
            ),
            map = remember(all) {
                MapModel(all.map { l ->
                    MapMarker(
                        l.id,
                        l.latitude,
                        l.longitude,
                        Health.UNKNOWN,
                        MarkerKind.Place,
                        info = MapInfo(LocationSearch.label(l), l.providers.joinToString(", ").ifBlank { null })
                    )
                }, fitMaxZoom = 1.5)
            },
            onEvent = onEvent,
        )
    }

    private fun describe(p: PlaceDto): String =
        (p.name?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()) + "${p.latitude}, ${p.longitude}"

    private fun pickOf(values: Map<LocationField, String>): Pair<Double, Double>? {
        val lat = values[LocationField.Latitude]?.trim()?.toDoubleOrNull() ?: return null
        val lon = values[LocationField.Longitude]?.trim()?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun LocationDto.toEditor() = LocationEditorModel(
        isNew = false,
        values = mapOf(
            LocationField.Id to id, LocationField.Name to name, LocationField.Country to country,
            LocationField.CountryCode to countryCode, LocationField.Region to region,
            LocationField.Latitude to latitude.toString(), LocationField.Longitude to longitude.toString(),
            LocationField.Providers to providers.joinToString(", "),
        ),
        error = null,
        pick = latitude to longitude,
    )

    private fun LocationEditorModel.toDto(): LocationDto? {
        val id = this[LocationField.Id].trim().lowercase()
        val name = this[LocationField.Name].trim()
        val lat = this[LocationField.Latitude].trim().toDoubleOrNull()
        val lon = this[LocationField.Longitude].trim().toDoubleOrNull()
        if (id.isBlank() || name.isBlank() || lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LocationDto(
            id = id,
            name = name,
            country = this[LocationField.Country].trim(),
            countryCode = this[LocationField.CountryCode].trim().uppercase(),
            region = this[LocationField.Region].trim().uppercase(),
            latitude = lat,
            longitude = lon,
            providers = this[LocationField.Providers].split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    private fun LocationDto.toRow() = LocationRowModel(
        id = id, name = name, country = country, region = region,
        coordinates = "$latitude, $longitude",
        providers = providers.joinToString(", "),
        source = when {
            !builtIn -> "added"
            edited -> "edited"
            else -> "shipped"
        },
    )
}
