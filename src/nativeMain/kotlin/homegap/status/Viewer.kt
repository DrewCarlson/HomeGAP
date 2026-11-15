package homegap.status

import kotlinx.serialization.Serializable

/**
 * What the status page tells a visitor about the own connection. A Pangolin client gets no
 * address, because the request arrives from the private network of the homelab.
 */
@Serializable
data class PublicViewer(val vpn: Boolean, val address: String? = null)

object Viewers {
    private const val IPV4_BITS = 32
    private const val OCTET_MAX = 255
    private const val ADDRESS_MAX = 45
    private val ADDRESS = Regex("[0-9A-Fa-f:.]+")

    /** Loopback, link-local, private, shared (RFC 6598) and unspecified IPv4 ranges. */
    private val LOCAL_IPV4 = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
    )

    /** The unspecified address and the loopback address, and the starts of the unique local and link-local ranges. */
    private val LOCAL_IPV6 = setOf("::", "::1")
    private val LOCAL_IPV6_STARTS = listOf("fc", "fd", "fe8", "fe9", "fea", "feb")

    /**
     * The view of the visitor at [address]. [privateRange] is the private network of the front
     * door as `address/prefix`. A request from it comes from a Pangolin client. The result has an
     * address only if it is a public address: without the PROXY protocol, the proxy of the homelab
     * sees an address of Docker for each visitor.
     */
    fun of(address: String?, privateRange: String?): PublicViewer {
        val vpn = address != null && privateRange != null && inRange(address, privateRange)
        return PublicViewer(vpn, address?.takeIf { !vpn && isPublic(it) })
    }

    /** The address that the proxy of the homelab got the request from: the last one in [forwardedFor]. */
    fun address(peer: String, forwardedFor: String?): String =
        forwardedFor?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() } ?: peer

    fun inRange(address: String, range: String): Boolean {
        val prefix = range.substringAfter('/', "").toIntOrNull()?.takeIf { it in 0..IPV4_BITS } ?: return false
        val base = ipv4(range.substringBefore('/')) ?: return false
        val candidate = ipv4(address) ?: return false
        val shift = IPV4_BITS - prefix
        return candidate shr shift == base shr shift
    }

    private fun isPublic(address: String): Boolean {
        if (address.length > ADDRESS_MAX || !ADDRESS.matches(address)) return false
        if (':' in address) {
            return address !in LOCAL_IPV6 && LOCAL_IPV6_STARTS.none { address.startsWith(it, ignoreCase = true) }
        }
        return ipv4(address) != null && LOCAL_IPV4.none { inRange(address, it) }
    }

    private fun ipv4(text: String): Long? {
        val octets = text.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..OCTET_MAX }) return null
        return octets.fold(0L) { sum, octet -> (sum shl 8) or octet.toLong() }
    }
}
