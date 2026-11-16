package homegap.config

import homegap.VERSION
import homegap.platform.env
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The configuration of the controller, which the operator writes as one YAML file.
 *
 * The file holds no secret. A field that ends in `_env` names an environment variable, and a field
 * that ends in `_file` names a mounted file. See [SecretRef].
 */
@Serializable
data class AppConfig(
    val project: String = "home-gap",
    /** The base domain that the platform serves, for example `example.net`. */
    val domain: String,
    /** More domains that services can use. The names of the platform itself stay under [domain]. */
    @SerialName("extra_domains") val extraDomains: List<ExtraDomain> = emptyList(),
    @SerialName("data_dir") val dataDir: String = "/data",
    val reconcile: ReconcileConfig = ReconcileConfig(),
    val bunny: BunnyConfig,
    /** What each node runs: the images, the install folder and the options of its Traefik. */
    @SerialName("node_stack") val nodeStack: NodeStackConfig = NodeStackConfig(),
    val acme: AcmeConfig = AcmeConfig(),
    /** The private WireGuard mesh between the homelab and each node. It is always on. */
    val mesh: MeshConfig = MeshConfig(),
    /** HomeGAP's own sign-in for the apps. */
    @SerialName("sign_in") val signIn: SignInConfig = SignInConfig(),
    /** The addresses of the VPN that the apps connect to. */
    val vpn: VpnConfig = VpnConfig(),
    /** The agent of HomeGAP that each node runs. */
    val agent: AgentConfig = AgentConfig(),
    val nodes: List<NodeConfig> = emptyList(),
    val dashboard: DashboardConfig = DashboardConfig(),
    val speedtest: SpeedtestConfig = SpeedtestConfig(),
    @SerialName("status_page") val statusPage: StatusPageConfig = StatusPageConfig(),
    val homelab: HomelabConfig = HomelabConfig(),
    val map: MapConfig = MapConfig(),
) {
    /** The public listener (speed test and status page) runs when either is on. */
    val publicOriginEnabled: Boolean get() = speedtest.enabled || statusPage.enabled

    /** Each domain that a service can use: [domain] first, then the extra domains. */
    val serviceDomains: List<String> get() = listOf(domain) + extraDomains.map { it.domain }

    /** The domains that get the CAA records of HomeGAP. Without `acme.caa`, the list is empty. */
    val caaDomains: List<String>
        get() = if (acme.caa) listOf(domain) + extraDomains.filter { it.caa }.map { it.domain } else emptyList()

    /** Each Bunny zone that HomeGAP writes: the zone of [domain] first. */
    val zones: List<String> get() = (listOf(bunny.zone) + extraDomains.map { it.zoneName }).distinct()

    /** The service domain that [fqdn] is in, or null for a name outside all of them. */
    fun serviceDomainOf(fqdn: String): String? {
        val name = fqdn.removePrefix("*.").lowercase()
        return serviceDomains.firstOrNull { name == it || name.endsWith(".$it") }
    }

    /** The Bunny zone that holds the records of [fqdn]. A name outside the extra domains is in the main zone. */
    fun zoneOf(fqdn: String): String {
        val domain = serviceDomainOf(fqdn)
        return extraDomains.firstOrNull { it.domain == domain }?.zoneName ?: bunny.zone
    }

    /** The name of [fqdn] in its zone, as Bunny stores it. The zone itself has the empty name. */
    fun relativeName(fqdn: String): String {
        val zone = zoneOf(fqdn)
        return if (fqdn == zone) "" else fqdn.removeSuffix(".$zone")
    }

    /** The name of the sign-in, which the apps connect to, or null if it has no public address. */
    val signInHost: String? get() = signIn.url?.removePrefix("https://")?.substringBefore('/')?.lowercase()

    /** The hostnames that HomeGAP routes itself. A service label cannot claim one of them. */
    fun reservedHosts(): Set<String> = buildSet {
        for (node in nodes) {
            add(node.managementHost(domain))
            if (speedtest.enabled) add(speedtest.nodeHost(domain, node.id))
            if (statusPage.enabled) add(statusPage.nodeHost(domain, node.id))
        }
        if (speedtest.enabled) add(speedtest.autoHost(domain))
        if (statusPage.enabled) add(statusPage.autoHost(domain))
        dashboard.publishedHost(domain)?.let(::add)
    }.mapTo(mutableSetOf()) { it.lowercase() }

    fun node(id: String): NodeConfig? = nodes.firstOrNull { it.id == id }
}

