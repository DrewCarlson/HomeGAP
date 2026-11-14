package homegap.reconcile

import homegap.acme.DnsChallenge
import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.clients.BunnyZones
import homegap.model.DesiredDnsRecord
import homegap.platform.runCatchingCancellable
import kotlin.math.abs

/**
 * DNS domain: Bunny geo-steered record sets (one record per node, each with its own monitor)
 * for the root, wildcard and dedicated hostnames, plus a plain record per node management host.
 *
 * Bunny drops offline members of a record set and returns every member when all are offline
 * (fail-open), which is exactly the failover behaviour the platform relies on.
 */
class DnsReconciler(
    private val ctx: ReconcileContext,
    private val bunny: BunnyClient?,
    /** Asks the nameservers of the zone if they serve what the API reports. With null, there is no check. */
    private val nameservers: NameserverCheck? = null,
) {
    data class Observed(
        val record: BunnyRecord,
        val desired: DesiredDnsRecord?,
        val inSync: Boolean,
        val diff: String?,
        /** The zone of the record. */
        val zone: String = "",
    ) {
        /** The full name of the record. Bunny stores the name without the zone. */
        val fqdn: String get() = desired?.fqdn ?: if (record.name.isEmpty()) zone else "${record.name}.$zone"
    }

    var lastObserved: List<Observed> = emptyList()
        private set

    /** The CAA records at each service domain whose zone the last pass read, as `flags tag "value"`. */
    var lastCaa: Map<String, List<String>> = emptyMap()
        private set

    /** The error of each zone that the last pass did not sync. */
    var lastZoneErrors: Map<String, String> = emptyMap()
        private set

    private val zones = BunnyZones(ctx.state, ctx.config.bunny.zone)

    /** The zone in one pass: its records, the records that the pass matched, and the pauses. */
    private class ZonePass(
        val bunny: BunnyClient,
        val zoneId: Long,
        val actual: List<BunnyRecord>,
        val globalPause: Boolean,
        val pausedNodes: Set<String>,
        val pausedServiceKeys: Set<String>,
    ) {
        val claimed = mutableSetOf<Long>()
        val observed = mutableListOf<Observed>()

        fun isPaused(desired: DesiredDnsRecord): Boolean =
            globalPause || desired.nodeId in pausedNodes || pausedServiceKeys.any { desired.purpose == "service:$it" }

        /** A pause keeps a record that nothing wants: a global pause, or a pause of its node or service. */
        fun keepsUndesired(record: BunnyRecord): Boolean {
            if (globalPause) return true
            if (!record.isManaged()) return false
            val node = record.comment?.substringAfter("node=", "")
            val nodePaused = node != null && node in pausedNodes
            return nodePaused || pausedServiceKeys.any { record.comment?.contains("service:$it ") == true }
        }
    }

    suspend fun reconcile(desired: List<DesiredDnsRecord>, pausedServiceKeys: Set<String>): List<Observed> {
        val bunny = this.bunny
        if (bunny == null) {
            val apiKey = ctx.config.bunny.apiKey.describe()
            ctx.log.error(DOMAIN, "Bunny API key $apiKey is not set; DNS reconciliation skipped")
            desired.forEach { ctx.diff.add(DOMAIN, it.fqdn, diffKey(it), action = "unknown", desired = it.value) }
            return emptyList()
        }
        // Each zone gets its own pass. A zone that fails does not stop the other zones.
        val byZone = desired.groupBy { ctx.config.zoneOf(it.fqdn) }
        val zoneNames = ctx.config.zones
        val failures = mutableMapOf<String, String>()
        val served = zoneNames.flatMap { zone ->
            runCatchingCancellable { reconcileZone(bunny, zone, byZone[zone].orEmpty(), pausedServiceKeys) }
                .onFailure { failures[zone] = it.message ?: it.toString() }
                .getOrDefault(emptyList())
                .map { it.copy(zone = zone) }
        }
        lastObserved = served
        lastZoneErrors = failures
        check(failures.isEmpty()) {
            failures.entries.joinToString("; ") { (zone, error) -> if (zoneNames.size == 1) error else "zone $zone: $error" }
        }
        return served
    }

    private suspend fun reconcileZone(
        bunny: BunnyClient,
        zoneName: String,
        desired: List<DesiredDnsRecord>,
        pausedServiceKeys: Set<String>,
    ): List<Observed> {
        val zoneId = zones.id(bunny, zoneName)
        val zone = runCatchingCancellable { bunny.getZone(zoneId) }.getOrElse {
            // The cached zone id may be stale (zone recreated): look it up again next pass.
            zones.forget(zoneName)
            throw it
        }
        for (domain in ctx.config.serviceDomains.filter { ctx.config.zoneOf(it) == zoneName }) {
            lastCaa = lastCaa + (domain to domainCaa(zone.records, domain))
        }
        val pass = ZonePass(
            bunny = bunny,
            zoneId = zoneId,
            actual = zone.records,
            globalPause = ctx.pauses().global,
            pausedNodes = ctx.pauses().nodes,
            pausedServiceKeys = pausedServiceKeys,
        )

        for (record in desired) {
            reconcileRecord(pass, record)
        }
        removeUndesired(pass, desired)

        return checkNameservers(zone.nameservers, pass.observed)
    }

    /** HomeGAP publishes its CAA records at [domain], which is the apex only if the zone is the domain. */
    private fun domainCaa(records: List<BunnyRecord>, domain: String): List<String> {
        val caaType = BunnyRecord.typeCode("CAA")
        val name = ctx.config.relativeName(domain)
        return records
            .filter { it.type == caaType && it.name.equals(name, ignoreCase = true) }
            .map { "${it.flags ?: 0} ${it.tag.orEmpty()} \"${it.value}\"" }
            .sorted()
    }

    // ------------------------------------------------------------------ desired records

    /** Creates or updates the record for [desired], and adds the result to the records of the pass. */
    private suspend fun reconcileRecord(pass: ZonePass, desired: DesiredDnsRecord) {
        val typeCode = BunnyRecord.typeCode(desired.type)
        val existing = existingRecord(pass, desired, typeCode)
        val wanted = toRecord(desired, typeCode)
        if (existing == null) {
            createRecord(pass, desired, wanted)
            return
        }
        pass.claimed += existing.id
        val difference = difference(existing, wanted)
        if (difference == null) {
            addDiff(desired, "none", wanted, existing)
            pass.observed += Observed(existing, desired, inSync = true, diff = null)
            return
        }
        addDiff(desired, "update", wanted, existing)
        if (ctx.dryRun || pass.isPaused(desired)) {
            pass.observed += Observed(existing, desired, inSync = false, diff = difference)
            return
        }
        pass.bunny.updateRecord(pass.zoneId, wanted.copy(id = existing.id))
        val updated = wanted.copy(id = existing.id, monitorStatus = existing.monitorStatus)
        pass.observed += Observed(updated, desired, inSync = true, diff = null)
        ctx.log.change(
            domain = DOMAIN,
            message = "updated ${desired.type} ${desired.fqdn}: $difference",
            target = desired.nodeId,
            fields = mapOf("record" to desired.fqdn),
        )
    }

    /**
     * Returns the record of the zone that [desired] owns: the managed record of the same node, or
     * an unmanaged record with the same value. A record that the pass matched before does not count.
     */
    private fun existingRecord(pass: ZonePass, desired: DesiredDnsRecord, typeCode: Int): BunnyRecord? {
        val sameName = pass.actual.filter {
            it.type == typeCode &&
                it.name.equals(desired.name, ignoreCase = true) &&
                it.id !in pass.claimed &&
                (desired.tag == null || it.tag == desired.tag)
        }
        return sameName.firstOrNull { it.isManaged() && it.comment?.endsWith("node=${desired.nodeId}") == true }
            ?: sameName.firstOrNull { !it.isManaged() && it.value == desired.value }
    }

    private suspend fun createRecord(pass: ZonePass, desired: DesiredDnsRecord, wanted: BunnyRecord) {
        addDiff(desired, "create", wanted)
        if (ctx.dryRun || pass.isPaused(desired)) {
            pass.observed += Observed(wanted, desired, inSync = false, diff = "missing")
            return
        }
        val created = pass.bunny.addRecord(pass.zoneId, wanted)
        pass.claimed += created.id
        pass.observed += Observed(created, desired, inSync = true, diff = null)
        ctx.log.change(
            domain = DOMAIN,
            message = "created ${desired.type} ${desired.fqdn} -> ${desired.value} (${desired.purpose})",
            target = desired.nodeId,
            fields = mapOf("record" to desired.fqdn),
        )
    }

    private fun addDiff(desired: DesiredDnsRecord, action: String, wanted: BunnyRecord, existing: BunnyRecord? = null) {
        ctx.diff.add(
            domain = DOMAIN,
            target = desired.fqdn,
            key = diffKey(desired),
            action = action,
            desired = summary(wanted),
            actual = existing?.let { summary(it) },
        )
    }

    private fun diffKey(desired: DesiredDnsRecord) = "${desired.type}@${desired.nodeId}"

    // ------------------------------------------------------------------ records that nothing wants

    /**
     * Deletes each record that the pass did not match, if HomeGAP manages it or if it collides with
     * a desired record set.
     */
    private suspend fun removeUndesired(pass: ZonePass, desired: List<DesiredDnsRecord>) {
        val desiredNames = desired.map { it.name.lowercase() to BunnyRecord.typeCode(it.type) }.toSet()
        for (record in pass.actual) {
            if (record.id in pass.claimed) continue
            // A certificate order runs outside the pass and owns its challenge records.
            if (record.comment == DnsChallenge.COMMENT) continue
            removeRecord(pass, record, desiredNames)
        }
    }

    private suspend fun removeRecord(pass: ZonePass, record: BunnyRecord, desiredNames: Set<Pair<String, Int>>) {
        val managed = record.isManaged()
        val name = record.name.lowercase()
        // A CNAME at a name that has A or AAAA records hides the full record set.
        val shadows = record.type == BunnyRecord.typeCode("CNAME") && desiredNames.any { it.first == name }
        val collides = (name to record.type) in desiredNames || shadows
        if (!managed && !collides) return
        if (pass.keepsUndesired(record)) {
            pass.observed += Observed(record, desired = null, inSync = false, diff = "kept: paused")
            return
        }
        val reason = if (managed) "no longer desired" else "collides with a managed record set"
        val shownName = record.name.ifEmpty { "@" }
        ctx.diff.add(
            domain = DOMAIN,
            target = shownName,
            key = "${record.typeName}#${record.id}",
            action = "delete",
            desired = null,
            actual = "${record.value} ($reason)",
        )
        if (ctx.dryRun) return
        pass.bunny.deleteRecord(pass.zoneId, record.id)
        ctx.log.change(DOMAIN, "deleted ${record.typeName} $shownName -> ${record.value} ($reason)")
    }

    // ------------------------------------------------------------------ nameservers

    /**
     * Marks records the API reports as in sync but a nameserver does not serve yet: until it
     * does, some lookups still get the old answer (for a new name, the wildcard's).
     */
    private suspend fun checkNameservers(zoneNameservers: List<String>, observed: List<Observed>): List<Observed> {
        val check = nameservers ?: return observed
        if (ctx.dryRun) return observed
        val records = observed.filter { it.inSync }.mapNotNull { it.desired }
        val result = check.check(zoneNameservers, records)
        for (record in result.changed) {
            val served = "${record.type} ${record.fqdn} -> ${record.value}"
            val problem = result.problems[record]
            if (problem != null) {
                ctx.log.warn(DOMAIN, "$served is not on every nameserver yet: $problem", record.nodeId)
            } else {
                ctx.log.info(DOMAIN, "$served is now on every nameserver", record.nodeId)
            }
        }
        return observed.map { entry ->
            val problem = entry.desired?.let { result.problems[it] } ?: return@map entry
            entry.copy(inSync = false, diff = "$NOT_ON_EVERY_NAMESERVER: $problem")
        }
    }

    // ------------------------------------------------------------------ records

    private fun toRecord(desired: DesiredDnsRecord, typeCode: Int): BunnyRecord {
        val configured = ctx.config.bunny.routing
        val routing = when {
            !desired.geo -> BunnyRecord.ROUTING_NONE
            configured == "geolocation" -> BunnyRecord.ROUTING_GEO
            configured == "latency" -> BunnyRecord.ROUTING_LATENCY
            else -> BunnyRecord.ROUTING_NONE
        }
        val monitor = when (desired.monitor) {
            "http" -> BunnyRecord.MONITOR_HTTP
            "ping" -> BunnyRecord.MONITOR_PING
            else -> BunnyRecord.MONITOR_NONE
        }
        return BunnyRecord(
            type = typeCode,
            ttl = desired.ttl,
            value = desired.value,
            name = desired.name,
            weight = WEIGHT,
            monitorType = monitor,
            latitude = if (routing == BunnyRecord.ROUTING_GEO) desired.latitude ?: 0.0 else 0.0,
            longitude = if (routing == BunnyRecord.ROUTING_GEO) desired.longitude ?: 0.0 else 0.0,
            latencyZone = if (routing == BunnyRecord.ROUTING_LATENCY) desired.latencyZone else null,
            smartRoutingType = routing,
            disabled = false,
            comment = desired.comment,
            flags = desired.tag?.let { 0 },
            tag = desired.tag,
        )
    }

    /** Returns the fields in which [actual] differs from [wanted], or null if the two agree. */
    private fun difference(actual: BunnyRecord, wanted: BunnyRecord): String? {
        val routing = wanted.smartRoutingType
        val samePlace = sameCoordinate(actual.latitude, wanted.latitude) &&
            sameCoordinate(actual.longitude, wanted.longitude)
        val changes = buildList {
            if (actual.value != wanted.value) add("value ${actual.value}->${wanted.value}")
            if (actual.ttl != wanted.ttl) add("ttl ${actual.ttl}->${wanted.ttl}")
            if (actual.smartRoutingType != routing) add("routing ${actual.smartRoutingType}->$routing")
            if (routing == BunnyRecord.ROUTING_GEO && !samePlace) add("geo")
            if (routing == BunnyRecord.ROUTING_LATENCY && actual.latencyZone != wanted.latencyZone) add("latency zone")
            if (actual.monitorType != wanted.monitorType) add("monitor ${actual.monitorType}->${wanted.monitorType}")
            if (actual.disabled) add("enabled")
            if (actual.comment != wanted.comment) add("ownership tag")
        }
        return changes.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun sameCoordinate(first: Double, second: Double) = abs(first - second) < COORDINATE_TOLERANCE

    private fun summary(record: BunnyRecord) = buildString {
        append("${record.typeName} ${record.value} ttl=${record.ttl}")
        if (record.smartRoutingType == BunnyRecord.ROUTING_GEO) {
            append(" geo(${record.latitude},${record.longitude})")
        }
        if (record.smartRoutingType == BunnyRecord.ROUTING_LATENCY) {
            append(" latency(${record.latencyZone})")
        }
        append(" monitor=${MONITOR_NAMES.getOrElse(record.monitorType) { "?" }}")
    }

    companion object {
        /** How [Observed.diff] starts for a record the API has but a nameserver does not serve yet. */
        const val NOT_ON_EVERY_NAMESERVER = "not on every nameserver"

        private const val DOMAIN = "dns"
        private const val WEIGHT = 100
        private const val COORDINATE_TOLERANCE = 0.0001

        /** The name of each monitor type of Bunny, in the order of the type codes. */
        private val MONITOR_NAMES = listOf("none", "ping", "http", "monitor")

        private fun BunnyRecord.isManaged(): Boolean = comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true
    }
}
