package homegap.discovery

import kotlinx.serialization.Serializable

/** The addresses that an entry point of Traefik trusts for the address of the client. */
@Serializable
data class TraefikTrust(val insecure: Boolean = false, val trustedIPs: List<String> = emptyList()) {
    /** True if the entry point takes the address of the client from [source], an IPv4 address. */
    fun trusts(source: String): Boolean = insecure || trustedIPs.any { contains(it, source) }

    private fun contains(range: String, address: String): Boolean {
        val bits = range.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..IPV4_BITS } ?: return false
        val base = number(range.substringBefore('/')) ?: return false
        val wanted = number(address) ?: return false
        val mask = if (bits == 0) 0L else (ALL_BITS shl (IPV4_BITS - bits)) and ALL_BITS
        return base and mask == wanted and mask
    }

    private fun number(address: String): Long? {
        val parts = address.split('.').map { it.toIntOrNull()?.takeIf { part -> part in 0..BYTE_MAX } }
        if (parts.size != IPV4_PARTS || parts.any { it == null }) return null
        return parts.fold(0L) { sum, part -> sum * (BYTE_MAX + 1) + part!! }
    }
}

private const val IPV4_BITS = 32
private const val IPV4_PARTS = 4
private const val BYTE_MAX = 255
private const val ALL_BITS = 0xFFFFFFFFL

/** An entry point as the API of Traefik reports it, with the parts that decide the address of the client. */
@Serializable
data class TraefikEntryPoint(
    val name: String,
    /** For example `:443` or `0.0.0.0:443/tcp`. */
    val address: String = "",
    val proxyProtocol: TraefikTrust? = null,
    val forwardedHeaders: TraefikTrust? = null,
) {
    val port: Int? get() = address.substringAfterLast(':').substringBefore('/').toIntOrNull()
}

/**
 * Why the front door [door] does not get the address of the client from HomeGAP, or null if it
 * does. HomeGAP reaches the front door from [source], the address of the mesh container, and
 * [entryPoints] are the entry points of the front door. With `homegap.proxy_protocol`, the entry
 * point must trust [source] for the PROXY protocol. For a name that a node terminates, it must
 * trust [source] for the forwarded headers.
 */
fun clientAddressIssue(door: ServiceSpec, entryPoints: List<TraefikEntryPoint>, source: String): String? {
    val entry = entryPoints.firstOrNull { it.port == door.port } ?: return null
    val (label, setting, trust) = when {
        door.proxyProtocol -> Triple(Labels.PROXY_PROTOCOL, "proxyProtocol.trustedIPs", entry.proxyProtocol)
        door.tls == TlsMode.TERMINATE -> Triple(Labels.TLS, "forwardedHeaders.trustedIPs", entry.forwardedHeaders)
        else -> return null
    }
    if (trust?.trusts(source) == true) return null
    return "$label: the entry point '${entry.name}' of ${door.containerName} does not trust $source ($setting), " +
        "so its services get the address of HomeGAP and not the address of the client"
}
