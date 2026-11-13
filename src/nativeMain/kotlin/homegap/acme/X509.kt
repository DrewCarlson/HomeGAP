package homegap.acme

import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64

/** Reads the public fields of the first certificate in a PEM file. */
object X509 {
    /** The two times are seconds since the epoch. */
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
        val outer = Reader(der).enter(SEQUENCE)
        val tbs = outer.enter(SEQUENCE)
        if (tbs.peek() == VERSION) tbs.skip()
        val serial = tbs.bytes(INTEGER)
        tbs.skip()
        val issuer = name(tbs.enter(SEQUENCE))
        val validity = tbs.enter(SEQUENCE)
        val times = Validity(validity.time(), validity.time())
        tbs.skip()
        val algorithm = tbs.enter(SEQUENCE).enter(SEQUENCE)
        val keyOid = algorithm.bytes(OID).hex()
        val params = if (algorithm.more() && algorithm.peek() == OID) algorithm.bytes(OID).hex() else null
        Info(
            serial = serial.dropWhile { it == 0.toByte() }.toByteArray().hex(":").uppercase(),
            issuer = issuer,
            validity = times,
            keyType = keyType(keyOid, params),
            fingerprint = der.toByteString().sha256().hex().chunked(2).joinToString(":").uppercase(),
        )
    }.getOrNull()

    /** "R11 · Let's Encrypt": the common name, then the organization. */
    private fun name(reader: Reader): String {
        val parts = mutableMapOf<String, String>()
        while (reader.more()) {
            val set = reader.enter(SET)
            while (set.more()) {
                val pair = set.enter(SEQUENCE)
                val oid = pair.bytes(OID).hex()
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

    // The tags of the DER values in a certificate.
    private const val INTEGER = 0x02
    private const val OID = 0x06
    private const val UTC_TIME = 0x17
    private const val GENERALIZED_TIME = 0x18
    private const val SEQUENCE = 0x30
    private const val SET = 0x31
    private const val VERSION = 0xA0

    // Object identifiers, as the hex of their DER content.
    private const val CN = "550403"
    private const val O = "55040a"
    private const val EC_KEY = "2a8648ce3d0201"
    private const val P256 = "2a8648ce3d030107"
    private const val P384 = "2b81040022"
    private const val RSA_KEY = "2a864886f70d010101"
    private const val ED25519 = "2b6570"

    private class Reader(private val der: ByteArray, private var pos: Int = 0, private val end: Int = der.size) {
        fun peek(): Int = der[pos].toInt() and 0xFF

        fun more(): Boolean = pos < end

        fun bytes(tag: Int): ByteArray {
            val (actual, length) = header()
            require(actual == tag)
            return der.copyOfRange(pos, pos + length).also { pos += length }
        }

        fun string(): String {
            val (_, length) = header()
            return der.decodeToString(pos, pos + length).also { pos += length }
        }

        private fun header(): Pair<Int, Int> {
            val tag = der[pos++].toInt() and 0xFF
            var length = der[pos++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val lengthBytes = length and 0x7F
                require(lengthBytes in 1..4)
                length = 0
                repeat(lengthBytes) { length = (length shl 8) or (der[pos++].toInt() and 0xFF) }
            }
            require(length >= 0 && pos + length <= end)
            return tag to length
        }

        fun enter(tag: Int): Reader {
            val (actual, length) = header()
            require(actual == tag)
            return Reader(der, pos, pos + length).also { pos += length }
        }

        fun skip() {
            val (_, length) = header()
            pos += length
        }

        fun time(): Long {
            val (tag, length) = header()
            val text = der.decodeToString(pos, pos + length)
            pos += length
            val digits = when (tag) {
                // UTCTime has a two-digit year: 50 to 99 are in the 1900s.
                UTC_TIME -> (if (text.take(2).toInt() >= 50) "19" else "20") + text
                GENERALIZED_TIME -> text
                else -> error("not a time")
            }
            require(digits.endsWith("Z") && digits.length >= 15)
            fun at(from: Int, count: Int) = digits.substring(from, from + count).toInt()
            return epochSeconds(at(0, 4), at(4, 2), at(6, 2), at(8, 2), at(10, 2), at(12, 2))
        }
    }

    /** The seconds since the epoch of a UTC date and time. The calendar is the Gregorian calendar. */
    fun epochSeconds(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        // The year starts in March, so the leap day is the last day of the year.
        val marchYear = if (month <= 2) year - 1 else year
        val era = (if (marchYear >= 0) marchYear else marchYear - 399) / 400
        val yearOfEra = marchYear - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        val days = era * 146097L + dayOfEra - 719468
        return days * 86400 + hour * 3600 + minute * 60 + second
    }
}
