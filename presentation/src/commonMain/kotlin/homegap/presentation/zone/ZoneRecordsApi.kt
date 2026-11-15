package homegap.presentation.zone

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.ZoneRecordDto
import homegap.api.ZoneRecordKind
import homegap.api.ZoneRecordRequest
import homegap.presentation.data.ControllerApi

/** Who owns a record of a zone. Only [Own] records can change. */
enum class RecordKind {
    /** A record of the user. */
    Own,

    /** A record that HomeGAP writes. HomeGAP writes it again on its next pass. */
    Managed,

    /** An `_acme-challenge` record that a certificate order owns. */
    Challenge,
}

/**
 * One record of a DNS zone. [name] is relative to the zone, and `@` is the root. For MX, [value]
 * is the mail server and [priority] its priority. For CAA, [value] is the authority, with [flags]
 * and [tag].
 */
data class ZoneRecord(
    val id: String,
    val name: String,
    val type: String,
    val value: String,
    val kind: RecordKind,
    val ttlSeconds: Int? = null,
    val priority: Int? = null,
    val flags: Int? = null,
    val tag: String? = null,
    /** What a managed record is for ("front door", "service", "CAA"). */
    val purpose: String? = null,
    /** The node that a managed record points to. */
    val nodeId: String? = null,
)

/** One zone of the DNS provider, with every record in it. */
data class DnsZone(
    val name: String,
    val records: List<ZoneRecord>,
)

/** The fields of a record that the user adds or edits. */
data class RecordDraft(
    val type: String,
    val name: String,
    val value: String,
    val ttlSeconds: Int,
    val priority: Int? = null,
    val flags: Int? = null,
    val tag: String? = null,
)

/**
 * The records of the DNS zones, and the changes to the records of the user. A change that the
 * server or the DNS provider refuses throws, and the message of the exception is the reason.
 */
interface ZoneRecordsApi {
    /** False for a user who can only look. */
    val canEdit: Boolean

    /** A note for the top of the screen. Null when the screen needs none. */
    val notice: String? get() = null

    /** Every zone, with the managed, challenge and own records of each. */
    suspend fun zones(): List<DnsZone>

    suspend fun add(zone: String, record: RecordDraft)

    suspend fun update(zone: String, id: String, record: RecordDraft)

    suspend fun delete(zone: String, id: String)
}

/** The [ZoneRecordsApi] over the API of the orchestrator. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ControllerZoneRecordsApi(
    private val api: ControllerApi,
) : ZoneRecordsApi {
    /** The answer of the last [zones] call. Before the first call, the screen has no records to edit. */
    override var canEdit: Boolean = true
        private set

    override suspend fun zones(): List<DnsZone> {
        val response = api.zoneRecords()
        canEdit = response.canEdit
        return response.zones.map { zone -> DnsZone(zone.name, zone.records.map { it.toRecord() }) }
    }

    override suspend fun add(zone: String, record: RecordDraft) {
        api.addZoneRecord(zone, record.toRequest())
    }

    override suspend fun update(zone: String, id: String, record: RecordDraft) {
        api.updateZoneRecord(zone, id, record.toRequest())
    }

    override suspend fun delete(zone: String, id: String) {
        api.deleteZoneRecord(zone, id)
    }

    private fun ZoneRecordDto.toRecord() = ZoneRecord(
        id = id,
        name = name,
        type = type,
        value = value,
        kind = when (kind) {
            ZoneRecordKind.OWN -> RecordKind.Own
            ZoneRecordKind.MANAGED -> RecordKind.Managed
            ZoneRecordKind.CHALLENGE -> RecordKind.Challenge
        },
        ttlSeconds = ttlSeconds,
        priority = priority,
        flags = flags,
        tag = tag,
        purpose = purpose,
        nodeId = nodeId,
    )

    private fun RecordDraft.toRequest() = ZoneRecordRequest(
        type = type,
        name = name,
        value = value,
        ttlSeconds = ttlSeconds,
        priority = priority,
        flags = flags,
        tag = tag,
    )
}

/** The name of the root of a zone. */
const val ROOT = "@"
