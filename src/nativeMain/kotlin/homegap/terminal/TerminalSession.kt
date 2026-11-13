package homegap.terminal

import homegap.platform.Pty
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * The output of a shell: the last [capacity] bytes, each with its position in the whole output.
 * A browser that attaches gets these bytes first, so it shows what the shell printed before.
 */
class OutputBuffer(private val capacity: Int) {
    private val bytes = ByteArray(capacity)

    /** The number of bytes that the shell has printed so far. */
    var end = 0L
        private set

    /** The position of the oldest byte that the buffer still holds. */
    val start: Long get() = (end - capacity).coerceAtLeast(0)

    fun append(data: ByteArray, length: Int) {
        for (i in 0 until length) {
            bytes[((end + i) % capacity).toInt()] = data[i]
        }
        end += length
    }

    /** At most [max] bytes from position [from]. A position that the buffer lost moves to [start]. */
    fun read(from: Long, max: Int): Pair<Long, ByteArray> {
        val at = from.coerceIn(start, end)
        val length = (end - at).coerceAtMost(max.toLong()).toInt()
        return at to ByteArray(length) { bytes[((at + it) % capacity).toInt()] }
    }
}

/**
 * One shell on a node. A thread reads its output into an [OutputBuffer]. One browser at a time
 * is attached: it reads from the buffer and reports what it has drawn.
 *
 * While the attached browser is more than [HIGH_WATER] bytes behind, the session does not read
 * from the shell, so the shell waits. A program that prints without end then cannot fill the
 * memory of the browser.
 */
class TerminalSession(
    val id: String,
    val nodeId: String,
    private val pty: Pty,
    scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) {
    val startedAt = now()
    private val lock = SynchronizedObject()
    private val buffer = OutputBuffer(BUFFER_BYTES)

    /** Changes with each new output, and when the shell ends. A browser waits on it. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    @Volatile
    var exitCode: Int? = null
        private set

    /** The connection that shows this session. A new one takes the place of the last. */
    @Volatile
    private var attachment: Any? = null
    val attached: Boolean get() = attachment != null

    @Volatile
    private var acked = 0L

    @Volatile
    var lastActivity = now()
        private set

    init {
        scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(READ_BYTES)
            while (true) {
                while (attached && buffer.end - acked > HIGH_WATER && exitCode == null) {
                    delay(BACKPRESSURE_POLL)
                }
                val count = pty.read(chunk)
                if (count < 0) break
                synchronized(lock) { buffer.append(chunk, count) }
                _changes.value++
            }
            // The read ends a moment before the child is gone.
            var code = pty.exitCode()
            repeat(EXIT_POLLS) {
                if (code == null) {
                    delay(EXIT_POLL)
                    code = pty.exitCode()
                }
            }
            exitCode = code ?: -1
            _changes.value++
        }
    }

    /** Makes [connection] the one that shows this session. Returns the position to read from. */
    fun attach(connection: Any): Long = synchronized(lock) {
        attachment = connection
        lastActivity = now()
        acked = buffer.start
        buffer.start
    }

    fun detach(connection: Any) = synchronized(lock) {
        if (attachment === connection) {
            attachment = null
            lastActivity = now()
        }
    }

    /** True while [connection] is the one that shows this session. */
    fun shows(connection: Any): Boolean = attachment === connection

    /** The output from position [from], at most [max] bytes, with the position that it starts at. */
    fun output(from: Long, max: Int = OUTPUT_BYTES): Pair<Long, ByteArray> =
        synchronized(lock) { buffer.read(from, max) }

    fun ack(offset: Long) {
        if (offset > acked) {
            acked = offset
        }
    }

    suspend fun input(bytes: ByteArray) {
        lastActivity = now()
        if (exitCode == null) {
            withContext(Dispatchers.IO) { pty.write(bytes) }
        }
    }

    fun resize(rows: Int, columns: Int) {
        if (exitCode == null) {
            pty.resize(rows, columns)
        }
    }

    /** Ends the shell. */
    suspend fun close() = withContext(Dispatchers.IO) { pty.close() }

    companion object {
        const val BUFFER_BYTES = 256 * 1024

        /** Less than the buffer, so the buffer never drops output that the attached browser did not get. */
        const val HIGH_WATER = BUFFER_BYTES / 2L

        private const val READ_BYTES = 16 * 1024
        private const val OUTPUT_BYTES = 32 * 1024
        private const val EXIT_POLLS = 40
        private val EXIT_POLL = 50.milliseconds
        private val BACKPRESSURE_POLL = 20.milliseconds
    }
}
