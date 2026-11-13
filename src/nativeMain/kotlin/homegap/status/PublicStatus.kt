package homegap.status

import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.config.AppConfig
import homegap.config.REGION_CENTROIDS
import homegap.locations.LocationCatalog
import homegap.platform.nowMillis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/** One node on the public status page: no addresses, only what a visitor needs. */
@Serializable
data class PublicNode(
    val id: String,
    val region: String,
    val state: String,
    val current: Boolean,
    /** The URL where the browser of the visitor measures its round trip through this node. Null while offline. */
    @SerialName("pingUrl") val pingUrl: String?,
    /** The node's place name (`Dallas, Texas`), when configured. */
    val place: String? = null,
    /** The city of the node, for the map. Null when the node has no location. */
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
 * The data of the public status page, from the view that the controller has of the nodes. A short
 * cache holds the view, because the page is public and the snapshot of the controller reads files.
 */
class PublicStatus(
    private val config: () -> AppConfig,
    private val nodes: () -> List<NodeStatusDto>,
    private val cacheMillis: Long = 5_000,
) {
    @Volatile
    private var cached: Pair<Long, List<NodeStatusDto>>? = null

    /** The node whose own status name [host] is, or null. */
    fun nodeFor(host: String): String? {
        val cfg = config()
        if (!cfg.statusPage.enabled) return null
        return cfg.nodes
            .firstOrNull { cfg.statusPage.nodeHost(cfg.domain, it.id).equals(host, ignoreCase = true) }
            ?.id
    }

    /** Whether [host] is one of the status page's names. */
    fun serves(host: String): Boolean {
        val cfg = config()
        if (!cfg.statusPage.enabled) return false
        return cfg.statusPage.autoHost(cfg.domain).equals(host, ignoreCase = true) || nodeFor(host) != null
    }

    fun build(host: String): PublicStatusDto {
        val cfg = config()
        val now = nowMillis()
        val statuses = cached?.takeIf { now - it.first < cacheMillis }?.second
            ?: nodes().also { cached = now to it }
        val via = nodeFor(host)
        val statusById = statuses.associateBy { it.id }
        val publicNodes = cfg.nodes.map { node ->
            val state = stateOf(statusById[node.id])
            val location = node.effectiveLocation()
            val pingUrl = "https://${cfg.statusPage.nodeHost(cfg.domain, node.id)}/status/ping"
            PublicNode(
                id = node.id,
                region = node.region.uppercase(),
                state = state,
                current = node.id == via,
                pingUrl = pingUrl.takeIf { state != OFFLINE },
                place = node.location?.name,
                lat = location?.latitude,
                lon = location?.longitude,
            )
        }.sortedWith(compareBy({ !it.current }, { it.region }, { it.id }))
        return PublicStatusDto(
            title = cfg.statusPage.title,
            via = via,
            nodes = publicNodes,
            at = now,
            home = homeRegion(cfg),
        )
    }

    companion object {
        /** The homelab's region, from its location: the nearest region centre. */
        fun homeRegion(cfg: AppConfig): PublicHome? {
            val home = cfg.homelab.location ?: return null
            val (code, centre) = REGION_CENTROIDS.minBy { (_, candidate) ->
                LocationCatalog.distanceKm(home.latitude, home.longitude, candidate.latitude, candidate.longitude)
            }
            return PublicHome(code, "Home · ${REGION_NAMES[code] ?: code}", centre.latitude, centre.longitude)
        }

        private val REGION_NAMES = mapOf(
            "NA" to "North America",
            "SA" to "South America",
            "EU" to "Europe",
            "AF" to "Africa",
            "ME" to "Middle East",
            "AS" to "Asia",
            "OC" to "Oceania",
        )

        const val ONLINE = "online"
        const val DEGRADED = "degraded"
        const val OFFLINE = "offline"

        /**
         * Offline: the node is down or paused, its removal is pending, or its tunnel to the homelab
         * is down. Degraded: the node carries traffic but a check fails.
         */
        fun stateOf(status: NodeStatusDto?): String = when {
            status == null || status.pendingDecommission || status.paused || !status.tunnelUp -> OFFLINE
            status.health == Health.DOWN || status.health == Health.DEGRADED -> DEGRADED
            else -> ONLINE
        }
    }
}
