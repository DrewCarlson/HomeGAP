package homegap.platform

import homegap.clients.DnsWire
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.*

private const val DNS_PORT = "53"
private const val ANSWER_BUFFER_SIZE = 4096

/**
 * Asks one nameserver directly (UDP port 53) for [name]'s records of [type]. Returns null when
 * the server cannot be reached or gives no usable answer within [timeoutMs].
 */
suspend fun queryNameserver(server: String, name: String, type: Int, timeoutMs: Int = 2000): List<String>? =
    withContext(Dispatchers.IO) { queryBlocking(server, name, type, timeoutMs) }

@OptIn(ExperimentalForeignApi::class)
private fun queryBlocking(server: String, name: String, type: Int, timeoutMs: Int): List<String>? = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_DGRAM
    }
    val found = allocPointerTo<addrinfo>()
    if (getaddrinfo(server, DNS_PORT, hints.ptr, found.ptr) != 0) return null
    try {
        val address = found.value?.pointed ?: return null
        val fd = socket(address.ai_family, SOCK_DGRAM, 0)
        if (fd < 0) return null
        try {
            // A connected socket takes answers only from the address and port of the nameserver.
            // The id is not predictable, so a host off the path cannot forge an answer.
            if (connect(fd, address.ai_addr, address.ai_addrlen) != 0) return null
            val id = Crypto.randomBytes(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
            val query = DnsWire.encodeQuery(id, name, type)
            val sent = query.usePinned { send(fd, it.addressOf(0), query.size.convert(), 0) }
            if (sent < 0) return null
            val buffer = ByteArray(ANSWER_BUFFER_SIZE)
            val deadline = nowMillis() + timeoutMs
            while (true) {
                val remaining = (deadline - nowMillis()).toInt()
                if (remaining <= 0) return null
                val pollRequest = alloc<pollfd>().apply {
                    this.fd = fd
                    events = POLLIN.convert()
                }
                if (poll(pollRequest.ptr, 1u.convert(), remaining) <= 0) return null
                val received = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                if (received <= 0) return null
                // Ignore a stray datagram with another id, and wait for the answer to this query.
                DnsWire.parseAnswers(buffer.copyOf(received.toInt()), id, type)?.let { return it }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            close(fd)
        }
    } finally {
        freeaddrinfo(found.value)
    }
}
