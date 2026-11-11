package homegap.platform

import kotlinx.cinterop.*
import okio.ByteString.Companion.encodeUtf8
import platform.posix.*

object Crypto {
    private const val ALNUM_LOWER = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** Cryptographically secure random bytes from the kernel CSPRNG. */
    fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        val fd = open("/dev/urandom", O_RDONLY)
        check(fd >= 0) { "cannot open /dev/urandom" }
        try {
            out.usePinned { p ->
                var off = 0
                while (off < n) {
                    val r = read(fd, p.addressOf(off), (n - off).convert())
                    check(r > 0) { "short read from /dev/urandom" }
                    off += r.toInt()
                }
            }
        } finally {
            close(fd)
        }
        return out
    }

    /** Unbiased random string over [alphabet] (rejection sampling). */
    fun token(length: Int, alphabet: String = ALNUM): String {
        val limit = 256 - (256 % alphabet.length)
        val sb = StringBuilder(length)
        while (sb.length < length) {
            for (b in randomBytes(length * 2)) {
                val v = b.toInt() and 0xff
                if (v < limit) sb.append(alphabet[v % alphabet.length])
                if (sb.length == length) break
            }
        }
        return sb.toString()
    }

    /** Pangolin requires setup tokens matching ^[a-z0-9]{32}$. */
    fun setupToken(): String = token(32, ALNUM_LOWER)

    /** Password satisfying Pangolin's policy (upper, lower, digit, symbol). */
    fun password(): String = token(28) + "-Aa1!"

    /** SHA-256 of [s] (UTF-8) as lowercase hex. */
    fun sha256Hex(s: String): String = s.encodeUtf8().sha256().hex()

    /** Whether [a] and [b] are equal, in time that does not depend on where they differ. */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        var diff = x.size xor y.size
        for (i in x.indices) diff = diff or (x[i].toInt() xor (if (y.isEmpty()) 0 else y[i % y.size].toInt()))
        return diff == 0
    }

    fun fnv64(s: String): String {
        var h = 0xcbf29ce484222325uL
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toULong() and 0xffuL)
            h *= 0x100000001b3uL
        }
        return h.toString(16).padStart(16, '0')
    }
}