@Serializable
data class ReconcileConfig(
    @SerialName("resync_interval") val resyncInterval: String = "60s",
    val debounce: String = "2s",
    @SerialName("dry_run") val dryRun: Boolean = false,
    /** The interval of the drift check of each node. The check uses SSH, so it costs more than the others. */
    @SerialName("node_check_interval") val nodeCheckInterval: String = "5m",
    /**
     * The interval at which HomeGAP reads the full Bunny zone again when the desired records are
     * the same. This finds edits from the Bunny console. A change to a desired record syncs at once.
     */
    @SerialName("dns_check_interval") val dnsCheckInterval: String = "5m",
    /** How long the services of a container stay after the container is gone. */
    @SerialName("removal_grace") val removalGrace: String = "3m",
) {
    val resync: Duration get() = parseDuration(resyncInterval)
    val debounceDuration: Duration get() = parseDuration(debounce)
    val nodeCheck: Duration get() = parseDuration(nodeCheckInterval)
    val dnsCheck: Duration get() = parseDuration(dnsCheckInterval)
    val removalGraceDuration: Duration get() = parseDuration(removalGrace)
}

/** A domain for services, in addition to [AppConfig.domain]. Its zone uses the same Bunny account. */
@Serializable
data class ExtraDomain(
    val domain: String,
    /** The Bunny DNS zone, equal to or a parent of [domain]. The default is [domain]. */
    val zone: String? = null,
    /**
     * With false, HomeGAP publishes no CAA records for this domain. Use it if the root of the
     * domain has a service that gets its certificate in a different way. It has no effect
     * without `acme.caa`.
     */
    val caa: Boolean = true,
) {
    val zoneName: String get() = zone ?: domain
}

@Serializable
data class BunnyConfig(
    /** The Bunny DNS zone, equal to or a parent of [AppConfig.domain]. */
    val zone: String,
    @SerialName("api_key_env") val apiKeyEnv: String? = "BUNNY_API_KEY",
    @SerialName("api_key_file") val apiKeyFile: String? = null,
    val ttl: Int = 60,
    /** `geolocation` (nearest node by coordinates), `latency` (Bunny latency zones) or `none`. */
    val routing: String = "geolocation",
    /** The health monitor of each geo record: `http` (port 80 through the tunnel), `ping` or `none`. */
    val monitor: String = "http",
    @SerialName("api_url") val apiUrl: String = "https://api.bunny.net",
) {
    val apiKey: SecretRef get() = SecretRef("bunny.api_key", envVar = apiKeyEnv, file = apiKeyFile)
}

@Serializable
data class NodeStackConfig(
    @SerialName("traefik_image") val traefikImage: String = "docker.io/traefik:v3.7.16",
    /** Runs the page that a node shows while it cannot reach the homelab. The page needs only the standard library of Python. */
    @SerialName("maintenance_image") val maintenanceImage: String = "docker.io/library/python:3.13-alpine",
    @SerialName("crowdsec_image") val crowdsecImage: String = "docker.io/crowdsecurity/crowdsec:v1.8.2",
    @SerialName("crowdsec_bouncer_version") val crowdsecBouncerVersion: String = "v1.4.6",
    @SerialName("install_dir") val installDir: String = "/opt/homegap",
    /** Enables the CrowdSec bouncer on the Traefik of each node. */
    val crowdsec: Boolean = false,
    /**
     * What a node does with a request when it cannot reach the CrowdSec AppSec component: refuse
     * it (true) or let it through (false).
     */
    @SerialName("crowdsec_fail_closed") val crowdsecFailClosed: Boolean = false,
    /**
     * The start of the range of ports on the mesh address of the homelab. Each service that a
     * node reaches by name gets one. No node publishes them.
     */
    @SerialName("mesh_port_base") val meshPortBase: Int = 40000,
    /**
     * Tunes TCP on each node for long paths with high bandwidth: BBR congestion control, the fq
     * qdisc, larger socket buffers and MTU probing. The settings are in
     * `/etc/sysctl.d/99-homegap.conf`.
     */
    @SerialName("tcp_tuning") val tcpTuning: Boolean = true,
)

