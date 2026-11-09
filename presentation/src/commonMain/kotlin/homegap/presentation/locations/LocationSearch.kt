package homegap.presentation.locations

import homegap.api.LocationDto
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Searching the locations catalog and naming map clicks, all in the browser. */
object LocationSearch {
    /** Rough region centres, matching the orchestrator's (used to guess a clicked point's region). */
    val REGION_CENTRES: Map<String, Pair<Double, Double>> = mapOf(
        "NA" to (39.8 to -98.6), "SA" to (-15.6 to -56.1), "EU" to (50.1 to 8.7), "AF" to (1.7 to 17.3),
        "ME" to (25.3 to 51.5), "AS" to (22.0 to 79.0), "OC" to (-25.3 to 133.8),
    )

    val REGION_NAMES: Map<String, String> = mapOf(
        "NA" to "North America", "SA" to "South America", "EU" to "Europe", "AF" to "Africa",
        "ME" to "Middle East", "AS" to "Asia", "OC" to "Oceania",
    )

    /** "Dallas, Texas · United States" style label for lists. */
    fun label(l: LocationDto): String = if (l.country.isBlank() || l.name.contains(l.country)) l.name else "${l.name}, ${l.country}"

    /**
     * Entries matching every word of [query] in their name, country, country code, providers or id;
     * names that start with the query come first.
     */
    fun search(all: List<LocationDto>, query: String, limit: Int = 8): List<LocationDto> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun haystack(l: LocationDto) = (listOf(l.name, l.country, l.countryCode, l.id, l.region, REGION_NAMES[l.region].orEmpty()) + l.providers).joinToString(" ").lowercase()
        return all.filter { l -> haystack(l).let { h -> words.all { it in h } } }
            .sortedWith(compareBy({ !it.name.lowercase().startsWith(words.first()) }, { it.name }))
            .take(limit)
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = kotlin.math.PI / 180
        val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(a))
    }

    /** The catalog entry nearest [lat], [lon] within [withinKm], if any. */
    fun nearest(all: List<LocationDto>, lat: Double, lon: Double, withinKm: Double = 50.0): LocationDto? =
        all.map { it to distanceKm(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= withinKm }
            .minByOrNull { it.second }?.first

    /** The region whose centre is closest to the point. */
    fun regionFor(lat: Double, lon: Double): String =
        REGION_CENTRES.minBy { (_, c) -> distanceKm(lat, lon, c.first, c.second) }.key

    /** Two decimals, as the catalog stores coordinates. */
    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
