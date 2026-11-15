package homegap.clients

import homegap.state.StateStore

/**
 * The id of each Bunny zone that HomeGAP writes. The state keeps each id, so only the first use of
 * a zone looks the zone up by name. The state also keeps the name of the main zone with its id.
 * After a change of `bunny.zone` in the config, the id of the old zone does not count, and HomeGAP
 * looks the new zone up.
 */
class BunnyZones(private val state: StateStore, private val mainZone: String) {
    /** Returns the id of [zone]. Throws if the Bunny account has no such zone. */
    suspend fun id(bunny: BunnyClient, zone: String): Long {
        known(zone)?.let { return it }
        val id = bunny.findZone(zone)?.id ?: error("Bunny zone $zone not found (create it in Bunny DNS first)")
        remember(zone, id)
        return id
    }

    /** Drops the id of [zone]. The next use looks the zone up again, for a zone that someone made again. */
    suspend fun forget(zone: String) = remember(zone, null)

    private fun known(zone: String): Long? {
        val snapshot = state.snapshot()
        if (zone != mainZone) return snapshot.bunnyZoneIds[zone]
        return snapshot.bunnyZoneId.takeIf { snapshot.bunnyZoneName.equals(zone, ignoreCase = true) }
    }

    private suspend fun remember(zone: String, id: Long?) {
        state.update {
            when {
                zone == mainZone -> it.copy(bunnyZoneId = id, bunnyZoneName = zone.takeIf { id != null })
                id == null -> it.copy(bunnyZoneIds = it.bunnyZoneIds - zone)
                else -> it.copy(bunnyZoneIds = it.bunnyZoneIds + (zone to id))
            }
        }
    }
}