/** How HomeGAP orders the certificate that the nodes serve. See [homegap.acme.NodeCertificate]. */
@Serializable
data class AcmeConfig(
    /** The contact address of the ACME account. The default is `admin@<domain>`. */
    val email: String? = null,
    /** The ACME directory. The default is Let's Encrypt, or its staging directory with [staging]. */
    val directory: String? = null,
    val staging: Boolean = false,
    /** Publishes CAA records that allow only [caaIssuer] with the DNS challenge. */
    val caa: Boolean = true,
    @SerialName("caa_issuer") val caaIssuer: String = "letsencrypt.org",
) {
    fun directoryUrl(): String = directory ?: if (staging) STAGING else PRODUCTION

    companion object {
        const val PRODUCTION = "https://acme-v02.api.letsencrypt.org/directory"
        const val STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory"
    }
}

@Serializable
data class SignInConfig(
    /** The address of the sign-in as a browser opens it, for example `https://vpn.example.net`. */
    val url: String? = null,
    /** A user can sign in with the password that HomeGAP holds. Turn it off to accept only [oidc]. */
    val passwords: Boolean = true,
    /** Sign-in through an OpenID Connect provider, for example Authelia. */
    val oidc: OidcConfig? = null,
)

/**
 * An OpenID Connect provider for the sign-in. The provider proves who the user is, with its own
 * second factor. The user must exist in HomeGAP with the same email address, and HomeGAP gives
 * the roles.
 */
@Serializable
data class OidcConfig(
    /** The address of the provider. Its settings are at `<issuer>/.well-known/openid-configuration`. */
    val issuer: String,
    @SerialName("client_id") val clientId: String,
    /** The environment variable of the sign-in container that holds the client secret. */
    @SerialName("client_secret_env") val clientSecretEnv: String = "HOMEGAP_OIDC_CLIENT_SECRET",
    /** The name on the button of the sign-in page. */
    val name: String = "single sign-on",
    val scopes: List<String> = listOf("openid", "email", "profile"),
)

@Serializable
data class VpnConfig(
    /** Each device gets an address of this subnet in the tunnel. The first address is the homelab. */
    @SerialName("client_subnet") val clientSubnet: String = "100.90.0.0/16",
    /** The apps use this subnet for their own DNS proxy and for the addresses of private names. */
    @SerialName("utility_subnet") val utilitySubnet: String = "100.96.128.0/24",
    /** The UDP port of each node that the apps connect to. The next port up is in use inside the tunnel. */
    val port: Int = 51830,
    /** The UDP port of each node for its own exit. The next port up is in use inside the tunnel. */
    @SerialName("exit_port") val exitPort: Int = 51840,
    /**
     * The name or address that the devices send their WireGuard packets to, for the peer of the
     * homelab. It must go to a node. With null, it is the name of the sign-in.
     */
    val endpoint: String? = null,
    /** The exits that the apps can select: each node, the homelab, or both. */
    @SerialName("exit_nodes") val exitNodes: ExitNodesConfig = ExitNodesConfig(),
    /** The DNS servers that the apps reach through the tunnel. */
    @SerialName("tunnel_dns") val tunnelDns: TunnelDnsConfig = TunnelDnsConfig(),
)

