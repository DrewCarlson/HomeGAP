package homegap.speedtest

import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Counts requests for each key in windows of [windowMillis]. A key gets at most [limit] requests
 * in a window. The public listener uses it for the pages that need no token.
 */
class RateLimiter(private val limit: Int, private val windowMillis: Long, private val now: () -> Long = ::nowMillis) {
    private val lock = SynchronizedObject()
    private val counts = mutableMapOf<String, Int>()
    private var window = 0L

    /** Counts one request for [key]. False if the key is over its limit for this window. */
    fun allow(key: String): Boolean = synchronized(lock) {
        val current = now() / windowMillis
        if (current != window) {
            window = current
            counts.clear()
        }
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        count <= limit
    }
}
