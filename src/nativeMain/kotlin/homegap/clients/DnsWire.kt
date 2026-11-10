package homegap.clients

/**
 * Just enough of the DNS wire format (RFC 1035) to ask an authoritative nameserver for a name's
 * A or AAAA records and read the answer.
 */
object DnsWire {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun typeCode(type: String): Int? = when (type) {
        "A" -> TYPE_A
        "AAAA" -> TYPE_AAAA
        else -> null
    }

    /** A non-recursive query (for an authoritative server) for [name] and [type]. */
    fun encodeQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ArrayList<Byte>(32 + name.length)
        fun u16(v: Int) {
            out += (v shr 8 and 0xff).toByte()
            out += (v and 0xff).toByte()
        }
        u16(id)
        u16(0) // standard query, recursion not desired
        u16(1) // one question
        u16(0); u16(0); u16(0)
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
     * The addresses of type [type] in a response to query [id]: an empty list when the name has
     * none (or does not exist), null when the response is not a usable answer.
     */
    fun parseAnswers(msg: ByteArray, id: Int, type: Int): List<String>? {
        if (msg.size < 12) return null
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
        var at = 12
        repeat(questions) {
            at = skipName(msg, at) ?: return null
            at += 4
        }
        val out = mutableListOf<String>()
        repeat(answers) {
            at = skipName(msg, at) ?: return null
            if (at + 10 > msg.size) return null
            val rType = u16(at)
            val length = u16(at + 8)
            val data = at + 10
            if (data + length > msg.size) return null
            if (rType == type) {
                when {
                    type == TYPE_A && length == 4 -> out += (0 until 4).joinToString(".") { (msg[data + it].toInt() and 0xff).toString() }
                    type == TYPE_AAAA && length == 16 -> out += formatIpv6(msg.copyOfRange(data, data + 16))
                }
            }
            at = data + length
        }
        return out
    }

    /** Where the name starting at [at] ends (labels, or a compression pointer). */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var at = start
        while (at < msg.size) {
            val len = msg[at].toInt() and 0xff
            when {
                len == 0 -> return at + 1
                len and 0xc0 == 0xc0 -> return if (at + 2 <= msg.size) at + 2 else null
                else -> at += len + 1
            }
        }
        return null
    }

    /** [address] in one canonical form (RFC 5952 for IPv6), so equal addresses compare equal. */
    fun canonical(address: String): String =
        if (':' in address) parseIpv6(address)?.let(::formatIpv6) ?: address.lowercase() else address

    private fun parseIpv6(text: String): ByteArray? {
        val parts = text.lowercase().split("::")
        if (parts.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(parts[0])
        val tail = if (parts.size == 2) groups(parts[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if (missing < 0 || (parts.size == 1 && missing != 0)) return null
        val all = head + List(missing) { "0" } + tail
        val out = ByteArray(16)
        for ((i, g) in all.withIndex()) {
            val v = g.toIntOrNull(16)?.takeIf { g.length <= 4 } ?: return null
            out[i * 2] = (v shr 8).toByte()
            out[i * 2 + 1] = (v and 0xff).toByte()
        }
        return out
    }

    private fun formatIpv6(bytes: ByteArray): String {
        val g = (0 until 8).map { ((bytes[it * 2].toInt() and 0xff) shl 8) or (bytes[it * 2 + 1].toInt() and 0xff) }
        // The longest run of two or more zero groups becomes "::" (the first one on a tie).
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < 8 && g[i] == 0) i++
            if (i - start > bestLen && i - start >= 2) {
                bestStart = start
                bestLen = i - start
            }
        }
        if (bestStart < 0) return g.joinToString(":") { it.toString(16) }
        val head = g.subList(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = g.subList(bestStart + bestLen, 8).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }
}
