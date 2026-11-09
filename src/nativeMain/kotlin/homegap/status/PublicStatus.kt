package homegap.status

import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.config.AppConfig
import homegap.platform.nowMillis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One node on the public status page: no addresses, only what a visitor needs. */
@Serializable
data class PublicNode(
    val id: String,
    val region: String,
    val state: String,
    val current: Boolean,
    /** Where the visitor's browser measures its round trip through this node; null while offline. */
    @SerialName("pingUrl") val pingUrl: String?,
    /** The node's place name (`Dallas, Texas`), when configured. */
    val place: String? = null,
    /** The node's city, for the map; null when it has no location. */
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * The homelab on the public map: only its region (that region's centre and name), never where
 * it actually is.
 */
@Serializable
data class PublicHome(val region: String, val label: String, val lat: Double, val lon: Double)

@Serializable
data class PublicStatusDto(
    val title: String,
    /** The node this page came through, or null when the visitor used an address that doesn't say. */
    val via: String?,
    val nodes: List<PublicNode>,
    val at: Long,
    val home: PublicHome? = null,
)

/**
 * The public status page's data, built from the controller's view of the nodes. Cached briefly:
 * the page is public, and the controller's snapshot reads files.
 */
class PublicStatus(
    private val config: () -> AppConfig,
    private val nodes: () -> List<NodeStatusDto>,
    private val cacheMillis: Long = 5_000,
) {
    private var cached: Pair<Long, List<NodeStatusDto>>? = null

    /** The node whose own status name [host] is, or null. */
    fun nodeFor(host: String): String? {
        val cfg = config()
        if (!cfg.statusPage.enabled) return null
        return cfg.nodes.firstOrNull { cfg.statusPage.nodeHost(cfg.domain, it.id).equals(host, ignoreCase = true) }?.id
    }

    /** Whether [host] is one of the status page's names. */
    fun serves(host: String): Boolean {
        val cfg = config()
        return cfg.statusPage.enabled && (cfg.statusPage.autoHost(cfg.domain).equals(host, ignoreCase = true) || nodeFor(host) != null)
    }

    fun build(host: String): PublicStatusDto {
        val cfg = config()
        val now = nowMillis()
        val all = cached?.takeIf { now - it.first < cacheMillis }?.second ?: nodes().also { cached = now to it }
        val via = nodeFor(host)
        val byId = all.associateBy { it.id }
        val list = cfg.nodes.map { n ->
            val s = byId[n.id]
            val state = stateOf(s)
            val loc = n.effectiveLocation()
            PublicNode(
                id = n.id,
                region = n.region.uppercase(),
                state = state,
                current = n.id == via,
                pingUrl = if (state == OFFLINE) null else "https://${cfg.statusPage.nodeHost(cfg.domain, n.id)}/status/ping",
                place = n.location?.name,
                lat = loc?.latitude,
                lon = loc?.longitude,
            )
        }.sortedWith(compareBy({ !it.current }, { it.region }, { it.id }))
        return PublicStatusDto(cfg.statusPage.title, via, list, now, homeRegion(cfg))
    }

    companion object {
        /** The homelab's region, from its location: the nearest region centre. */
        fun homeRegion(cfg: AppConfig): PublicHome? {
            val loc = cfg.homelab.location ?: return null
            val (code, centre) = homegap.config.REGION_CENTROIDS.minBy { (_, c) ->
                homegap.locations.LocationCatalog.distanceKm(loc.latitude, loc.longitude, c.latitude, c.longitude)
            }
            return PublicHome(code, "Home · ${REGION_NAMES[code] ?: code}", centre.latitude, centre.longitude)
        }

        private val REGION_NAMES = mapOf(
            "NA" to "North America", "SA" to "South America", "EU" to "Europe", "AF" to "Africa",
            "ME" to "Middle East", "AS" to "Asia", "OC" to "Oceania",
        )

        const val ONLINE = "online"
        const val DEGRADED = "degraded"
        const val OFFLINE = "offline"

        /**
         * Offline: the node is down, paused, being removed or its tunnel to the homelab is down.
         * Degraded: it carries traffic but a check is failing.
         */
        fun stateOf(s: NodeStatusDto?): String = when {
            s == null || s.pendingDecommission || s.paused || !s.tunnelUp -> OFFLINE
            s.health == Health.DOWN || s.health == Health.DEGRADED -> DEGRADED
            else -> ONLINE
        }
    }
}
