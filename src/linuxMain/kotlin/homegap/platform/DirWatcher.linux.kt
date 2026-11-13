package homegap.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import platform.linux.*
import platform.posix.close
import platform.posix.read
import kotlin.time.Duration.Companion.seconds

private val SCAN_INTERVAL = 3.seconds
private val READ_RETRY_DELAY = 1.seconds
private const val EVENT_BUFFER_SIZE = 4096

/** The size of `struct inotify_event` without the name. */
private const val EVENT_HEADER_SIZE = 16

/** The offset of the `len` field in `struct inotify_event`. */
private const val NAME_LENGTH_OFFSET = 12

actual fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean): Flow<Unit> = flow {
    Files.mkdirs(dir)
    val fd = inotify_init1(IN_CLOEXEC)
    if (fd < 0) {
        // No inotify (unusual container restrictions): scan at an interval.
        while (true) {
            delay(SCAN_INTERVAL)
            emit(Unit)
        }
    }
    try {
        val mask = (IN_CLOSE_WRITE or IN_MOVED_TO or IN_MOVED_FROM or IN_DELETE or IN_CREATE).toUInt()
        check(inotify_add_watch(fd, dir, mask) >= 0) { "inotify_add_watch($dir) failed" }
        val buffer = ByteArray(EVENT_BUFFER_SIZE)
        while (true) {
            // Blocks this IO thread until something changes. An idle watch does not wake the thread.
            val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
            if (count < 0) {
                delay(READ_RETRY_DELAY)
                continue
            }
            if (eventNames(buffer, count.toInt()).any(accept)) emit(Unit)
        }
    } finally {
        close(fd)
    }
}.flowOn(Dispatchers.IO)

/**
 * File names in the first [size] bytes of [buffer], which holds `struct inotify_event` records
 * `(wd, mask, cookie, len, name[len])`.
 */
internal fun eventNames(buffer: ByteArray, size: Int): List<String> {
    val names = mutableListOf<String>()
    var offset = 0
    while (offset + EVENT_HEADER_SIZE <= size) {
        val length = littleEndianInt(buffer, offset + NAME_LENGTH_OFFSET)
        val start = offset + EVENT_HEADER_SIZE
        if (length > 0 && start + length <= size) {
            var end = start
            while (end < start + length && buffer[end] != 0.toByte()) end++
            names += buffer.decodeToString(start, end)
        }
        offset = start + length
    }
    return names
}

private fun littleEndianInt(buffer: ByteArray, at: Int): Int =
    (buffer[at].toInt() and 0xff) or
        ((buffer[at + 1].toInt() and 0xff) shl 8) or
        ((buffer[at + 2].toInt() and 0xff) shl 16) or
        ((buffer[at + 3].toInt() and 0xff) shl 24)
