package homegap.presentation.zone

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.StatusSnapshot
import homegap.presentation.data.ControllerSession

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

/**
 * TODO(backend): replace this class with a [ZoneRecordsApi] over the orchestrator's API.
 *
 * Until then it shows the records that HomeGAP manages, from the status snapshot, and it keeps
 * the records of the user in memory. It knows no challenge records, no TTL and no read-only role,
 * and it does not apply the rules of the server (a collision with a managed set).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class StubZoneRecordsApi(
    private val session: ControllerSession,
) : ZoneRecordsApi {
    private val own = mutableMapOf<String, List<ZoneRecord>>()
    private var nextId = 1

    override val canEdit: Boolean = true

    override val notice: String = "Zone records are not connected to the DNS provider yet. A change stays in this browser tab."

    override suspend fun zones(): List<DnsZone> {
        val status = session.status.value ?: return emptyList()
        val names = status.domains.map { it.zone }.distinct()
        return names.map { zone ->
            DnsZone(
                name = zone,
                records = managed(status, zone, names) + own[zone].orEmpty(),
            )
        }
    }

    override suspend fun add(zone: String, record: RecordDraft) {
        own[zone] = own[zone].orEmpty() + record.toRecord("own-${nextId++}")
    }

    override suspend fun update(zone: String, id: String, record: RecordDraft) {
        own[zone] = own[zone].orEmpty().map { if (it.id == id) record.toRecord(id) else it }
    }

    override suspend fun delete(zone: String, id: String) {
        own[zone] = own[zone].orEmpty().filterNot { it.id == id }
    }

    private fun managed(status: StatusSnapshot, zone: String, zones: List<String>): List<ZoneRecord> {
        val services = status.services.map { it.hostname }.toSet()
        return status.dns
            .filter { record -> zones.filter { record.name == it || record.name.endsWith(".$it") }.maxByOrNull { it.length } == zone }
            .map { record ->
                ZoneRecord(
                    id = "managed:${record.name}:${record.type}:${record.value}",
                    name = if (record.name == zone) ROOT else record.name.removeSuffix(".$zone"),
                    type = record.type,
                    value = record.value,
                    kind = RecordKind.Managed,
                    purpose = when {
                        record.type == "CAA" -> "CAA"
                        record.name in services -> "service"
                        else -> null
                    },
                    nodeId = record.nodeId,
                )
            }
    }

    private fun RecordDraft.toRecord(id: String) = ZoneRecord(
        id = id,
        name = name.ifBlank { ROOT },
        type = type,
        value = value,
        kind = RecordKind.Own,
        ttlSeconds = ttlSeconds,
        priority = priority,
        flags = flags,
        tag = tag,
    )
}

/** The name of the root of a zone. */
const val ROOT = "@"
