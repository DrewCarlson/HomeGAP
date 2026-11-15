package homegap.presentation.zone

/** The record types that the form can add. */
val RECORD_TYPES = listOf("A", "AAAA", "CNAME", "TXT", "MX", "CAA")

val CAA_TAGS = listOf("issue", "issuewild", "iodef")

/** The TTL choices of the form, in seconds. */
val TTL_CHOICES = listOf(60, 300, 3600, 86400)

const val DEFAULT_TTL = 300

/** A field of the record form that can have a problem. */
enum class RecordField {
    Name,
    Value,
    Priority,
    Flags,
}

/** The text of the form, as the user typed it. */
data class RecordInput(
    val type: String = "TXT",
    val name: String = "",
    val value: String = "",
    val priority: String = "",
    val flags: String = "0",
    val tag: String = CAA_TAGS.first(),
    val ttlSeconds: Int = DEFAULT_TTL,
) {
    /** The name relative to the zone. The root is `@`. */
    val relativeName: String get() = name.trim().trimEnd('.').ifEmpty { ROOT }

    fun toDraft() = RecordDraft(
        type = type,
        name = relativeName,
        value = value.trim(),
        ttlSeconds = ttlSeconds,
        priority = priority.trim().toIntOrNull().takeIf { type == "MX" },
        flags = flags.trim().toIntOrNull().takeIf { type == "CAA" },
        tag = tag.takeIf { type == "CAA" },
    )
}

/** The full name of [name] in [zone]. */
fun fullName(name: String, zone: String): String = if (name.isBlank() || name == ROOT) zone else "$name.$zone"

/** The value of a record as one line, with the priority of MX and the flags and tag of CAA. */
val ZoneRecord.line: String
    get() = when {
        type == "MX" && priority != null -> "$priority $value"
        type == "CAA" && tag != null -> "${flags ?: 0} $tag \"$value\""
        else -> value
    }

fun ttlLabel(seconds: Int?): String = when (seconds) {
    null -> "—"
    60 -> "1 min"
    300 -> "5 min"
    3600 -> "1 h"
    86400 -> "1 day"
    else -> "$seconds s"
}

/**
 * The problems of [input] for a record in a zone that has [records]. [editing] is the id of the
 * record that the form edits. The server has the same rules. The form shows them first, so the
 * user does not reach most refusals.
 */
fun recordProblems(input: RecordInput, zone: String, records: List<ZoneRecord>, editing: String? = null): Map<RecordField, String> = buildMap {
    val name = input.relativeName
    val value = input.value.trim()
    val managed = records.filter { it.kind == RecordKind.Managed && it.name == name }
    when {
        !name.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.*@" } || name.startsWith(".") || ".." in name || '@' in name && name != ROOT ->
            put(RecordField.Name, "Use letters, digits, - _ . and *. Leave it empty for the root.")
        managed.any { it.type == input.type } ->
            put(RecordField.Name, "HomeGAP manages ${input.type} records for ${fullName(name, zone)}.")
        input.type == "CNAME" && managed.isNotEmpty() ->
            put(RecordField.Name, "HomeGAP manages records at ${fullName(name, zone)}, so a CNAME cannot go there.")
        input.type == "CNAME" && name == ROOT ->
            put(RecordField.Name, "A CNAME cannot go at the root.")
        records.any { it.id != editing && it.kind == RecordKind.Own && it.name == name && it.type == input.type && it.value == value } ->
            put(RecordField.Name, "This record is in the zone already.")
    }
    when (input.type) {
        "A" -> if (!isIpv4(value)) put(RecordField.Value, "Enter an IPv4 address, for example 198.51.100.25.")
        "AAAA" -> if (!isIpv6(value)) put(RecordField.Value, "Enter an IPv6 address, for example 2001:db8::25.")
        "CNAME", "MX" -> if (!isHostName(value)) put(RecordField.Value, "Enter a host name, for example mail.example.net.")
        else -> if (value.isEmpty()) put(RecordField.Value, "Enter a value.")
    }
    if (input.type == "MX" && input.priority.trim().toIntOrNull()?.let { it in 0..65535 } != true) {
        put(RecordField.Priority, "Enter a number from 0 to 65535.")
    }
    if (input.type == "CAA" && input.flags.trim().toIntOrNull()?.let { it == 0 || it == 128 } != true) {
        put(RecordField.Flags, "Enter 0 or 128.")
    }
}

/** True when [name] is not the root, has no managed record of its own and is under a managed wildcard. */
fun underManagedWildcard(name: String, records: List<ZoneRecord>): Boolean {
    val managed = records.filter { it.kind == RecordKind.Managed }
    return name != ROOT && !name.startsWith("*") && managed.none { it.name == name } &&
        managed.any { it.name == "*" || it.name.startsWith("*.") && name.endsWith(it.name.removePrefix("*")) }
}

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) && part.toInt() <= 255 }
}

private fun isIpv6(value: String): Boolean {
    if (':' !in value || ":::" in value || value.split("::").size > 2) return false
    val groups = value.split(':')
    val short = "::" in value
    return groups.size in 3..8 && (short || groups.size == 8) &&
        groups.all { group -> group.length <= 4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } } &&
        (short || groups.none { it.isEmpty() })
}

private fun isHostName(value: String): Boolean {
    val labels = value.trimEnd('.').split('.')
    return value.isNotEmpty() && value.length <= 253 &&
        labels.all { label -> label.length in 1..63 && label.all { it.isLetterOrDigit() && it.code < 128 || it in "-_" } }
}
