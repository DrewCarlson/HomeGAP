package homegap.acme

import homegap.config.AppConfig
import homegap.model.DesiredState
import homegap.model.ResourceMode

/**
 * The names on the certificate of the nodes: each name for which a node ends TLS itself. A
 * passthrough name is never in the list, so a node holds no certificate for it.
 */
object CertNames {
    /** Let's Encrypt accepts at most this many names on one certificate. */
    const val MAX = 100

    /**
     * A name and why it is on the certificate. [reason] is `management`, `client`, `status`,
     * `speedtest`, `service` or `frontdoor`. [subject] is the node id or the service key.
     */
    data class Entry(val name: String, val reason: String, val subject: String?)

    fun of(config: AppConfig, desired: DesiredState): List<String> = entries(config, desired).map { it.name }

    /** The names in the order of [of], each with the first reason that puts it there. */
    fun entries(config: AppConfig, desired: DesiredState): List<Entry> {
        val found = linkedMapOf<String, Entry>()
        fun add(name: String, reason: String, subject: String?) {
            val key = name.lowercase()
            if (key !in found) found[key] = Entry(key, reason, subject)
        }
        for (node in config.nodes) {
            add(node.managementHost(config.domain), "management", node.id)
        }
        config.clientHost?.let { add(it, "client", null) }
        for (desiredNode in desired.nodes.values) {
            desiredNode.statusRedirect?.let { add(it.first, "status", null) }
            desiredNode.speedtestRedirect?.let { add(it.first, "speedtest", null) }
            desiredNode.publicResources.forEach { resource ->
                val name = resource.fullDomain
                if (resource.mode == ResourceMode.HTTP && resource.ssl && name != null) {
                    val reason = if (resource.serviceKey == desired.frontDoor?.key) "frontdoor" else "service"
                    add(name, reason, resource.serviceKey)
                }
            }
        }
        val names = found.keys
        val wildcards = names.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }
        // An ACME server refuses a name that a wildcard in the same order covers.
        val exact = names.filter { name -> !name.startsWith("*.") && wildcards.none { covers(it, name) } }
        return (exact.sorted() + wildcards.sorted().map { "*.$it" }).map { found.getValue(it) }
    }

    /**
     * The names that the nodes forward to the homelab and do not end TLS for. The homelab holds
     * their certificates. A wildcard route is not in the list: no single name stands for it.
     */
    fun passthrough(desired: DesiredState): List<String> =
        desired.nodes.values
            .flatMap { desiredNode -> desiredNode.sniRoutes.flatMap { it.exactHosts } }
            .map { it.lowercase() }
            .distinct()
            .sorted()

    /** True if `*.[parent]` matches [name]: exactly one label under [parent]. */
    fun covers(parent: String, name: String): Boolean =
        name.endsWith(".$parent") && '.' !in name.removeSuffix(".$parent")
}
