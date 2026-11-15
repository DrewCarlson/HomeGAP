package homegap.zone

import homegap.acme.DnsChallenge
import homegap.api.ZoneRecordDto
import homegap.api.ZoneRecordKind
import homegap.api.ZoneRecordRequest
import homegap.clients.BunnyRecord
import homegap.model.DesiredDnsRecord
import homegap.model.Planner

/**
 * The rules for the records of a zone that the user edits in the dashboard. HomeGAP owns each
 * record whose comment starts with [DesiredDnsRecord.OWNER_TAG]. The user owns each other record.
 */
object ZoneRecordRules {
    /** The name of the root of a zone, as the dashboard shows it. Bunny stores an empty name. */
    const val ROOT = "@"

    /** The record types that the dashboard can add and edit. */
    val TYPES = listOf("A", "AAAA", "CNAME", "TXT", "MX", "CAA")
    private val CAA_TAGS = setOf("issue", "issuewild", "iodef")
    private val CAA_FLAGS = setOf(0, 128)
    private const val LABEL = "[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?"
    private val NAME = Regex("^(\\*|(\\*\\.)?$LABEL(\\.$LABEL)*)$")
    private val HOSTNAME = Regex("^$LABEL(\\.$LABEL)*\\.?$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$")
    private val TTL = 15..86_400
    private val PRIORITY = 0..65_535
    private const val VALUE_MAX = 4096
    private const val NAME_MAX = 253
    private const val PURPOSE_FIELD = 1
    private const val NODE_FIELD = "node="

    fun kind(record: BunnyRecord): ZoneRecordKind = when {
        record.comment == DnsChallenge.COMMENT -> ZoneRecordKind.CHALLENGE
        record.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true -> ZoneRecordKind.MANAGED
        else -> ZoneRecordKind.OWN
    }

    fun toDto(record: BunnyRecord): ZoneRecordDto {
        val kind = kind(record)
        val caa = record.typeName == "CAA"
        return ZoneRecordDto(
            id = record.id.toString(),
            name = record.name.ifEmpty { ROOT },
            type = record.typeName,
            value = record.value,
            kind = kind,
            ttlSeconds = record.ttl,
            priority = record.priority.takeIf { record.typeName == "MX" },
            flags = record.flags.takeIf { caa },
            tag = record.tag.takeIf { caa },
            purpose = purpose(record).takeIf { kind == ZoneRecordKind.MANAGED },
            nodeId = nodeOf(record).takeIf { kind == ZoneRecordKind.MANAGED },
        )
    }

    /** What a managed record is for, from the purpose in its comment. */
    private fun purpose(record: BunnyRecord): String? {
        val purpose = record.comment?.split(' ')?.getOrNull(PURPOSE_FIELD) ?: return null
        return when {
            purpose == "root" -> "front door"
            purpose == "wildcard" -> "front door (wildcard)"
            purpose == "caa" -> "CAA"
            purpose == "status" -> "status page"
            purpose == "speedtest" -> "speed test"
            purpose.startsWith("service:") -> "service"
            else -> purpose
        }
    }

    /** The node of a managed record. The CAA records belong to no node. */
    private fun nodeOf(record: BunnyRecord): String? =
        record.comment?.substringAfter(NODE_FIELD, "")?.takeIf { it.isNotEmpty() && it != Planner.CAA_OWNER }

    /** The name of [request] as Bunny stores it: lower case, and empty for the root. */
    fun storedName(request: ZoneRecordRequest): String =
        request.name.trim().lowercase().removeSuffix(".").takeIf { it != ROOT }.orEmpty()

    /** The reason that [request] is not a valid record, or null if it is valid. */
    fun problem(request: ZoneRecordRequest): String? {
        val name = storedName(request)
        val value = request.value.trim()
        return when {
            request.type !in TYPES -> "The type must be one of ${TYPES.joinToString(", ")}."
            name.length > NAME_MAX || (name.isNotEmpty() && !NAME.matches(name)) -> "The name is not a valid DNS name."
            value.isEmpty() -> "The value is empty."
            value.length > VALUE_MAX -> "The value is too long."
            request.ttlSeconds !in TTL -> "The TTL must be from ${TTL.first} to ${TTL.last} seconds."
            else -> typeProblem(request, name, value)
        }
    }

    private fun typeProblem(request: ZoneRecordRequest, name: String, value: String): String? = when (request.type) {
        "A" -> "The value must be an IPv4 address.".takeIf { !IPV4.matches(value) }
        "AAAA" -> "The value must be an IPv6 address.".takeIf { !IPV6.matches(value) }
        "CNAME" -> when {
            name.isEmpty() -> "A CNAME cannot be at the root of the zone."
            !HOSTNAME.matches(value) -> "The target must be a hostname."
            else -> null
        }
        "MX" -> when {
            !HOSTNAME.matches(value) -> "The mail server must be a hostname."
            request.priority == null || request.priority !in PRIORITY -> "The priority must be from 0 to 65535."
            else -> null
        }
        "CAA" -> when {
            request.tag !in CAA_TAGS -> "The tag must be issue, issuewild or iodef."
            (request.flags ?: 0) !in CAA_FLAGS -> "The flags must be 0 or 128."
            else -> null
        }
        else -> null
    }

    /**
     * The reason that HomeGAP refuses [request] in a zone, or null. The DNS pass deletes a record
     * of the user that has the name and the type of a managed record set, and a CNAME at a name
     * that HomeGAP manages. [managed] has the name and the type of each managed record of the
     * zone, wanted or present. [fullName] is the name of the request with its zone.
     */
    fun collision(request: ZoneRecordRequest, managed: Set<Pair<String, String>>, fullName: String): String? {
        val name = storedName(request)
        return when {
            (name to request.type) in managed -> "HomeGAP manages ${request.type} records for $fullName."
            request.type == "CNAME" && managed.any { it.first == name } ->
                "HomeGAP manages records at $fullName, so a CNAME cannot go there."
            else -> null
        }
    }

    /**
     * The record that Bunny gets for [request]. With [existing], the record keeps its id and each
     * field that the dashboard does not edit.
     */
    fun toBunny(request: ZoneRecordRequest, existing: BunnyRecord? = null): BunnyRecord {
        val caa = request.type == "CAA"
        val base = existing ?: BunnyRecord(type = BunnyRecord.typeCode(request.type))
        return base.copy(
            ttl = request.ttlSeconds,
            value = request.value.trim(),
            name = storedName(request),
            flags = (request.flags ?: 0).takeIf { caa },
            tag = request.tag.takeIf { caa },
            priority = request.priority.takeIf { request.type == "MX" },
        )
    }
}
