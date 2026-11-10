package homegap.tunnels

/**
 * The block of the tunnels container's hosts file that pins each connector's endpoint name to
 * the addresses the orchestrator publishes for it in DNS. Newt keeps the name (TLS and the
 * node's routing need it) but never asks public DNS for it, so a record that has not reached
 * every nameserver yet, or a resolver's cached answer, cannot send it to another node.
 *
 * Only the lines between [BEGIN] and [END] are ours; everything else in the file is kept as is.
 */
object HostsPins {
    const val BEGIN = "# BEGIN homegap tunnels: node addresses, as published in DNS"
    const val END = "# END homegap tunnels"

    /** One (address, host) line per published address, in a stable order. */
    fun entries(specs: Collection<TunnelSpec>): List<Pair<String, String>> =
        specs.sortedBy { it.nodeId }.flatMap { s -> s.addresses.distinct().map { it to s.host } }

    /** [current] with our block replaced by [entries] (or removed when there are none). */
    fun render(current: String, entries: List<Pair<String, String>>): String {
        val kept = buildList {
            var inBlock = false
            for (line in current.lines()) {
                when {
                    line == BEGIN -> inBlock = true
                    line == END -> inBlock = false
                    !inBlock -> add(line)
                }
            }
        }.dropLastWhile { it.isBlank() }
        val block = if (entries.isEmpty()) emptyList() else listOf(BEGIN) + entries.map { (ip, host) -> "$ip\t$host" } + END
        val lines = kept + block
        return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }
}
