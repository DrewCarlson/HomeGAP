package homegap.platform

import kotlinx.cinterop.*
import platform.posix.*

object Crypto {
    private const val ALNUM_LOWER = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val SETUP_TOKEN_LENGTH = 32
    private const val PASSWORD_TOKEN_LENGTH = 28

    /** One character of each class that the password policy of Pangolin asks for. */
    private const val PASSWORD_SUFFIX = "-Aa1!"

    /** Cryptographically secure random bytes from the kernel CSPRNG. */
    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        val fd = open("/dev/urandom", O_RDONLY)
        check(fd >= 0) { "cannot open /dev/urandom" }
        try {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < count) {
                    val received = read(fd, pinned.addressOf(offset), (count - offset).convert())
                    check(received > 0) { "short read from /dev/urandom" }
                    offset += received.toInt()
                }
            }
        } finally {
            close(fd)
        }
        return bytes
    }

    /** Unbiased random string over [alphabet] (rejection sampling). */
    fun token(length: Int, alphabet: String = ALNUM): String {
        val limit = 256 - (256 % alphabet.length)
        val text = StringBuilder(length)
        while (text.length < length) {
            for (byte in randomBytes(length * 2)) {
                val value = byte.toInt() and 0xff
                if (value < limit) text.append(alphabet[value % alphabet.length])
                if (text.length == length) break
            }
        }
        return text.toString()
    }

    /** Pangolin requires a setup token that matches ^[a-z0-9]{32}$. */
    fun setupToken(): String = token(SETUP_TOKEN_LENGTH, ALNUM_LOWER)

    /** A password that satisfies the policy of Pangolin (upper, lower, digit, symbol). */
    fun password(): String = token(PASSWORD_TOKEN_LENGTH) + PASSWORD_SUFFIX

    /** Whether [first] and [second] are equal, in time that does not depend on where they differ. */
    fun constantTimeEquals(first: String, second: String): Boolean {
        val firstBytes = first.encodeToByteArray()
        val secondBytes = second.encodeToByteArray()
        var diff = firstBytes.size xor secondBytes.size
        for (i in firstBytes.indices) {
            val other = if (secondBytes.isEmpty()) 0 else secondBytes[i % secondBytes.size].toInt()
            diff = diff or (firstBytes[i].toInt() xor other)
        }
        return diff == 0
    }

    fun fnv64(text: String): String {
        var hash = 0xcbf29ce484222325uL
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toULong() and 0xffuL)
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
