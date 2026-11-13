package homegap.tunnels

import homegap.provision.OutsideCalls
import homegap.setup.SetupScripts

/**
 * The block of the tunnels container's hosts file that pins each connector's endpoint name to
 * the addresses the orchestrator publishes for it in DNS. Newt keeps the name (TLS and the
 * node's routing need it) but never asks public DNS for it. Thus a record that is not on every
 * nameserver yet, or a cached answer of a resolver, cannot send Newt to another node.
 *
 * Only the lines between [BEGIN] and [END] belong to HomeGAP. All other lines of the file stay
 * as they are.
 */
object HostsPins {
    const val BEGIN = "# BEGIN homegap tunnels: node addresses, as published in DNS"
    const val END = "# END homegap tunnels"
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

    /**
     * One (address, host) line per published address, in a stable order, then Newt's version
     * check sent nowhere (see [OutsideCalls]).
     */
    fun entries(specs: Collection<TunnelSpec>): List<Pair<String, String>> {
        val pins = specs.sortedBy { it.nodeId }
            .filter { HOST.matches(it.host) }
            .flatMap { spec -> spec.addresses.distinct().filter(SetupScripts::isIpAddress).map { it to spec.host } }
        return pins + OutsideCalls.NEWT.map { OutsideCalls.NOWHERE to it }
    }

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
        val block = buildList {
            if (entries.isEmpty()) return@buildList
            add(BEGIN)
            entries.mapTo(this) { (address, host) -> "$address\t$host" }
            add(END)
        }
        val lines = kept + block
        return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
    }
}
