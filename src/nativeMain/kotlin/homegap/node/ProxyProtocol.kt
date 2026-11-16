package homegap.node

/**
 * The header of PROXY protocol version 2 for a TCP connection. It tells the service the address
 * of the visitor, which the service cannot see behind the node.
 */
object ProxyProtocol {
    private val SIGNATURE = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A)

    /** Version 2, command PROXY. */
    private const val VERSION_COMMAND: Byte = 0x21
    private const val TCP_OVER_IPV4: Byte = 0x11
    private const val TCP_OVER_IPV6: Byte = 0x21
    private const val IPV4_LENGTH = 4
    private const val IPV6_LENGTH = 16
    private const val PORT_BYTES = 2
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff

    /** [address] holds 4 bytes for IPv4 or 16 bytes for IPv6. */
    class Endpoint(val address: ByteArray, val port: Int)

    /** The header for a connection from [source] to [destination]. Both have the same address family. */
    fun header(source: Endpoint, destination: Endpoint): ByteArray {
        val size = source.address.size
        require(size == destination.address.size) { "the two addresses are not of the same family" }
        require(size == IPV4_LENGTH || size == IPV6_LENGTH) { "an address has 4 or 16 bytes, not $size" }
        val family = if (size == IPV4_LENGTH) TCP_OVER_IPV4 else TCP_OVER_IPV6
        val length = 2 * size + 2 * PORT_BYTES
        return SIGNATURE + byteArrayOf(VERSION_COMMAND, family) + bigEndian(length) +
            source.address + destination.address + bigEndian(source.port) + bigEndian(destination.port)
    }

    /**
     * The bytes of the IPv4 address in [text] (`203.0.113.7`), or null. An IPv4 address that a
     * dual-stack socket reports as `::ffff:203.0.113.7` counts too.
     */
    fun ipv4(text: String): ByteArray? {
        val parts = text.substringAfterLast(':').split('.')
        val bytes = parts.mapNotNull { part -> part.toIntOrNull()?.takeIf { it in 0..BYTE_MASK }?.toByte() }
        return bytes.takeIf { parts.size == IPV4_LENGTH && it.size == IPV4_LENGTH }?.toByteArray()
    }

    private fun bigEndian(value: Int): ByteArray =
        byteArrayOf((value shr BYTE_BITS and BYTE_MASK).toByte(), (value and BYTE_MASK).toByte())
}
