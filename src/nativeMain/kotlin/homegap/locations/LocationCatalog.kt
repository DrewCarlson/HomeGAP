package homegap.locations

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.LocationDto
import homegap.config.AppConfig
import homegap.platform.Files
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One catalog entry as stored (the shipped file, or local additions and edits). */
@Serializable
data class LocationEntry(
    val id: String,
    val name: String = "",
    val country: String = "",
    @SerialName("country_code") val countryCode: String = "",
    val region: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val providers: List<String> = emptyList(),
    /** A local entry that hides a shipped one. */
    val hidden: Boolean = false,
)

/**
 * Known locations: the catalog shipped in the binary (`locations/datacenters.yml`) merged with
 * local additions and edits in `<data_dir>/locations.yml`, where an entry with a shipped id
 * replaces it (or hides it, with `hidden: true`).
 */
@Inject
@SingleIn(AppScope::class)
class LocationCatalog(config: AppConfig) {
    private val path = "${config.dataDir}/locations.yml"
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = false))
    private val builtIn: List<LocationEntry> by lazy {
        yaml.decodeFromString<List<LocationEntry>>(BuiltInLocations.yaml)
    }

    private fun local(): List<LocationEntry> =
        Files.readTextOrNull(path)
            ?.takeIf { it.isNotBlank() }
            ?.let { yaml.decodeFromString<List<LocationEntry>>(it) }
            .orEmpty()

    fun all(): List<LocationDto> = merge(builtIn, local())

    /** Adds or replaces [dto] (by id). Editing a shipped entry keeps a local copy of it. */
    fun put(dto: LocationDto): LocationDto {
        val entry = validate(dto)
        val shipped = builtIn.firstOrNull { it.id == entry.id }
        val rest = local().filter { it.id != entry.id }
        save(if (shipped == entry) rest else rest + entry)
        return all().first { it.id == entry.id }
    }

    /**
     * Removes [id]: a local entry is deleted; a shipped one is hidden. With [reset], an edited
     * shipped entry goes back to the shipped version instead.
     */
    fun delete(id: String, reset: Boolean = false) {
        val shipped = builtIn.any { it.id == id }
        val rest = local().filter { it.id != id }
        require(shipped || local().any { it.id == id }) { "unknown location $id" }
        save(if (shipped && !reset) rest + LocationEntry(id, hidden = true) else rest)
    }

    private fun save(entries: List<LocationEntry>) {
        val header = "# Locations added or edited in the HomeGAP dashboard. Entries with a shipped id replace it;\n" +
                "# `hidden: true` hides one.\n"
        Files.writeAtomic(path, header + yaml.encodeToString(entries.sortedBy { it.id }))
    }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        val REGIONS = setOf("NA", "SA", "EU", "AF", "ME", "AS", "OC")

        fun merge(builtIn: List<LocationEntry>, local: List<LocationEntry>): List<LocationDto> {
            val overrides = local.associateBy { it.id }
            val shipped = builtIn.mapNotNull { b ->
                val o = overrides[b.id]
                when {
                    o == null -> b.toDto(builtIn = true, edited = false)
                    o.hidden -> null
                    else -> o.toDto(builtIn = true, edited = true)
                }
            }
            val added = local.filter { l -> !l.hidden && builtIn.none { it.id == l.id } }
                .map { it.toDto(builtIn = false, edited = false) }
            return (shipped + added).sortedWith(compareBy({ it.region }, { it.country }, { it.name }))
        }

        fun validate(d: LocationDto): LocationEntry {
            require(ID.matches(d.id)) { "id must be lowercase letters, digits and dashes" }
            require(d.name.isNotBlank()) { "name is required" }
            require(d.latitude in -90.0..90.0 && d.longitude in -180.0..180.0) { "coordinates are out of range" }
            require(d.region.isBlank() || d.region.uppercase() in REGIONS) { "region must be one of ${REGIONS.joinToString()}" }
            return LocationEntry(
                d.id, d.name.trim(), d.country.trim(), d.countryCode.trim().uppercase(), d.region.trim().uppercase(),
                d.latitude, d.longitude, d.providers.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            )
        }

        private fun LocationEntry.toDto(builtIn: Boolean, edited: Boolean) =
            LocationDto(id, name, country, countryCode, region, latitude, longitude, providers, builtIn, edited)

        /** Great-circle distance in km. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = kotlin.math.PI / 180
            val a =
                sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
            return 2 * 6371.0 * asin(sqrt(a))
        }
    }
}
