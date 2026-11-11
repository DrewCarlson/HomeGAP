package homegap.reconcile

import homegap.api.ApiJson
import homegap.api.LogEntryDto
import homegap.api.LogLevel
import homegap.platform.Files
import homegap.platform.nowMillis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Bounded log of reconcile actions, grouped by domain for the dashboard. With a [path] it is kept
 * on disk too ([save] after each pass), so the Activity page keeps its history across restarts.
 */
class ReconcileLog(
    private val capacity: Int = 10_000,
    private val path: String? = null,
) {
    private val logger = KotlinLogging.logger("reconcile")
    private val lock = SynchronizedObject()
    private val entries = ArrayDeque<LogEntryDto>()
    private var changeCount = 0
    private var errorCount = 0
    private var seq = 0L

    private var savedSeq = 0L

    /** When a change was last logged for this DNS record name (`record` field), kept across the log's size. */
    private val recordChanges = mutableMapOf<String, Long>()

    /** Sequence number of the newest entry; observers are woken on every new entry. */
    val revision = MutableStateFlow(0L)

    init {
        path?.let(Files::readTextOrNull)?.let { text ->
            runCatching { ApiJson.decodeFromString(ListSerializer(LogEntryDto.serializer()), text) }.getOrNull()
        }?.let { saved ->
            entries.addAll(saved.takeLast(capacity))
            saved.forEach { entry -> entry.fields["record"]?.let { recordChanges[it] = entry.time } }
            seq = saved.lastOrNull()?.seq ?: 0L
            savedSeq = seq
            revision.value = seq
        }
    }

    /** Writes the log to disk if anything was added since the last save. */
    fun save() {
        val target = path ?: return
        val snapshot = synchronized(lock) {
            if (seq == savedSeq) return
            savedSeq = seq
            entries.toList()
        }
        runCatching {
            Files.writeAtomic(target, ApiJson.encodeToString(ListSerializer(LogEntryDto.serializer()), snapshot), "600".toInt(8))
        }.onFailure { logger.warn { "could not save the activity log to $target: ${it.message}" } }
    }

    /** The pass being run; entries written meanwhile carry it. Null between passes. */
    @kotlin.concurrent.Volatile
    var pass: Long? = null

    /** The number the next pass gets: one more than the last pass in the log. */
    fun nextPass(): Long = synchronized(lock) { (entries.lastOrNull { it.pass != null }?.pass ?: 0L) + 1 }

    fun info(domain: String, message: String, target: String? = null, fields: Map<String, String> = emptyMap()) =
        add(LogLevel.INFO, domain, message, target, fields)
    fun change(domain: String, message: String, target: String? = null, fields: Map<String, String> = emptyMap()) =
        add(LogLevel.CHANGE, domain, message, target, fields)


    fun lastRecordChange(name: String): Long? = synchronized(lock) { recordChanges[name] }
    fun warn(domain: String, message: String, target: String? = null) = add(LogLevel.WARN, domain, message, target)
    fun error(domain: String, message: String, target: String? = null) = add(LogLevel.ERROR, domain, message, target)

    private fun add(level: LogLevel, domain: String, message: String, target: String?, fields: Map<String, String> = emptyMap()) {
        val line = "[$domain]${target?.let { " [$it]" }.orEmpty()} $message"
        when (level) {
            LogLevel.ERROR -> logger.error { line }
            LogLevel.WARN -> logger.warn { line }
            LogLevel.CHANGE, LogLevel.INFO -> logger.info { line }
        }
        synchronized(lock) {
            if (level == LogLevel.ERROR) errorCount++
            if (level == LogLevel.CHANGE) changeCount++
            fields["record"]?.let { recordChanges[it] = nowMillis() }
            seq++
            entries.addLast(
                LogEntryDto(
                    time = nowMillis(),
                    domain = domain,
                    level = level,
                    message = message,
                    target = target,
                    seq = seq,
                    pass = pass,
                    fields = fields,
                )
            )
            while (entries.size > capacity) entries.removeFirst()
        }
        revision.value = seq
    }

    /**
     * Newest first: at most [limit] entries older than [beforeSeq] (all when null), of [domain]
     * and [level] when given, whose message or target contains [query].
     */
    fun recent(
        limit: Int = 300,
        domain: String? = null,
        beforeSeq: Long? = null,
        level: LogLevel? = null,
        query: String? = null,
    ): List<LogEntryDto> = synchronized(lock) {
        val text = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        entries.asReversed().asSequence()
            .filter { beforeSeq == null || it.seq < beforeSeq }
            .filter { domain == null || it.domain == domain }
            .filter { level == null || it.level == level }
            .filter { entry ->
                text == null || text in entry.message.lowercase() || entry.target?.lowercase()?.contains(text) == true
            }
            .take(limit)
            .toList()
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
