package homegap.node

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.linux.eventfd
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.MSG_NOSIGNAL
import platform.posix.SHUT_WR
import platform.posix.SOCK_CLOEXEC
import platform.posix.SOCK_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_KEEPALIVE
import platform.posix.SO_REUSEADDR
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.accept
import platform.posix.fcntl
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.read
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.write
import kotlin.concurrent.AtomicReference

/**
 * One thread that waits in `epoll` for all the ports and all their connections. The number of
 * connections has no limit but the open files of the process, and an idle relay uses no CPU.
 */
actual fun tcpRelay(log: (String) -> Unit): TcpRelay = EpollRelay(log)

private class EpollRelay(private val log: (String) -> Unit) : TcpRelay {
    private val wanted = AtomicReference<Map<Int, String>>(emptyMap())
    private val poll = epoll_create1(EPOLL_CLOEXEC)
    private val wake = eventfd(0.convert(), (EFD_NONBLOCK or EFD_CLOEXEC).convert())

    /** What `epoll` reports for a file: a port that accepts, or one end of a connection. */
    private sealed interface Entry

    private class Port(val port: Int, var target: String) : Entry

    private class End(val fd: Int, val connection: Connection) : Entry {
        /** The events that `epoll` reports for the file now. */
        var interest = 0u

        /** The other side sent all its bytes. */
        var ended = false

        /** The other side is gone: `epoll` no longer has the file. */
        var gone = false

        /** The relay told this side that no more bytes come. */
        var closedForWrite = false
    }

    /** The bytes on their way in one direction. */
    private class Pipe {
        val bytes = ByteArray(PIPE_SIZE)
        var start = 0
        var end = 0
        val size get() = end - start
        val space get() = PIPE_SIZE - end
    }

    private class Connection(visitorFd: Int, homeFd: Int) {
        val visitor = End(visitorFd, this)
        val home = End(homeFd, this)
        val toHome = Pipe()
        val toVisitor = Pipe()
        var connecting = true
        var closed = false
    }

    private val entries = HashMap<Int, Entry>()
    private val failed = HashMap<Int, String>()

    /** The files that closed while the relay handles one set of events. A new file can get the same number. */
    private val closedNow = HashSet<Int>()

    override fun apply(listeners: Map<Int, String>) {
        wanted.value = listeners
        memScoped {
            val one = alloc<ULongVar>()
            one.value = 1u
            write(wake, one.ptr, sizeOf<ULongVar>().convert())
        }
    }

    override fun run() {
        check(poll >= 0 && wake >= 0) { "epoll is not available: ${error()}" }
        watch(wake, EPOLLIN, add = true)
        memScoped {
            val events = allocArray<epoll_event>(MAX_EVENTS)
            while (true) {
                // A port that is not free gets one more attempt after the pause. If not, the relay waits for events only.
                val count = epoll_wait(poll, events, MAX_EVENTS, if (failed.isEmpty()) FOREVER else RETRY_MS)
                if (count < 0) {
                    check(errno == EINTR) { "epoll_wait: ${error()}" }
                    continue
                }
                closedNow.clear()
                var woken = count == 0
                for (index in 0 until count) {
                    val fd = events[index].data.fd
                    val flags = events[index].events
                    if (fd == wake) {
                        drainWake()
                        woken = true
                    } else if (fd !in closedNow) {
                        when (val entry = entries[fd]) {
                            is Port -> accept(fd, entry)
                            is End -> handle(entry, flags)
                            null -> Unit
                        }
                    }
                }
                if (woken) applyWanted()
            }
        }
    }

    // ------------------------------------------------------------------ ports

    private fun applyWanted() {
        val listeners = wanted.value
        val open = entries.filterValues { it is Port }.mapValues { it.value as Port }
        for ((fd, port) in open) {
            val target = listeners[port.port]
            if (target == null) {
                forget(fd)
                log("port ${port.port}/tcp: closed")
            } else {
                port.target = target
            }
        }
        failed.keys.retainAll(listeners.keys)
        val served = open.values.map { it.port }.toSet()
        for ((port, target) in listeners) {
            if (port in served && port !in failed) continue
            val problem = open(port, target)
            if (problem == null) {
                failed.remove(port)
                log("port $port/tcp: open, with PROXY protocol, to $target")
            } else if (failed.put(port, problem) != problem) {
                log("port $port/tcp: $problem")
            }
        }
    }

