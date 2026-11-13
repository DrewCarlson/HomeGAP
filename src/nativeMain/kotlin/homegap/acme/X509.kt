package homegap.acme

import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64

/** Reads the public fields of the first certificate in a PEM file. */
object X509 {
    /** Both times are seconds since the epoch. */
    data class Validity(val notBefore: Long, val notAfter: Long)

    /**
     * The public facts of a certificate. [fingerprint] is the SHA-256 of the certificate itself,
     * in colon-separated hex, the form that browsers show.
     */
    data class Info(
        val serial: String,
        val issuer: String,
        val validity: Validity,
        val keyType: String,
        val fingerprint: String,
    )

    fun validity(pem: String): Validity? = info(pem)?.validity

    fun info(pem: String): Info? = runCatching {
        val body = pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
        val der = Base64.decode(body.filterNot { it.isWhitespace() })
        val outer = Reader(der).enter(0x30)
        val tbs = outer.enter(0x30)
        if (tbs.peek() == 0xA0) tbs.skip()
        val serial = tbs.bytes(0x02)
        tbs.skip()
        val issuer = name(tbs.enter(0x30))
        val validity = tbs.enter(0x30)
        val times = Validity(validity.time(), validity.time())
        tbs.skip()
        val algorithm = tbs.enter(0x30).enter(0x30)
        val keyOid = algorithm.bytes(0x06).hex()
        val params = if (algorithm.more() && algorithm.peek() == 0x06) algorithm.bytes(0x06).hex() else null
        Info(
            serial = serial.dropWhile { it == 0.toByte() }.toByteArray().hex(":").uppercase(),
            issuer = issuer,
            validity = times,
            keyType = keyType(keyOid, params),
            fingerprint = der.toByteString().sha256().hex().chunked(2).joinToString(":").uppercase(),
        )
    }.getOrNull()

    /** "R11 · Let's Encrypt": the common name, then the organization. */
    private fun name(r: Reader): String {
        val parts = mutableMapOf<String, String>()
        while (r.more()) {
            val set = r.enter(0x31)
            while (set.more()) {
                val pair = set.enter(0x30)
                val oid = pair.bytes(0x06).hex()
                parts[oid] = pair.string()
            }
        }
        return listOfNotNull(parts[CN], parts[O]).joinToString(" · ").ifEmpty { "unknown" }
    }

    private fun keyType(oid: String, params: String?): String = when (oid) {
        EC_KEY -> when (params) {
            P256 -> "EC P-256"
            P384 -> "EC P-384"
            else -> "EC"
        }
        RSA_KEY -> "RSA"
        ED25519 -> "Ed25519"
        else -> "unknown"
    }

    private fun ByteArray.hex(separator: String = ""): String =
        joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private const val CN = "550403"
    private const val O = "55040a"
    private const val EC_KEY = "2a8648ce3d0201"
    private const val P256 = "2a8648ce3d030107"
    private const val P384 = "2b81040022"
    private const val RSA_KEY = "2a864886f70d010101"
    private const val ED25519 = "2b6570"

    private class Reader(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        fun peek(): Int = b[pos].toInt() and 0xFF

        fun more(): Boolean = pos < end

        fun bytes(tag: Int): ByteArray {
            val (t, len) = header()
            require(t == tag)
            return b.copyOfRange(pos, pos + len).also { pos += len }
        }

        fun string(): String {
            val (_, len) = header()
            return b.decodeToString(pos, pos + len).also { pos += len }
        }

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
