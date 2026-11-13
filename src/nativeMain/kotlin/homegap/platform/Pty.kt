@file:OptIn(ExperimentalForeignApi::class)

package homegap.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.SIGHUP
import platform.posix.SIGKILL
import platform.posix.TIOCSCTTY
import platform.posix.TIOCSWINSZ
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.grantpt
import platform.posix.ioctl
import platform.posix.open
import platform.posix.posix_openpt
import platform.posix.ptsname
import platform.posix.setsid
import platform.posix.strerror
import platform.posix.unlockpt
import platform.posix.waitpid
import platform.posix.winsize

/**
 * A child process on a pseudo-terminal, for an interactive program such as `ssh -tt`. The child
 * is the leader of a new session, and the terminal is its standard input, output and error.
 *
 * [read] blocks, so call it on a thread that can wait. One reader and one writer at a time.
 */
class Pty private constructor(val pid: Int, private val master: Int) {
    @kotlin.concurrent.Volatile
    private var closed = false

    /** Reads output of the child into [buffer]. Returns the number of bytes, or -1 when the child is gone. */
    fun read(buffer: ByteArray): Int {
        while (!closed) {
            val n = buffer.usePinned { platform.posix.read(master, it.addressOf(0), buffer.size.toULong()) }.toInt()
            if (n > 0) return n
            // A terminal with no process on its other end gives EIO, not an end of file.
            if (n == 0 || (errno != EINTR && errno != EAGAIN)) return -1
        }
        return -1
    }

    /** Sends [bytes] to the child as typed input. */
    fun write(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size && !closed) {
            val n = bytes.usePinned { platform.posix.write(master, it.addressOf(sent), (bytes.size - sent).toULong()) }.toInt()
            if (n > 0) sent += n else if (errno != EINTR && errno != EAGAIN) return
        }
    }

    /** Tells the terminal its size. The child gets the signal for a changed window. */
    fun resize(rows: Int, columns: Int) {
        if (!closed) setSize(master, rows, columns)
    }

    /** The exit code of the child, or null while it runs. A child that a signal ended gives 128 plus the signal. */
    fun exitCode(): Int? = memScoped {
        exit?.let { return it }
        val status = alloc<IntVar>()
        if (waitpid(pid, status.ptr, WNOHANG) != pid) return null
        val s = status.value
        (if (s and 0x7f == 0) (s shr 8) and 0xff else 128 + (s and 0x7f)).also { exit = it }
    }

    private var exit: Int? = null

    /** Ends the child and frees the terminal. A blocked [read] returns -1. */
    fun close() {
        if (closed) return
        closed = true
        if (exitCode() == null) platform.posix.kill(pid, SIGHUP)
        platform.posix.close(master)
        repeat(20) {
            if (exitCode() != null) return
            platform.posix.usleep(50_000u)
        }
        platform.posix.kill(pid, SIGKILL)
        waitpid(pid, null, 0)
    }

    companion object {
        /**
         * Starts [argv] on a new terminal of [rows] by [columns]. [env] is added to the environment
         * of this process, for the child only.
         */
        fun spawn(argv: List<String>, rows: Int, columns: Int, env: Map<String, String> = emptyMap()): Pty = memScoped {
            require(argv.isNotEmpty()) { "empty command" }
            val master = posix_openpt(O_RDWR or O_NOCTTY)
            check(master >= 0) { "cannot open a terminal: ${error()}" }
            try {
                check(grantpt(master) == 0 && unlockpt(master) == 0) { "cannot prepare the terminal: ${error()}" }
                val name = (ptsname(master)?.toKString() ?: error("the terminal has no name: ${error()}")).cstr.ptr
                fcntl(master, F_SETFD, FD_CLOEXEC)
                // The child sets the size on its side: macOS drops a size set before that side is open.
                val size = size(rows, columns)

                // `env` gives the child its extra variables, so the child sets nothing itself.
                val command = if (env.isEmpty()) argv else listOf("env") + env.map { (k, v) -> "$k=$v" } + argv
                val args = allocArray<CPointerVar<ByteVar>>(command.size + 1)
                command.forEachIndexed { i, a -> args[i] = a.cstr.ptr }
                args[command.size] = null
                val program = command.first().cstr.ptr

                // Between fork and exec the child makes system calls only: other threads of this
                // process do not exist there, and a lock that one of them held stays locked.
                val pid = fork()
                check(pid >= 0) { "cannot start ${argv.first()}: ${error()}" }
                if (pid == 0) {
                    setsid()
                    val slave = open(name, O_RDWR)
                    if (slave < 0) _exit(126)
                    ioctl(slave, TIOCSCTTY.toULong(), 0)
                    ioctl(slave, TIOCSWINSZ.toULong(), size.ptr)
                    dup2(slave, 0)
                    dup2(slave, 1)
                    dup2(slave, 2)
                    if (slave > 2) platform.posix.close(slave)
                    execvp(program, args)
                    _exit(127)
                }
                Pty(pid, master)
            } catch (e: Throwable) {
                platform.posix.close(master)
                throw e
            }
        }

        private fun setSize(fd: Int, rows: Int, columns: Int) = memScoped {
            ioctl(fd, TIOCSWINSZ.toULong(), size(rows, columns).ptr)
        }

        private fun kotlinx.cinterop.MemScope.size(rows: Int, columns: Int): winsize {
            val size = alloc<winsize>()
            size.ws_row = rows.coerceIn(1, 1000).toUShort()
            size.ws_col = columns.coerceIn(1, 1000).toUShort()
            size.ws_xpixel = 0u
            size.ws_ypixel = 0u
            return size
        }

        private fun error() = strerror(errno)?.toKString() ?: "error $errno"
    }
}
