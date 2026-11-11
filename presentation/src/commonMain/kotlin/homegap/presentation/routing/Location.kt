package homegap.presentation.routing

/**
 * Where the dashboard is: a [route] and, optionally, the item selected on it, which the Inspector
 * shows. The URL fragment holds both (`#nodes/sg1`), so the back button closes the Inspector and
 * every selection can be linked.
 */
data class Location(
    val route: Route,
    val selection: String? = null,
) {
    /** The URL fragment without `#`. */
    val path: String
        get() = selection?.let { "${route.path}/${encodeSegment(it)}" } ?: route.path

    companion object {
        /** Reads a URL fragment, with or without `#`; unknown routes open the default one. */
        fun fromPath(path: String): Location {
            val trimmed = path.removePrefix("#")
            val routePath = trimmed.substringBefore('/')
            val selection = trimmed.substringAfter('/', missingDelimiterValue = "")
            return Location(
                route = Route.entries.firstOrNull { it.path == routePath } ?: Route.DEFAULT,
                selection = selection.takeIf { it.isNotEmpty() }?.let(::decodeSegment),
            )
        }
    }
}

/** Percent-encodes the characters that would end or split a fragment segment. */
internal fun encodeSegment(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val char = (byte.toInt() and 0xff).toChar()
        if (char.isLetterOrDigit() && char.code < 128 || char in "-._~*") {
            append(char)
        } else {
            append('%')
            append(HEX[(byte.toInt() shr 4) and 0xf])
            append(HEX[byte.toInt() and 0xf])
        }
    }
}

internal fun decodeSegment(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val code = if (value[index] == '%') value.substring(index + 1, minOf(index + 3, value.length)).toIntOrNull(16) else null
        if (code != null && index + 2 < value.length) {
            bytes += code.toByte()
            index += 3
        } else {
            bytes += value[index].toString().encodeToByteArray().toList()
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX = "0123456789ABCDEF"
