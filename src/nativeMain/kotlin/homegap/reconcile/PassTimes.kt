package homegap.reconcile

import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The time that each part of a reconcile pass takes, in milliseconds. */
class PassTimes {
    private val lock = SynchronizedObject()
    private val millis = mutableMapOf<String, Long>()

    /** Runs [block] and adds its time to [part]. */
    inline fun <T> measure(part: String, block: () -> T): T {
        val start = nowMillis()
        try {
            return block()
        } finally {
            add(part, nowMillis() - start)
        }
    }

    fun add(part: String, elapsed: Long) = synchronized(lock) {
        millis[part] = (millis[part] ?: 0) + elapsed
    }

    /** The parts and their times, as fields of a log entry. The times start again from zero. */
    fun drain(): Map<String, String> = synchronized(lock) {
        val fields = millis.entries.associate { (part, elapsed) -> "${FIELD_PREFIX}$part" to elapsed.toString() }
        millis.clear()
        fields
    }

    companion object {
        const val FIELD_PREFIX = "ms_"
    }
}