    /** Opens [port]. Returns the problem, or null. */
    private fun open(port: Int, target: String): String? = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM or SOCK_NONBLOCK or SOCK_CLOEXEC, 0)
        if (fd < 0) return "socket: ${error()}"
        setOption(fd, SO_REUSEADDR)
        val address = alloc<sockaddr_in>()
        fill(address, ANY_ADDRESS, port)
        val bound = bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0 &&
            listen(fd, BACKLOG) == 0
        if (!bound) {
            val problem = error()
            close(fd)
            return problem
        }
        entries[fd] = Port(port, target)
        watch(fd, EPOLLIN, add = true)
        null
    }

    private fun accept(listener: Int, port: Port) = memScoped {
        val address = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>()
        while (true) {
            length.value = sizeOf<sockaddr_in>().convert()
            val visitor = accept(listener, address.ptr.reinterpret(), length.ptr)
            if (visitor < 0) break
            fcntl(visitor, F_SETFL, fcntl(visitor, F_GETFL) or O_NONBLOCK)
            fcntl(visitor, F_SETFD, FD_CLOEXEC)
            val local = alloc<sockaddr_in>()
            length.value = sizeOf<sockaddr_in>().convert()
            getsockname(visitor, local.ptr.reinterpret(), length.ptr)
            val home = connectHome(port.target)
            if (home < 0) {
                close(visitor)
                continue
            }
            setOption(visitor, SO_KEEPALIVE)
            val connection = Connection(visitor, home)
            val header = ProxyProtocol.header(endpoint(address), endpoint(local))
            header.copyInto(connection.toHome.bytes)
            connection.toHome.end = header.size
            entries[visitor] = connection.visitor
            entries[home] = connection.home
            watch(connection.visitor, add = true)
            watch(connection.home, add = true)
        }
    }

    /** Starts a connection to [target] (`address:port`). Returns the file, or -1. */
    private fun connectHome(target: String): Int = memScoped {
        val host = ProxyProtocol.ipv4(target.substringBeforeLast(':')) ?: return -1
        val port = target.substringAfterLast(':').toIntOrNull() ?: return -1
        val fd = socket(AF_INET, SOCK_STREAM or SOCK_NONBLOCK or SOCK_CLOEXEC, 0)
        if (fd < 0) return -1
        setOption(fd, SO_KEEPALIVE)
        val address = alloc<sockaddr_in>()
        fill(address, host, port)
        val started = connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0 ||
            errno == EINPROGRESS
        if (!started) {
            close(fd)
            return -1
        }
        fd
    }

    // ------------------------------------------------------------------ connections

    private fun handle(end: End, flags: UInt) {
        val connection = end.connection
        if (connection.closed) return
        if (flags and EPOLLERR != 0u) return drop(connection)
        if (end === connection.home && connection.connecting) {
            if (socketError(end.fd) != 0) return drop(connection)
            connection.connecting = false
        }
        if (flags and EPOLLHUP != 0u && !end.gone) {
            // Both directions of this side are closed. `epoll` reports that again and again, so it loses the file.
            epoll_ctl(poll, EPOLL_CTL_DEL, end.fd, null)
            end.gone = true
        }
        move(connection)
    }

    /** Moves all the bytes that can move now, then tells `epoll` what the connection waits for. */
    private fun move(connection: Connection) {
        val steps = listOf(
            Triple(connection.visitor, connection.toHome, connection.home),
            Triple(connection.home, connection.toVisitor, connection.visitor),
        )
        for ((from, pipe, to) in steps) {
            if (connection.connecting && from === connection.home) continue
            do {
                var progress = fill(from, pipe)
                if (!connection.closed && !connection.connecting) progress = drain(pipe, to) || progress
                if (connection.closed) return
            } while (progress)
            if (from.ended && pipe.size == 0 && !to.closedForWrite) {
                shutdown(to.fd, SHUT_WR)
                to.closedForWrite = true
            }
        }
        val done = connection.visitor.closedForWrite && connection.home.closedForWrite
        // A side that is gone can get no bytes, so the other direction has no reader.
        val lost = steps.any { (from, pipe, _) -> from.gone && from.ended && pipe.size == 0 }
        if (done || lost) return drop(connection)
        watch(connection.visitor)
        watch(connection.home)
    }

    /** Reads from [from] into [pipe]. Returns true if bytes came. */
    private fun fill(from: End, pipe: Pipe): Boolean {
        if (from.ended || pipe.space == 0) return false
        val count = pipe.bytes.usePinned { recv(from.fd, it.addressOf(pipe.end), pipe.space.convert(), 0) }.toInt()
        when {
            count > 0 -> pipe.end += count
            count == 0 -> from.ended = true
            errno != EAGAIN && errno != EINTR -> drop(from.connection)
        }
        return count > 0
    }

    /** Writes [pipe] to [to]. Returns true if bytes left. */
    private fun drain(pipe: Pipe, to: End): Boolean {
        if (pipe.size == 0) return false
        val count = pipe.bytes.usePinned {
            send(to.fd, it.addressOf(pipe.start), pipe.size.convert(), MSG_NOSIGNAL)
        }.toInt()
        if (count < 0) {
            if (errno != EAGAIN && errno != EINTR) drop(to.connection)
            return false
        }
        pipe.start += count
        if (pipe.size == 0) {
            pipe.start = 0
            pipe.end = 0
        }
        return count > 0
    }

    private fun drop(connection: Connection) {
        if (connection.closed) return
        connection.closed = true
        forget(connection.visitor.fd)
        forget(connection.home.fd)
    }

    private fun forget(fd: Int) {
        entries.remove(fd)
        closedNow += fd
        // The close also takes the file from `epoll`.
        close(fd)
    }

    // ------------------------------------------------------------------ epoll

    /** Tells `epoll` what [end] waits for: bytes to read while its pipe has space, and space to write while bytes wait. */
    private fun watch(end: End, add: Boolean = false) {
        if (end.gone) return
        val connection = end.connection
        val isHome = end === connection.home
        val incoming = if (isHome) connection.toVisitor else connection.toHome
        val outgoing = if (isHome) connection.toHome else connection.toVisitor
        val connectingHome = isHome && connection.connecting
        var interest = 0u
        if (!end.ended && incoming.space > 0 && !connectingHome) interest = interest or EPOLLIN
        if (connectingHome || (outgoing.size > 0 && !connection.connecting)) interest = interest or EPOLLOUT
        if (!add && interest == end.interest) return
        end.interest = interest
        watch(end.fd, interest, add)
    }

    private fun watch(fd: Int, interest: UInt, add: Boolean) = memScoped {
        val event = alloc<epoll_event>()
        event.events = interest
        event.data.fd = fd
        epoll_ctl(poll, if (add) EPOLL_CTL_ADD else EPOLL_CTL_MOD, fd, event.ptr)
    }

    private fun drainWake() = memScoped {
        val value = alloc<ULongVar>()
        read(wake, value.ptr, sizeOf<ULongVar>().convert())
    }

    // ------------------------------------------------------------------ sockets

    private fun setOption(fd: Int, option: Int) = memScoped {
        val on = alloc<IntVar>()
        on.value = 1
        setsockopt(fd, SOL_SOCKET, option, on.ptr, sizeOf<IntVar>().convert())
    }

    private fun socketError(fd: Int): Int = memScoped {
        val value = alloc<IntVar>()
        val length = alloc<socklen_tVar>()
        length.value = sizeOf<IntVar>().convert()
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, value.ptr, length.ptr) != 0) return errno
        value.value
    }

    /**
     * The port and the address of a socket address are in network byte order. Each Linux that
     * HomeGAP supports is little-endian, so the first byte is the lowest.
     */
    private fun fill(address: sockaddr_in, host: ByteArray, port: Int) {
        address.sin_family = AF_INET.convert()
        address.sin_port = ((port and BYTE_MASK shl BYTE_BITS) or (port shr BYTE_BITS)).toUShort()
        address.sin_addr.s_addr = host.foldRight(0u) { byte, value -> (value shl BYTE_BITS) or byte.toUByte().toUInt() }
    }

    private fun endpoint(address: sockaddr_in): ProxyProtocol.Endpoint {
        val port = address.sin_port.toInt()
        val host = address.sin_addr.s_addr
        return ProxyProtocol.Endpoint(
            address = ByteArray(ADDRESS_BYTES) { (host shr (it * BYTE_BITS)).toByte() },
            port = (port and BYTE_MASK shl BYTE_BITS) or (port shr BYTE_BITS),
        )
    }

    private fun error(): String = strerror(errno)?.toKString() ?: "error $errno"

    private companion object {
        val ANY_ADDRESS = ByteArray(ADDRESS_BYTES)
        const val ADDRESS_BYTES = 4
        const val PIPE_SIZE = 32 * 1024
        const val MAX_EVENTS = 256
        const val BACKLOG = 1024
        const val FOREVER = -1
        const val RETRY_MS = 5000
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xff
    }
}
