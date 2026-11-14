package homegap.tunnels

/**
 * The address of the Docker host, as a container reaches it: the gateway of the default route of
 * the container. The tunnel connectors open their connections from the `tunnels` container, so
 * this is the address of a service on the homelab host itself.
 */
object HostGateway {
    const val ROUTE_TABLE = "/proc/net/route"

    private const val DESTINATION = 1
    private const val GATEWAY = 2
    private const val FLAGS = 3
    private const val DEFAULT_DESTINATION = "00000000"
    private const val FLAG_GATEWAY = 0x2
    private const val HEX_ADDRESS_LENGTH = 8

    /**
     * Reads the gateway of the default route from [routeTable], the text of `/proc/net/route`.
     * The kernel writes each address as eight hex digits with the last byte of the address first.
     */
    fun parse(routeTable: String): String? =
        routeTable.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size > FLAGS && it[DESTINATION] == DEFAULT_DESTINATION }
            .filter { (it[FLAGS].toIntOrNull(radix = 16) ?: 0) and FLAG_GATEWAY != 0 }
            .firstNotNullOfOrNull { address(it[GATEWAY]) }

    private fun address(hex: String): String? {
        if (hex.length != HEX_ADDRESS_LENGTH) return null
        val bytes = hex.chunked(2).map { it.toIntOrNull(radix = 16) ?: return null }
        return bytes.reversed().joinToString(".")
    }
}
