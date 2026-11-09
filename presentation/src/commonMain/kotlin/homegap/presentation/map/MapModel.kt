package homegap.presentation.map

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.api.PlaceDto
import homegap.api.StatusSnapshot
import homegap.presentation.format.ago

/** What a map shows: markers, lines between points and soft areas. */
@Immutable
data class MapModel(
    val markers: List<MapMarker> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    val areas: List<MapArea> = emptyList(),
    /** The closest the initial view zooms in to fit everything. */
    val fitMaxZoom: Double = 4.0,
) {
    val isEmpty: Boolean get() = markers.isEmpty() && areas.isEmpty()
}

/** [Place]: a known location, not something running (a small, still dot). */
enum class MarkerKind(val wire: String) { Node("node"), Homelab("homelab"), Place("place") }

@Immutable
data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val health: Health,
    val kind: MarkerKind = MarkerKind.Node,
    /** Shown next to the marker. */
    val label: String? = null,
    val highlight: Boolean = false,
    /** The hover card. */
    val info: MapInfo? = null,
)

@Immutable
data class MapInfo(val title: String, val subtitle: String? = null, val rows: List<MapInfoRow> = emptyList())

@Immutable
data class MapInfoRow(val label: String, val value: String, val health: Health? = null)

@Immutable
data class MapLine(
    val id: String,
    val from: MapPoint,
    val to: MapPoint,
    val health: Health,
    val label: String? = null,
)

@Immutable
data class MapArea(val id: String, val center: MapPoint, val radiusKm: Double, val label: String? = null, val health: Health? = null)

@Immutable
data class MapPoint(val latitude: Double, val longitude: Double)

val PlaceDto.point: MapPoint get() = MapPoint(latitude, longitude)

/** "Dallas, Texas" when named; otherwise the region code (or the node id). */
fun NodeStatusDto.placeName(): String =
    location?.name?.takeIf { it.isNotBlank() } ?: region.ifBlank { id }

/** The homelab's marker: placed where `homelab.location` says. */
fun homelabMarker(place: PlaceDto, info: MapInfo? = null): MapMarker =
    MapMarker("homelab", place.latitude, place.longitude, Health.OK, MarkerKind.Homelab, "Homelab", info = info ?: MapInfo("Homelab", place.name))

/**
 * The Overview map: every node and the homelab, with lines from the homelab to each node colored
 * by its tunnel. A node's card lists what's wrong, its tunnel and its round trip to the homelab.
 */
fun StatusSnapshot.overviewMap(now: Long): MapModel {
    val home = homelab
    val meshRtt = mesh?.members.orEmpty().associate { m ->
        m.id to m.peers.firstOrNull { it.id == "homelab" }?.probe?.rttMs
    }
    val markers = nodes.mapNotNull { n ->
        val loc = n.location ?: return@mapNotNull null
        val failing = n.checks.filter { it.health != Health.OK && it.health != Health.UNKNOWN }
        val rows = buildList {
            add(MapInfoRow("Health", n.health.name.lowercase(), n.health))
            add(MapInfoRow("Tunnel", if (n.tunnelUp) "up" else "down", if (n.tunnelUp) Health.OK else Health.DOWN))
            meshRtt[n.id]?.let { add(MapInfoRow("To homelab", homegap.presentation.format.formatMillis(it))) }
            if (n.provider.isNotBlank()) add(MapInfoRow("Provider", n.provider))
            failing.take(4).forEach { add(MapInfoRow(it.name, it.detail.ifBlank { it.health.name.lowercase() }.take(48), it.health)) }
            n.lastProvisionAt?.let { add(MapInfoRow("Provisioned", ago(it, now))) }
        }
        val subtitle = listOfNotNull(n.location?.name?.takeIf { it.isNotBlank() }, n.region.takeIf { it.isNotBlank() }).joinToString(" · ")
            .ifBlank { null }?.let { if (loc.estimated) "$it (approximate)" else it }
        MapMarker(n.id, loc.latitude, loc.longitude, n.health, MarkerKind.Node, n.id, info = MapInfo(n.id, subtitle, rows))
    }
    val lines = if (home == null) emptyList() else nodes.mapNotNull { n ->
        val loc = n.location ?: return@mapNotNull null
        MapLine("home-${n.id}", home.point, loc.point, if (n.tunnelUp) Health.OK else Health.DOWN)
    }
    val homeMarker = home?.let {
        val up = nodes.count { it.tunnelUp }
        homelabMarker(it, MapInfo("Homelab", it.name, listOf(MapInfoRow("Tunnels up", "$up of ${nodes.size}", if (up == nodes.size) Health.OK else Health.DEGRADED))))
    }
    return MapModel(listOfNotNull(homeMarker) + markers, lines)
}