@Serializable
data class AgentConfig(
    /** The HomeGAP image that a node runs as its agent. */
    val image: String = defaultImage(),
    /**
     * The build of the image, the value of `HOMEGAP_REVISION`. A tag such as `main` goes to a new
     * image after each build. With a new revision, each node pulls the image again.
     */
    val revision: String? = env("HOMEGAP_REVISION")?.trim()?.ifEmpty { null },
) {
    companion object {
        const val IMAGE_REPOSITORY = "ghcr.io/drewcarlson/homegap"

        /**
         * The HomeGAP image with the tag of the homelab's stack, the value of `HOMEGAP_VERSION`. Without
         * that variable, the tag is the version of this release.
         */
        fun defaultImage(stackTag: String? = env("HOMEGAP_VERSION")): String =
            "$IMAGE_REPOSITORY:${stackTag?.trim()?.ifEmpty { null } ?: VERSION}"
    }
}

/**
 * With "DNS over tunnel", an app sends its DNS lookups through the tunnel to these servers. The
 * peer of the homelab sends them on, so a lookup leaves from home. HomeGAP sets the servers in the
 * app when a device connects.
 */
@Serializable
data class TunnelDnsConfig(
    /** IPv4 addresses of DNS servers. An empty list disables it. */
    val resolvers: List<String> = listOf("8.8.8.8"),
    /** The roles that can use them. Admin always can. */
    val roles: List<String> = listOf("Member"),
)

/**
 * Exits ("Exit via <node>", "Exit via homelab"). A device that selects one sends all its traffic
 * through it, and the traffic leaves for the internet there. An exit gives no way into a private
 * network.
 */
@Serializable
data class ExitNodesConfig(
    /** Each node offers "Exit via <node>". */
    val nodes: Boolean = false,
    /** "Exit via homelab": a node relays the traffic, which leaves from the homelab. */
    val homelab: Boolean = false,
    /** The roles that can use the exits. Admin always can. */
    val roles: List<String> = emptyList(),
)

/**
 * The private WireGuard mesh between the homelab and each node. Each node sends the traffic for
 * the homelab through it. The homelab dials out, so it works behind NAT and no record publishes
 * its address.
 */
@Serializable
data class MeshConfig(
    /** The IPv4 subnet of the mesh addresses. It must not overlap 100.64.0.0/10, where the VPN has its addresses. */
    val subnet: String = "10.77.0.0/24",
    /** The UDP port that each node listens on. The firewall of the node opens it. */
    val port: Int = 51821,
)

@Serializable
data class SpeedtestConfig(
    /** Serves the speed test endpoints (homelab <-> node and device -> node -> homelab). */
    val enabled: Boolean = false,
    /** Host label: `<hostname>.<domain>` (auto) and `<hostname>-<node>.<domain>` (per node). */
    val hostname: String = "speedtest",
    /** The listener that the reverse proxy of the homelab reaches. Do not publish it directly. */
    val bind: String = "0.0.0.0:8089",
    /** The lifetime of a device test link from the dashboard. */
    @SerialName("link_ttl") val linkTtl: String = "2h",
    /** The duration of each download or upload phase. */
    @SerialName("phase_seconds") val phaseSeconds: Int = 8,
    /** The bytes that one device link can send and receive in total, in GiB. */
    @SerialName("link_budget_gb") val linkBudgetGb: Int = 20,
    /** The parallel TCP streams of a homelab <-> node test. The dashboard can set a different number for a run. */
    val streams: Int = 4,
    /** Devices of the VPN open the test page with no link from the dashboard. The front door needs a private path. */
    @SerialName("vpn_access") val vpnAccess: Boolean = false,
) {
    val host: String get() = bind.substringBeforeLast(":")
    val linkBudgetBytes: Long get() = linkBudgetGb * 1024L * 1024 * 1024
    val port: Int get() = bind.substringAfterLast(":").toInt()
    fun autoHost(domain: String) = "$hostname.$domain"
    fun nodeHost(domain: String, nodeId: String) = "$hostname-$nodeId.$domain"
}

