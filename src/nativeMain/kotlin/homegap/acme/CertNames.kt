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

    fun of(config: AppConfig, desired: DesiredState): List<String> {
        val names = buildSet {
            for (n in config.nodes) add(n.managementHost(config.domain))
            config.clientHost?.let(::add)
            for (nd in desired.nodes.values) {
                nd.statusRedirect?.let { add(it.first) }
                nd.speedtestRedirect?.let { add(it.first) }
                for (r in nd.publicResources) {
                    if (r.mode == ResourceMode.HTTP && r.ssl && r.fullDomain != null) add(r.fullDomain)
                }
            }
        }.map { it.lowercase() }.toSet()
        val wildcards = names.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }
        // An ACME server refuses a name that a wildcard in the same order covers.
        val exact = names.filter { !it.startsWith("*.") && wildcards.none { w -> covers(w, it) } }
        return exact.sorted() + wildcards.sorted().map { "*.$it" }
    }

    /** True if `*.[parent]` matches [name]: exactly one label under [parent]. */
    fun covers(parent: String, name: String): Boolean =
        name.endsWith(".$parent") && '.' !in name.removeSuffix(".$parent")
}
