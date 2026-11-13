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
import kotlin.math.PI
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

    /** Adds or replaces [dto] (by id). An edit of a shipped entry keeps a local copy of it. */
    fun put(dto: LocationDto): LocationDto {
        val entry = validate(dto)
        val shipped = builtIn.firstOrNull { it.id == entry.id }
        val rest = local().filter { it.id != entry.id }
        save(if (shipped == entry) rest else rest + entry)
        return all().first { it.id == entry.id }
    }

    /**
     * Removes [id]. A local entry goes away, and a shipped entry becomes hidden. With [reset], an
     * edited shipped entry goes back to the shipped version.
     */
    fun delete(id: String, reset: Boolean = false) {
        val shipped = builtIn.any { it.id == id }
        val rest = local().filter { it.id != id }
        require(shipped || local().any { it.id == id }) { "unknown location $id" }
        save(if (shipped && !reset) rest + LocationEntry(id, hidden = true) else rest)
    }

    private fun save(entries: List<LocationEntry>) {
        Files.writeAtomic(path, HEADER + yaml.encodeToString(entries.sortedBy { it.id }))
    }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
        val REGIONS = setOf("NA", "SA", "EU", "AF", "ME", "AS", "OC")
        private const val EARTH_RADIUS_KM = 6371.0

        /** The first lines of the local file. */
        private const val HEADER =
            "# Locations added or edited in the HomeGAP dashboard. Entries with a shipped id replace it;\n" +
                "# `hidden: true` hides one.\n"

        fun merge(builtIn: List<LocationEntry>, local: List<LocationEntry>): List<LocationDto> {
            val overrides = local.associateBy { it.id }
            val shipped = builtIn.mapNotNull { entry ->
                val override = overrides[entry.id]
                when {
                    override == null -> entry.toDto(builtIn = true, edited = false)
                    override.hidden -> null
                    else -> override.toDto(builtIn = true, edited = true)
                }
            }
            val added = local
                .filter { entry -> !entry.hidden && builtIn.none { it.id == entry.id } }
                .map { it.toDto(builtIn = false, edited = false) }
            return (shipped + added).sortedWith(compareBy({ it.region }, { it.country }, { it.name }))
        }

        fun validate(dto: LocationDto): LocationEntry {
            require(ID.matches(dto.id)) { "id must be lowercase letters, digits and dashes" }
            require(dto.name.isNotBlank()) { "name is required" }
            require(dto.latitude in -90.0..90.0 && dto.longitude in -180.0..180.0) {
                "coordinates are out of range"
            }
            require(dto.region.isBlank() || dto.region.uppercase() in REGIONS) {
                "region must be one of ${REGIONS.joinToString()}"
            }
            return LocationEntry(
                id = dto.id,
                name = dto.name.trim(),
                country = dto.country.trim(),
                countryCode = dto.countryCode.trim().uppercase(),
                region = dto.region.trim().uppercase(),
                latitude = dto.latitude,
                longitude = dto.longitude,
                providers = dto.providers.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            )
        }

        private fun LocationEntry.toDto(builtIn: Boolean, edited: Boolean) = LocationDto(
            id = id,
            name = name,
            country = country,
            countryCode = countryCode,
            region = region,
            latitude = latitude,
            longitude = longitude,
            providers = providers,
            builtIn = builtIn,
            edited = edited,
        )

        /** The great-circle distance in km. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val toRadians = PI / 180
            val latitudeTerm = sin((lat2 - lat1) * toRadians / 2).let { it * it }
            val longitudeTerm = sin((lon2 - lon1) * toRadians / 2).let { it * it }
            val haversine = latitudeTerm + cos(lat1 * toRadians) * cos(lat2 * toRadians) * longitudeTerm
            return 2 * EARTH_RADIUS_KM * asin(sqrt(haversine))
        }
    }
}
