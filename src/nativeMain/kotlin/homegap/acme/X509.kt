package homegap.acme

import kotlin.io.encoding.Base64

/** Reads the validity of a PEM certificate. HomeGAP needs no other field. */
object X509 {
    /** Both times are seconds since the epoch. */
    data class Validity(val notBefore: Long, val notAfter: Long)

    fun validity(pem: String): Validity? = runCatching {
        val body = pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
        val der = Base64.decode(body.filterNot { it.isWhitespace() })
        val tbs = Reader(der).enter(0x30).enter(0x30)
        if (tbs.peek() == 0xA0) tbs.skip()
        // The serial number, the signature algorithm and the issuer come before the validity.
        repeat(3) { tbs.skip() }
        val validity = tbs.enter(0x30)
        Validity(validity.time(), validity.time())
    }.getOrNull()

    private class Reader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        fun peek(): Int = b[pos].toInt() and 0xFF

        private fun header(): Pair<Int, Int> {
            val tag = b[pos++].toInt() and 0xFF
            var len = b[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                require(n in 1..4)
                len = 0
                repeat(n) { len = (len shl 8) or (b[pos++].toInt() and 0xFF) }
            }
            require(len >= 0 && pos + len <= end)
            return tag to len
        }

        fun enter(tag: Int): Reader {
            val (t, len) = header()
            require(t == tag)
            return Reader(b, pos, pos + len).also { pos += len }
        }

        fun skip() {
            val (_, len) = header()
            pos += len
        }

        fun time(): Long {
            val (tag, len) = header()
            val s = b.decodeToString(pos, pos + len)
            pos += len
            val digits = when (tag) {
                // UTCTime has a two-digit year: 50 to 99 are in the 1900s.
                0x17 -> (if (s.take(2).toInt() >= 50) "19" else "20") + s
                0x18 -> s
                else -> error("not a time")
            }
            require(digits.endsWith("Z") && digits.length >= 15)
            fun at(from: Int, n: Int) = digits.substring(from, from + n).toInt()
            return epochSeconds(at(0, 4), at(4, 2), at(6, 2), at(8, 2), at(10, 2), at(12, 2))
        }
    }

    fun epochSeconds(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        return days * 86400 + hour * 3600 + minute * 60 + second
    }
}