/**
 * A public status page. It shows the node that a visitor came through, the state of each node,
 * and the round trip of the visitor to the homelab through each node. The listener of the speed
 * test (`speedtest.bind`) serves it, so the homelab proxy must route `<hostname>*.<domain>` there.
 */
@Serializable
data class StatusPageConfig(
    val enabled: Boolean = false,
    /** Host label: `<hostname>.<domain>` (the nearest node answers) and `<hostname>-<node>.<domain>`. */
    val hostname: String = "status",
    /** The heading of the page. */
    val title: String = "Status",
) {
    fun autoHost(domain: String) = "$hostname.$domain"
    fun nodeHost(domain: String, nodeId: String) = "$hostname-$nodeId.$domain"
}

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    /** The name on the maps and in the dashboard, for example `Dallas, Texas`. */
    val name: String? = null,
)

/** The homelab itself (where the orchestrator runs). */
@Serializable
data class HomelabConfig(
    /** Where the homelab is, for the maps. The public status page only shows its region. */
    val location: GeoLocation? = null,
    val ssh: HomelabSshConfig = HomelabSshConfig(),
)

/** SSH to the homelab host for devices of the VPN: a private service that the config turns on. */
@Serializable
data class HomelabSshConfig(
    val enabled: Boolean = false,
    /** Host label: `<hostname>.<domain>`. */
    val hostname: String = "ssh",
    /** The port of the SSH server of the host. */
    val port: Int = 22,
    /** The roles that can use the service. Admin always can. */
    val roles: List<String> = emptyList(),
) {
    fun host(domain: String) = "$hostname.$domain"
}

/** The self-hosted map (see `tools/build-map.sh`). */
@Serializable
data class MapConfig(
    /** The PMTiles archive of the maps. Without it, the maps show markers on a plain background. */
    val tiles: String = "/usr/share/homegap/map/world.pmtiles",
)

@Serializable
data class NodeConfig(
    val id: String,
    /** A region code (NA, SA, EU, AF, AS, OC, ME), for labels and for the default coordinates. */
    val region: String = "",
    val provider: String = "",
    /** SSH address of the node. */
    val host: String,
    /** The public IPv4 address in the DNS answers. The default is [host] if that is an IPv4 literal. */
    @SerialName("public_ipv4") val publicIpv4: String? = null,
    @SerialName("public_ipv6") val publicIpv6: String? = null,
    val os: String = "",
    @SerialName("ssh_user") val sshUser: String = "root",
    @SerialName("ssh_port") val sshPort: Int = 22,
    @SerialName("ssh_key_file") val sshKeyFile: String,
    val location: GeoLocation? = null,
    @SerialName("latency_zone") val latencyZone: String? = null,
    /**
     * The name of the node itself, which goes only to this node. HomeGAP checks the certificate of
     * the node there. The default is `node-<id>.<domain>`.
     */
    @SerialName("management_host") val managementHost: String? = null,
    /** Manages `ufw` on the node. Disable it only where a different firewall has the authority. */
    val firewall: Boolean = true,
    /**
     * The sources (IPs or CIDRs) that can reach SSH through the firewall of the node. With an empty
     * list, each source can. If the public IP of the orchestrator changes and the list does not
     * cover it, the orchestrator cannot connect to the node. Then correct the rule from the console
     * of the provider.
     */
    @SerialName("ssh_allow_from") val sshAllowFrom: List<String> = emptyList(),
    /** Installs Docker Engine if the node does not have it. Disable it for a host that comes with a runtime. */
    @SerialName("install_docker") val installDocker: Boolean = true,
) {
    fun managementHost(domain: String): String = managementHost ?: "node-$id.$domain"

    fun ipv4(): String? = publicIpv4 ?: host.takeIf { isIpv4(it) }

    fun effectiveLocation(): GeoLocation? = location ?: REGION_CENTROIDS[region.uppercase()]

    val sshKey: SecretRef get() = SecretRef("nodes.$id.ssh_key", envVar = null, file = sshKeyFile)
}

