package homegap.provision

import homegap.config.AppConfig
import homegap.model.PrivateResource

/**
 * DNS through the tunnel without an exit node (`cluster.tunnel_dns`). With "DNS over tunnel", a
 * Pangolin app sends its own lookups through the tunnel to its upstream DNS server. That address
 * must be a resource that the device can reach. If it is not, only the names of Pangolin resolve.
 *
 * Each resolver gets one `cidr` resource for port 53 (TCP and UDP). If there are node exits, the
 * resource is on the exit site of each node. A device uses its nearest node, so the lookups go
 * out there and the answers fit the location of the device. If there are no node exits, the
 * resource is on the homelab site of each node.
 */
object TunnelDns {
    const val PREFIX = "hg-dns-"

    fun niceId(resolver: String) = PREFIX + resolver.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** [exitSites] gives the exit site of each node. [homelabSites] gives the homelab site of each node. */
    fun resources(
        config: AppConfig,
        exitSites: Map<String, String>,
        homelabSites: Map<String, String>,
    ): Map<PrivateResource, List<String>> {
        val dns = config.cluster?.tunnelDns ?: return emptyMap()
        val throughExits = config.cluster.exitNodes.nodes && exitSites.isNotEmpty()
        val siteOfNode = if (throughExits) exitSites else homelabSites
        val sites = config.nodes.mapNotNull { siteOfNode[it.id] }
        if (sites.isEmpty()) return emptyMap()
        return dns.resolvers.distinct().associate { resolver ->
            PrivateResource(
                niceId = niceId(resolver),
                serviceKey = "dns",
                name = "DNS $resolver",
                siteNiceId = sites.first(),
                destination = if (':' in resolver) "$resolver/128" else "$resolver/32",
                alias = "",
                tcpPorts = "53",
                udpPorts = "53",
                roles = dns.roles,
                paused = false,
                mode = "cidr",
            ) to sites
        }
    }

    /** The resources that the config wants, also if their sites do not exist yet. */
    fun wanted(config: AppConfig): Set<String> =
        config.cluster?.tunnelDns?.resolvers.orEmpty().map(::niceId).toSet()
}
