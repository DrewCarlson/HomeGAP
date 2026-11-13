package homegap.reconcile

import homegap.acme.DnsChallenge
import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.model.DesiredDnsRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    /** Asks the zone's nameservers whether they serve what the API reports; null skips it. */
    private val nameservers: NameserverCheck? = null,
) {
    private val domain = "dns"

    data class Observed(val record: BunnyRecord, val desired: DesiredDnsRecord?, val inSync: Boolean, val diff: String?)

    var lastObserved: List<Observed> = emptyList()
        private set

    suspend fun reconcile(desired: List<DesiredDnsRecord>, pausedServiceKeys: Set<String>): List<Observed> {
        val cfg = ctx.config.bunny
        val bunny = this.bunny
        if (bunny == null) {
            ctx.log.error(domain, "Bunny API key ${cfg.apiKey.describe()} is not set; DNS reconciliation skipped")
            desired.forEach { ctx.diff.add(domain, it.fqdn, "${it.type}@${it.nodeId}", "unknown", it.value) }
            return emptyList()
        }
        val zoneId = ctx.state.snapshot().bunnyZoneId ?: bunny.findZone(cfg.zone)?.id?.also { id ->
            ctx.state.update { it.copy(bunnyZoneId = id) }
        } ?: error("Bunny zone ${cfg.zone} not found (create it in Bunny DNS first)")
        val zone = runCatching { bunny.getZone(zoneId) }.getOrElse {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            // The cached zone id may be stale (zone recreated): look it up again next pass.
            ctx.state.update { s -> s.copy(bunnyZoneId = null) }
            throw it
        }
        val actual = zone.records
        val claimed = mutableSetOf<Long>()
        val observed = mutableListOf<Observed>()
        val global = ctx.pauses().global
        val pausedNodes = ctx.pauses().nodes

        fun isPaused(d: DesiredDnsRecord) = global || d.nodeId in pausedNodes || pausedServiceKeys.any { d.purpose == "service:$it" }

        for (d in desired) {
            val typeCode = BunnyRecord.typeCode(d.type)
            val sameName = actual.filter { it.type == typeCode && it.name.equals(d.name, ignoreCase = true) && it.id !in claimed && (d.tag == null || it.tag == d.tag) }
            val mine = sameName.firstOrNull { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true && it.comment.endsWith("node=${d.nodeId}") }
                ?: sameName.firstOrNull { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) != true && it.value == d.value }
            val want = toRecord(d, typeCode)
            if (mine == null) {
                ctx.diff.add(domain, d.fqdn, "${d.type}@${d.nodeId}", "create", summary(want))
                if (ctx.dryRun || isPaused(d)) {
                    observed += Observed(want, d, false, "missing")
                    continue
                }
                val created = bunny.addRecord(zoneId, want)
                claimed += created.id
                observed += Observed(created, d, true, null)
                ctx.log.change(domain, "created ${d.type} ${d.fqdn} -> ${d.value} (${d.purpose})", d.nodeId, mapOf("record" to d.fqdn))
                continue
            }
            claimed += mine.id
            val diff = diff(mine, want)
            if (diff == null) {
                ctx.diff.add(domain, d.fqdn, "${d.type}@${d.nodeId}", "none", summary(want), summary(mine))
                observed += Observed(mine, d, true, null)
                continue
            }
            ctx.diff.add(domain, d.fqdn, "${d.type}@${d.nodeId}", "update", summary(want), summary(mine))
            if (ctx.dryRun || isPaused(d)) {
                observed += Observed(mine, d, false, diff)
                continue
            }
            bunny.updateRecord(zoneId, want.copy(id = mine.id))
            observed += Observed(want.copy(id = mine.id, monitorStatus = mine.monitorStatus), d, true, null)
            ctx.log.change(domain, "updated ${d.type} ${d.fqdn}: $diff", d.nodeId, mapOf("record" to d.fqdn))
        }

        val desiredNames = desired.map { it.name.lowercase() to BunnyRecord.typeCode(it.type) }.toSet()
        for (a in actual) {
            if (a.id in claimed) continue
            // A certificate order runs outside the pass and owns its challenge records.
            if (a.comment == DnsChallenge.COMMENT) continue
            val managed = a.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true
            val collides = (a.name.lowercase() to a.type) in desiredNames ||
                // a CNAME at a name we serve with A/AAAA would shadow the whole set
                (a.type == BunnyRecord.typeCode("CNAME") && desiredNames.any { it.first == a.name.lowercase() })
            if (!managed && !collides) continue
            val node = a.comment?.substringAfter("node=", "")?.takeIf { managed }
            if (global || (node != null && node in pausedNodes) || (managed && pausedServiceKeys.any { a.comment.contains("service:$it ") })) {
                observed += Observed(a, null, false, "kept: paused")
                continue
            }
            val why = if (managed) "no longer desired" else "collides with a managed record set"
            ctx.diff.add(domain, a.name.ifEmpty { "@" }, "${a.typeName}#${a.id}", "delete", null, "${a.value} ($why)")
            if (ctx.dryRun) continue
            bunny.deleteRecord(zoneId, a.id)
            ctx.log.change(domain, "deleted ${a.typeName} ${a.name.ifEmpty { "@" }} -> ${a.value} ($why)")
        }

        val served = checkNameservers(zone.nameservers, observed)
        lastObserved = served
        return served
    }

    /**
     * Marks records the API reports as in sync but a nameserver does not serve yet: until it
     * does, some lookups still get the old answer (for a new name, the wildcard's).
     */
    private suspend fun checkNameservers(zoneNameservers: List<String>, observed: List<Observed>): List<Observed> {
        val check = nameservers ?: return observed
        if (ctx.dryRun) return observed
        val records = observed.filter { it.inSync }.mapNotNull { it.desired }
        val result = check.check(zoneNameservers, records)
        for (r in result.changed) {
            val problem = result.problems[r]
            if (problem != null) {
                ctx.log.warn(domain, "${r.type} ${r.fqdn} -> ${r.value} is not on every nameserver yet: $problem", r.nodeId)
            } else {
                ctx.log.info(domain, "${r.type} ${r.fqdn} -> ${r.value} is now on every nameserver", r.nodeId)
            }
        }
        return observed.map { o ->
            val problem = o.desired?.let { result.problems[it] } ?: return@map o
            o.copy(inSync = false, diff = "$NOT_ON_EVERY_NAMESERVER: $problem")
        }
    }

    private fun toRecord(d: DesiredDnsRecord, typeCode: Int): BunnyRecord {
        val routing = ctx.config.bunny.routing
        val smart = when {
            !d.geo -> BunnyRecord.ROUTING_NONE
            routing == "geolocation" -> BunnyRecord.ROUTING_GEO
            routing == "latency" -> BunnyRecord.ROUTING_LATENCY
            else -> BunnyRecord.ROUTING_NONE
        }
        val monitor = when (d.monitor) {
            "http" -> BunnyRecord.MONITOR_HTTP
            "ping" -> BunnyRecord.MONITOR_PING
            else -> BunnyRecord.MONITOR_NONE
        }
        return BunnyRecord(
            type = typeCode,
            ttl = d.ttl,
            value = d.value,
            name = d.name,
            weight = 100,
            monitorType = monitor,
            latitude = if (smart == BunnyRecord.ROUTING_GEO) d.latitude ?: 0.0 else 0.0,
            longitude = if (smart == BunnyRecord.ROUTING_GEO) d.longitude ?: 0.0 else 0.0,
            latencyZone = if (smart == BunnyRecord.ROUTING_LATENCY) d.latencyZone else null,
            smartRoutingType = smart,
            disabled = false,
            comment = d.comment,
            flags = d.tag?.let { 0 },
            tag = d.tag,
        )
    }

    private fun diff(a: BunnyRecord, w: BunnyRecord): String? {
        val out = mutableListOf<String>()
        if (a.value != w.value) out += "value ${a.value}->${w.value}"
        if (a.ttl != w.ttl) out += "ttl ${a.ttl}->${w.ttl}"
        if (a.smartRoutingType != w.smartRoutingType) out += "routing ${a.smartRoutingType}->${w.smartRoutingType}"
        if (w.smartRoutingType == BunnyRecord.ROUTING_GEO && (!close(a.latitude, w.latitude) || !close(a.longitude, w.longitude))) out += "geo"
        if (w.smartRoutingType == BunnyRecord.ROUTING_LATENCY && a.latencyZone != w.latencyZone) out += "latency zone"
        if (a.monitorType != w.monitorType) out += "monitor ${a.monitorType}->${w.monitorType}"
        if (a.disabled) out += "enabled"
        if (a.comment != w.comment) out += "ownership tag"
        return out.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    companion object {
        /** How [Observed.diff] starts for a record the API has but a nameserver does not serve yet. */
        const val NOT_ON_EVERY_NAMESERVER = "not on every nameserver"
    }

    private fun close(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.0001

    private fun summary(r: BunnyRecord) = buildString {
        append("${r.typeName} ${r.value} ttl=${r.ttl}")
        if (r.smartRoutingType == BunnyRecord.ROUTING_GEO) append(" geo(${r.latitude},${r.longitude})")
        if (r.smartRoutingType == BunnyRecord.ROUTING_LATENCY) append(" latency(${r.latencyZone})")
        append(" monitor=${listOf("none", "ping", "http", "monitor").getOrElse(r.monitorType) { "?" }}")
    }
}
