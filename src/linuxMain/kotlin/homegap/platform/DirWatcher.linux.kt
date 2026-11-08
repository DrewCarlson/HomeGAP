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

actual fun watchDirectory(dir: String, accept: (fileName: String) -> Boolean): Flow<Unit> = flow {
    Files.mkdirs(dir)
    val fd = inotify_init1(IN_CLOEXEC)
    if (fd < 0) {
        // No inotify (unusual container restrictions): fall back to scanning.
        while (true) { delay(3.seconds); emit(Unit) }
    }
    try {
        val mask = (IN_CLOSE_WRITE or IN_MOVED_TO or IN_MOVED_FROM or IN_DELETE or IN_CREATE).toUInt()
        check(inotify_add_watch(fd, dir, mask) >= 0) { "inotify_add_watch($dir) failed" }
        val buf = ByteArray(4096)
        while (true) {
            // Blocks this IO thread until something changes; no wakeups while idle.
            val n = buf.usePinned { read(fd, it.addressOf(0), buf.size.convert()) }
            if (n < 0) { delay(1.seconds); continue }
            if (eventNames(buf, n.toInt()).any(accept)) emit(Unit)
        }
    } finally {
        close(fd)
    }
}.flowOn(Dispatchers.IO)

/** File names in a buffer of `struct inotify_event` records `(wd, mask, cookie, len, name[len])`. */
internal fun eventNames(buf: ByteArray, n: Int): List<String> {
    val names = mutableListOf<String>()
    var off = 0
    while (off + 16 <= n) {
        val len = (buf[off + 12].toInt() and 0xff) or ((buf[off + 13].toInt() and 0xff) shl 8) or
            ((buf[off + 14].toInt() and 0xff) shl 16) or ((buf[off + 15].toInt() and 0xff) shl 24)
        val start = off + 16
        if (len > 0 && start + len <= n) {
            var end = start
            while (end < start + len && buf[end] != 0.toByte()) end++
            names += buf.decodeToString(start, end)
        }
        off = start + len
    }
    return names
}
