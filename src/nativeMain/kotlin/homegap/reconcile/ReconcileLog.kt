package homegap.reconcile

import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.platform.nowMillis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/** Bounded in-memory log of reconcile actions, grouped by domain for the dashboard. */
class ReconcileLog(private val capacity: Int = 1000) {
    private val logger = KotlinLogging.logger("reconcile")
    private val lock = SynchronizedObject()
    private val entries = ArrayDeque<LogEntryDto>()
    private var changeCount = 0
    private var errorCount = 0
    private var seq = 0L

    /** Sequence number of the newest entry; observers are woken on every new entry. */
    val revision = MutableStateFlow(0L)

    fun info(domain: String, message: String, target: String? = null) = add(LogLevel.INFO, domain, message, target)
    fun change(domain: String, message: String, target: String? = null) = add(LogLevel.CHANGE, domain, message, target)
    fun warn(domain: String, message: String, target: String? = null) = add(LogLevel.WARN, domain, message, target)
    fun error(domain: String, message: String, target: String? = null) = add(LogLevel.ERROR, domain, message, target)

    private fun add(level: LogLevel, domain: String, message: String, target: String?) {
        val line = "[$domain]${target?.let { " [$it]" }.orEmpty()} $message"
        when (level) {
            LogLevel.ERROR -> logger.error { line }
            LogLevel.WARN -> logger.warn { line }
            LogLevel.CHANGE, LogLevel.INFO -> logger.info { line }
        }
        synchronized(lock) {
            if (level == LogLevel.ERROR) errorCount++
            if (level == LogLevel.CHANGE) changeCount++
            seq++
            entries.addLast(LogEntryDto(nowMillis(), domain, level, message, target, seq))
            while (entries.size > capacity) entries.removeFirst()
        }
        revision.value = seq
    }

    fun recent(limit: Int = 300, domain: String? = null): List<LogEntryDto> = synchronized(lock) {
        entries.toList().asReversed().filter { domain == null || it.domain == domain }.take(limit)
    }

    /** Entries newer than [afterSeq], oldest first (at most [limit]). */
    fun since(afterSeq: Long, limit: Int = 500): List<LogEntryDto> = synchronized(lock) {
        entries.filter { it.seq > afterSeq }.takeLast(limit)
    }

    /** Counters since the last call (used to summarise one reconcile pass). */
    fun drainCounters(): Pair<Int, Int> = synchronized(lock) {
        (changeCount to errorCount).also { changeCount = 0; errorCount = 0 }
    }
}
