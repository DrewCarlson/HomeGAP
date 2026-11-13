package homegap.clients

/**
 * The part of the DNS wire format (RFC 1035) that HomeGAP needs: a query to an authoritative
 * nameserver for the A or AAAA records of a name, and the answer.
 */
object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    private const val HEADER_SIZE = 12
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val IPV6_GROUPS = 8

    fun typeCode(type: String): Int? = when (type) {
        "A" -> TYPE_A
        "AAAA" -> TYPE_AAAA
        else -> null
    }

    /** A non-recursive query (for an authoritative server) for [name] and [type]. */
    fun encodeQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ArrayList<Byte>(32 + name.length)
        fun u16(value: Int) {
            out += (value shr 8 and 0xff).toByte()
            out += (value and 0xff).toByte()
        }
        u16(id)
        u16(0) // standard query, recursion not desired
        u16(1) // one question
        u16(0)
        u16(0)
        u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63) { "bad label in $name" }
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        out += 0
        u16(type)
        u16(1) // IN
        return out.toByteArray()
    }

    /**
     * The addresses of type [type] in a response to query [id]. The list is empty when the name
     * has none or does not exist. The result is null when the response is not a usable answer.
     */
    fun parseAnswers(msg: ByteArray, id: Int, type: Int): List<String>? {
        if (msg.size < HEADER_SIZE) return null
        fun u16(at: Int) = ((msg[at].toInt() and 0xff) shl 8) or (msg[at + 1].toInt() and 0xff)
        if (u16(0) != id) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null // not a response
        if (flags and 0x0200 != 0) return null // truncated
        when (flags and 0x000f) {
            0 -> Unit
            3 -> return emptyList() // NXDOMAIN
            else -> return null
        }
        val questions = u16(4)
        val answers = u16(6)
        var at = HEADER_SIZE
        repeat(questions) {
            at = skipName(msg, at) ?: return null
            at += 4
        }
        val addresses = mutableListOf<String>()
        repeat(answers) {
            at = skipName(msg, at) ?: return null
            if (at + 10 > msg.size) return null
            val recordType = u16(at)
            val length = u16(at + 8)
            val data = at + 10
            if (data + length > msg.size) return null
            if (recordType == type) {
                when (type) {
                    TYPE_A if length == IPV4_BYTES -> addresses += formatIpv4(msg, data)
                    TYPE_AAAA if length == IPV6_BYTES -> addresses += formatIpv6(msg.copyOfRange(data, data + length))
                }
            }
            at = data + length
        }
        return addresses
    }

    /** Where the name that starts at [start] ends (labels, or a compression pointer). */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var at = start
        while (at < msg.size) {
            val length = msg[at].toInt() and 0xff
            when {
                length == 0 -> return at + 1
                length and 0xc0 == 0xc0 -> return if (at + 2 <= msg.size) at + 2 else null
                else -> at += length + 1
            }
        }
        return null
    }

    private fun formatIpv4(msg: ByteArray, start: Int): String =
        (0 until IPV4_BYTES).joinToString(".") { (msg[start + it].toInt() and 0xff).toString() }

    /** [address] in one canonical form (RFC 5952 for IPv6), so equal addresses compare equal. */
    fun canonical(address: String): String =
        if (':' in address) parseIpv6(address)?.let(::formatIpv6) ?: address.lowercase() else address

    private fun parseIpv6(text: String): ByteArray? {
        val parts = text.lowercase().split("::")
        if (parts.size > 2) return null
        fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
        val head = groups(parts[0])
        val tail = if (parts.size == 2) groups(parts[1]) else emptyList()
        val missing = IPV6_GROUPS - head.size - tail.size
        if (missing < 0 || (parts.size == 1 && missing != 0)) return null
        val all = buildList {
            addAll(head)
            repeat(missing) { add("0") }
            addAll(tail)
        }
        val bytes = ByteArray(IPV6_BYTES)
        for ((i, group) in all.withIndex()) {
            val value = group.toIntOrNull(16)?.takeIf { group.length <= 4 } ?: return null
            bytes[i * 2] = (value shr 8).toByte()
            bytes[i * 2 + 1] = (value and 0xff).toByte()
        }
        return bytes
    }

    private fun formatIpv6(bytes: ByteArray): String {
        val groups = (0 until IPV6_GROUPS).map {
            ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff)
        }
        // The longest run of two or more zero groups becomes "::" (the first one on a tie).
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < IPV6_GROUPS) {
            if (groups[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < IPV6_GROUPS && groups[i] == 0) i++
            if (i - start > bestLength && i - start >= 2) {
                bestStart = start
                bestLength = i - start
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val head = groups.subList(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.subList(bestStart + bestLength, IPV6_GROUPS).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }
}
