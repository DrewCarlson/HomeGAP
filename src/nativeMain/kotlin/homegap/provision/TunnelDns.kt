package homegap.provision

import homegap.config.AppConfig
import homegap.model.PrivateResource

/**
 * DNS through the tunnel without an exit node (`cluster.tunnel_dns`). With "DNS over tunnel", a
 * Pangolin app sends its own lookups through the tunnel to its upstream DNS server; that address
 * must be a resource the device can reach, or every name but Pangolin's own fails to resolve.
 * One `cidr` resource per resolver, port 53 (TCP and UDP), on every node's exit site when there
 * are node exits (a device uses its nearest node, so lookups leave there and geo-steered answers
 * stay local), otherwise on every node's homelab site.
 */
object TunnelDns {
    const val PREFIX = "hg-dns-"

    fun niceId(resolver: String) = PREFIX + resolver.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** [exitSites]: node -> its exit site; [homelabSites]: node -> its homelab site. */
    fun resources(config: AppConfig, exitSites: Map<String, String>, homelabSites: Map<String, String>): Map<PrivateResource, List<String>> {
        val dns = config.cluster?.tunnelDns ?: return emptyMap()
        val viaExits = config.cluster.exitNodes.nodes && exitSites.isNotEmpty()
        val sites = config.nodes.mapNotNull { (if (viaExits) exitSites else homelabSites)[it.id] }
        if (sites.isEmpty()) return emptyMap()
        return dns.resolvers.distinct().associate { ip ->
            PrivateResource(
                niceId = niceId(ip),
                serviceKey = "dns",
                name = "DNS $ip",
                siteNiceId = sites.first(),
                destination = if (':' in ip) "$ip/128" else "$ip/32",
                alias = "",
                tcpPorts = "53",
                udpPorts = "53",
                roles = dns.roles,
                paused = false,
                mode = "cidr",
            ) to sites
        }
    }

    /** The resources the config wants, whether or not their sites exist yet (never deleted). */
    fun wanted(config: AppConfig): Set<String> = config.cluster?.tunnelDns?.resolvers.orEmpty().map(::niceId).toSet()
}
