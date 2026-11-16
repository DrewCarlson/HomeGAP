package homegap.mesh

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AI_PASSIVE
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Answers the liveness probe of the apps. An app sends 13 bytes through its tunnel to the peer:
 * a fixed number, the type 1 and a time. The peer sends the same bytes back with the type 2. The
 * app shows a peer as connected only after this answer.
 */
object LivenessResponder {
    const val SIZE = 13
    private const val TYPE_AT = 4
    private const val REQUEST: Byte = 1
    private const val RESPONSE: Byte = 2
    private val MAGIC = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    private const val BUFFER_SIZE = 64

    /** The answer to the datagram in the first [length] bytes of [packet], or null if it is not a probe. */
    fun answer(packet: ByteArray, length: Int): ByteArray? {
        if (length < SIZE || packet[TYPE_AT] != REQUEST) return null
        if (!MAGIC.indices.all { packet[it] == MAGIC[it] }) return null
        return packet.copyOf(SIZE).also { it[TYPE_AT] = RESPONSE }
    }

    /**
     * Answers probes on the UDP port [port] of each address. It returns only if the port is not
     * free. The firewall of the namespace accepts the port only from the VPN interface.
     */
    fun serve(port: Int): Unit = memScoped {
        val hints = alloc<addrinfo>().apply {
            ai_family = AF_INET
            ai_socktype = SOCK_DGRAM
            ai_flags = AI_PASSIVE
        }
        val found = allocPointerTo<addrinfo>()
        check(getaddrinfo(null, port.toString(), hints.ptr, found.ptr) == 0) { "no address for the UDP port $port" }
        val address = found.value!!.pointed
        val fd = socket(address.ai_family, SOCK_DGRAM, 0)
        try {
            check(fd >= 0 && bind(fd, address.ai_addr, address.ai_addrlen) == 0) { "the UDP port $port is not free" }
            val buffer = ByteArray(BUFFER_SIZE)
            val from = alloc<sockaddr_storage>()
            val fromLength = alloc<socklen_tVar>()
            while (true) {
                fromLength.value = sizeOf<sockaddr_storage>().convert()
                val received = buffer.usePinned {
                    recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, from.ptr.reinterpret(), fromLength.ptr)
                }.toInt()
                val reply = answer(buffer, received) ?: continue
                reply.usePinned {
                    sendto(fd, it.addressOf(0), reply.size.convert(), 0, from.ptr.reinterpret(), fromLength.value)
                }
            }
        } finally {
            if (fd >= 0) close(fd)
            freeaddrinfo(found.value)
        }
    }
}