@Serializable
data class DashboardConfig(
    val bind: String = "127.0.0.1:8088",
    @SerialName("auth_token_env") val authTokenEnv: String? = null,
    @SerialName("auth_token_file") val authTokenFile: String? = null,
    /** Lets the dashboard add and remove nodes. Each edit writes the config file again. */
    @SerialName("allow_config_edits") val allowConfigEdits: Boolean = true,
    /** Lets the dashboard open a shell on a node. */
    val terminal: Boolean = true,
    /** Host label: the homelab Traefik serves the dashboard at `<hostname>.<domain>`. Null gives no route. */
    val hostname: String? = null,
    /** Who reaches [hostname]: `private` (devices of the VPN only) or `public`. */
    val access: String = "private",
    /** Sign-in through a forward-auth middleware of the homelab Traefik. It needs [hostname]. */
    val sso: DashboardSsoConfig? = null,
) {
    /** The name of the dashboard at the homelab Traefik, or null without one. */
    fun publishedHost(domain: String): String? = hostname?.let { "$it.$domain" }

    val publishedPrivately: Boolean get() = hostname != null && access == "private"

    fun authToken(): SecretRef? {
        if (authTokenEnv == null && authTokenFile == null) {
            return null
        }
        return SecretRef("dashboard.auth_token", envVar = authTokenEnv, file = authTokenFile)
    }

    val host: String get() = bind.substringBeforeLast(":")
    val port: Int get() = bind.substringAfterLast(":").toInt()
}

/**
 * Sign-in to the dashboard through a forward-auth middleware of the homelab Traefik, for example
 * Authelia. A user who signs in needs no dashboard token.
 */
@Serializable
data class DashboardSsoConfig(
    /** The middleware as Traefik names it, with its provider: `authelia@docker`. */
    val middleware: String,
    /** The header with the name of the user, which the middleware adds to each request. */
    @SerialName("user_header") val userHeader: String = "Remote-User",
    /** The header with the groups of the user, with commas between them. */
    @SerialName("groups_header") val groupsHeader: String = "Remote-Groups",
    /** The groups with full access. If the list is empty, each user that is not read-only has full access. */
    val groups: List<String> = emptyList(),
    /** The groups that can only look: each request that changes something gets a refusal. */
    @SerialName("read_only_groups") val readOnlyGroups: List<String> = emptyList(),
) {
    /** The headers that only the middleware can write. The route removes them from the request of the client. */
    val trustedHeaders: List<String> get() = (listOf(userHeader, groupsHeader) + EXTRA_HEADERS).distinct()

}

private val EXTRA_HEADERS = listOf("Remote-Name", "Remote-Email")

/** The approximate center of each region, for a node that gives only a region code. */
val REGION_CENTROIDS: Map<String, GeoLocation> = mapOf(
    "NA" to GeoLocation(39.8, -98.6),
    "SA" to GeoLocation(-15.6, -56.1),
    "EU" to GeoLocation(50.1, 8.7),
    "AF" to GeoLocation(1.7, 17.3),
    "ME" to GeoLocation(25.3, 51.5),
    "AS" to GeoLocation(22.0, 79.0),
    "OC" to GeoLocation(-25.3, 133.8),
)

fun isIpv4(text: String): Boolean {
    val octets = text.split('.')
    return octets.size == 4 && octets.all { isOctet(it) }
}

private fun isOctet(text: String): Boolean =
    text.isNotEmpty() && text.length <= 3 && text.all(Char::isDigit) && text.toInt() in 0..255

fun parseDuration(text: String): Duration {
    val normalized = text.trim().lowercase()
    val digits = normalized.takeWhile { it.isDigit() || it == '.' }
    val unit = normalized.drop(digits.length).trim()
    val amount = digits.toDoubleOrNull() ?: error("Invalid duration: '$text'")
    return when (unit) {
        "ms" -> amount.milliseconds
        "", "s" -> amount.seconds
        "m" -> amount.minutes
        "h" -> amount.hours
        else -> error("Invalid duration unit in '$text'")
    }
}
