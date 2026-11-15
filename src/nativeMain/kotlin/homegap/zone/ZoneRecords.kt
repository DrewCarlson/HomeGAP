package homegap.zone

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.DnsZoneDto
import homegap.api.ZoneRecordDto
import homegap.api.ZoneRecordKind
import homegap.api.ZoneRecordRequest
import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.clients.BunnyZone
import homegap.clients.BunnyZones
import homegap.di.ExternalHttp
import homegap.reconcile.Controller
import io.ktor.client.HttpClient

/**
 * The records of the DNS zones, for the Zone records tab of the dashboard. It shows every record
 * of each zone. It changes only the records of the user ([ZoneRecordRules]). A change goes to
 * Bunny at once, also in a dry run and during a pause, because the user asks for it directly.
 */
@SingleIn(AppScope::class)
@Inject
class ZoneRecords(
    private val controller: Controller,
    @ExternalHttp private val http: HttpClient,
) {
    /** One zone that a change is in: the client, the id of the zone and its records now. */
    private class OpenZone(val bunny: BunnyClient, val name: String, val zone: BunnyZone)

    suspend fun zones(): List<DnsZoneDto> {
        val bunny = client()
        return controller.config.zones.map { name ->
            val records = load(bunny, name).zone.records
            DnsZoneDto(name, records.map(ZoneRecordRules::toDto).sortedWith(ORDER))
        }
    }

    /** Adds a record of the user to [zoneName]. Returns what the activity log says. */
    suspend fun add(zoneName: String, request: ZoneRecordRequest): String {
        val open = open(zoneName)
        check(open, request)
        open.bunny.addRecord(open.zone.id, ZoneRecordRules.toBunny(request))
        return logged("added", open.name, request.type, ZoneRecordRules.storedName(request))
    }

    /** Changes the record [id] of the user. The type of a record does not change. */
    suspend fun update(zoneName: String, id: String, request: ZoneRecordRequest): String {
        val open = open(zoneName)
        val existing = own(open, id)
        require(existing.typeName == request.type) { TYPE_IS_FIXED }
        check(open, request)
        open.bunny.updateRecord(open.zone.id, ZoneRecordRules.toBunny(request, existing))
        return logged("changed", open.name, request.type, ZoneRecordRules.storedName(request))
    }

    suspend fun delete(zoneName: String, id: String): String {
        val open = open(zoneName)
        val existing = own(open, id)
        open.bunny.deleteRecord(open.zone.id, existing.id)
        return logged("deleted", open.name, existing.typeName, existing.name)
    }

    private fun check(open: OpenZone, request: ZoneRecordRequest) {
        ZoneRecordRules.problem(request)?.let { throw IllegalArgumentException(it) }
        val fullName = fullName(ZoneRecordRules.storedName(request), open.name)
        ZoneRecordRules.collision(request, managed(open), fullName)?.let { throw IllegalArgumentException(it) }
    }

    /** The name and the type of each managed record of the zone: what the plan wants, and what the zone has. */
    private fun managed(open: OpenZone): Set<Pair<String, String>> {
        val config = controller.config
        val wanted = controller.desired?.dns.orEmpty()
            .filter { config.zoneOf(it.fqdn) == open.name }
            .map { it.name.lowercase() to it.type }
        val present = open.zone.records
            .filter { ZoneRecordRules.kind(it) == ZoneRecordKind.MANAGED }
            .map { it.name.lowercase() to it.typeName }
        return (wanted + present).toSet()
    }

    /** The record [id] of the zone, which must be a record of the user. */
    private fun own(open: OpenZone, id: String): BunnyRecord {
        val record = open.zone.records.firstOrNull { it.id.toString() == id }
            ?: throw IllegalArgumentException("The zone ${open.name} has no such record. Load the page again.")
        when (ZoneRecordRules.kind(record)) {
            ZoneRecordKind.MANAGED -> throw IllegalArgumentException("HomeGAP manages this record.")
            ZoneRecordKind.CHALLENGE -> throw IllegalArgumentException("A certificate order uses this record.")
            ZoneRecordKind.OWN -> return record
        }
    }

    private suspend fun open(zoneName: String): OpenZone {
        require(zoneName in controller.config.zones) { "$zoneName is not a zone of this config." }
        return load(client(), zoneName)
    }

    private suspend fun load(bunny: BunnyClient, zoneName: String): OpenZone {
        val zones = BunnyZones(controller.state, controller.config.bunny.zone)
        val id = zones.id(bunny, zoneName)
        return OpenZone(bunny, zoneName, bunny.getZone(id))
    }

    private fun client(): BunnyClient {
        val bunny = controller.config.bunny
        val key = bunny.apiKey.resolve() ?: error("The Bunny API key ${bunny.apiKey.describe()} is not set.")
        return BunnyClient(http, bunny.apiUrl, key)
    }

    private fun logged(action: String, zone: String, type: String, name: String): String {
        val fullName = fullName(name, zone)
        val message = "$action $type $fullName (from the dashboard)"
        controller.log.change(DOMAIN, message, fields = mapOf("record" to fullName))
        return "${action.replaceFirstChar { it.uppercase() }} $type $fullName"
    }

    private fun fullName(name: String, zone: String) = if (name.isEmpty()) zone else "$name.$zone"

    private companion object {
        const val DOMAIN = "dns"
        const val TYPE_IS_FIXED = "The type of a record cannot change. Delete it and add a new one."
        val ORDER = compareBy<ZoneRecordDto>({ it.name }, { it.type }, { it.value })
    }
}
