package homegap.config

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
    @SerialName("data_dir") val dataDir: String = "/data",
    val reconcile: ReconcileConfig = ReconcileConfig(),
    val bunny: BunnyConfig,
    val pangolin: PangolinNodeDefaults = PangolinNodeDefaults(),
    val acme: AcmeConfig = AcmeConfig(),
    val tunnels: TunnelsConfig = TunnelsConfig(),
    /** One Pangolin cluster across the nodes. With null, each node has its own Pangolin. */
    val cluster: ClusterConfig? = null,
    val nodes: List<NodeConfig> = emptyList(),
    val dashboard: DashboardConfig = DashboardConfig(),
    val speedtest: SpeedtestConfig = SpeedtestConfig(),
    @SerialName("status_page") val statusPage: StatusPageConfig = StatusPageConfig(),
    val homelab: HomelabConfig = HomelabConfig(),
    val map: MapConfig = MapConfig(),
) {
    /** The public listener (speed test and status page) runs when either is on. */
    val publicOriginEnabled: Boolean get() = speedtest.enabled || statusPage.enabled

    /** The cluster's client address (see [ClusterConfig.clientHostname]), or null without a cluster. */
    val clientHost: String? get() = cluster?.let { "${it.clientHostname}.$domain" }

    /** The hostnames that HomeGAP routes itself. A service label cannot claim one of them. */
    fun reservedHosts(): Set<String> = buildSet {
        clientHost?.let(::add)
        for (node in nodes) {
            add(node.managementHost(domain))
            if (speedtest.enabled) add(speedtest.nodeHost(domain, node.id))
            if (statusPage.enabled) add(statusPage.nodeHost(domain, node.id))
        }
        if (speedtest.enabled) add(speedtest.autoHost(domain))
        if (statusPage.enabled) add(statusPage.autoHost(domain))
    }.mapTo(mutableSetOf()) { it.lowercase() }

    /**
     * The homelab's site on a node. A cluster has one org that all nodes share, so each node gets
     * its own site there. HomeGAP creates the site through that node, which pins it to the exit
     * node of that node.
     */
    fun siteNiceId(nodeId: String): String = if (cluster != null) "${pangolin.siteName}-$nodeId" else pangolin.siteName

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
data class PangolinNodeDefaults(
    @SerialName("pangolin_image") val pangolinImage: String = "docker.io/fosrl/pangolin:ee-1.26.0",
    @SerialName("gerbil_image") val gerbilImage: String = "docker.io/fosrl/gerbil:1.6.0",
    @SerialName("traefik_image") val traefikImage: String = "docker.io/traefik:v3.7.16",
    /**
     * Runs the page that a node shows while it cannot reach the homelab, on nodes without
     * `cluster:`. The page needs only the standard library of Python. A node in a cluster runs the
     * page from the cluster image.
     */
    @SerialName("maintenance_image") val maintenanceImage: String = "docker.io/library/python:3.13-alpine",
    @SerialName("crowdsec_image") val crowdsecImage: String = "docker.io/crowdsecurity/crowdsec:v1.8.2",
    @SerialName("badger_version") val badgerVersion: String = "v1.8.0",
    @SerialName("crowdsec_bouncer_version") val crowdsecBouncerVersion: String = "v1.4.6",
    @SerialName("acme_email") val acmeEmail: String? = null,
    @SerialName("org_id") val orgId: String = "homegap",
    @SerialName("org_name") val orgName: String = "HomeGAP",
    /** The email of the admin account that HomeGAP creates on each node. The default is `admin@<domain>`. */
    @SerialName("admin_email") val adminEmail: String? = null,
    @SerialName("license_key_env") val licenseKeyEnv: String? = null,
    @SerialName("license_key_file") val licenseKeyFile: String? = null,
    @SerialName("install_dir") val installDir: String = "/opt/homegap",
    /** Enables the CrowdSec bouncer on the Traefik of each node. */
    val crowdsec: Boolean = false,
    /**
     * What a node does with a request when it cannot reach the CrowdSec AppSec component: refuse
     * it (true) or let it through (false).
     */
    @SerialName("crowdsec_fail_closed") val crowdsecFailClosed: Boolean = false,
    /** The name of the homelab site on the Pangolin of each node. */
    @SerialName("site_name") val siteName: String = "homelab",
    /** A Pangolin blueprint (YAML or JSON) that each node gets before the managed resources. Null for none. */
    @SerialName("base_blueprint") val baseBlueprint: String? = null,
    /** The start of the range of internal proxy ports for SNI passthrough resources. No node publishes them. */
    @SerialName("passthrough_port_base") val passthroughPortBase: Int = 40000,
    /**
     * Tunes TCP on each node for long paths with high bandwidth: BBR congestion control, the fq
     * qdisc, larger socket buffers and MTU probing. The settings are in
     * `/etc/sysctl.d/99-homegap.conf`.
     */
    @SerialName("tcp_tuning") val tcpTuning: Boolean = true,
) {
    fun licenseKey(): SecretRef? {
        if (licenseKeyEnv == null && licenseKeyFile == null) {
            return null
        }
        return SecretRef("pangolin.license_key", envVar = licenseKeyEnv, file = licenseKeyFile)
    }

    /**
     * The Pangolin image a node runs. A cluster needs the PostgreSQL build, so `ee-<version>` becomes
     * `ee-postgresql-<version>`. Each other tag stays as it is.
     */
    fun pangolinImage(cluster: Boolean): String {
        if (!cluster) {
            return pangolinImage
        }
        val tagAt = pangolinImage.lastIndexOf(':')
        if (tagAt < 0 || '/' in pangolinImage.substring(tagAt)) {
            return pangolinImage
        }
        val tag = pangolinImage.substring(tagAt + 1)
        if (!tag.startsWith(EE_TAG) || tag.startsWith(EE_POSTGRESQL_TAG)) {
            return pangolinImage
        }
        return pangolinImage.substring(0, tagAt + 1) + EE_POSTGRESQL_TAG + tag.removePrefix(EE_TAG)
    }
}

private const val EE_TAG = "ee-"
private const val EE_POSTGRESQL_TAG = "ee-postgresql-"

/** How HomeGAP orders the certificate that the nodes serve. See [homegap.acme.NodeCertificate]. */
@Serializable
data class AcmeConfig(
    /** The contact address of the ACME account. The default is `pangolin.acme_email`, then the admin email. */
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

/** The cluster: what the nodes and the homelab share. */
@Serializable
data class ClusterConfig(
    val mesh: MeshConfig = MeshConfig(),
    val primary: PrimaryConfig = PrimaryConfig(),
    /**
     * What the homelab is in the cluster. With `database` (the default), it is a full member: it
     * holds a copy of the database and of Valkey, and it is the preferred primary. With `vote`, it
     * only votes (etcd and Sentinel). Then the database and Valkey are only on the nodes, so no
     * database process that a node controls runs at home. Set the role before you create the cluster.
     */
    @SerialName("homelab_role") val homelabRole: String = "database",
    /** The cluster image every member runs (PostgreSQL with Patroni, etcd, Valkey, HAProxy). */
    val image: String = "ghcr.io/drewcarlson/homegap-cluster:latest",
    /**
     * `<client_hostname>.<domain>` is the address that Pangolin clients and the login pages use.
     * Each node serves it, and DNS sends a client to the nearest healthy node.
     */
    @SerialName("client_hostname") val clientHostname: String = "pangolin",
    /** The internet exits that the Pangolin apps can select: each node, the homelab, or both. */
    @SerialName("exit_nodes") val exitNodes: ExitNodesConfig = ExitNodesConfig(),
    /** The DNS resolvers that the Pangolin apps can reach through the tunnel, without an exit node. */
    @SerialName("tunnel_dns") val tunnelDns: TunnelDnsConfig = TunnelDnsConfig(),
)

/** The homelab only votes: it runs no PostgreSQL and no Valkey (`cluster.homelab_role: vote`). */
val ClusterConfig.homelabVotesOnly: Boolean get() = homelabRole == "vote"

/**
 * With "DNS over tunnel", a Pangolin app sends its DNS lookups through the tunnel to its upstream
 * DNS server. That server is 8.8.8.8 unless the user changes it in the app, and it must be a
 * resource that the app can reach. Each resolver here becomes such a resource.
 *
 * When the nodes are exits, the resource is on the exit site of each node, and a lookup leaves from
 * the node nearest the device. If not, it is on the homelab site of each node, and a lookup leaves
 * from home.
 */
@Serializable
data class TunnelDnsConfig(
    /** IPv4 or IPv6 addresses of DNS servers. An empty list disables it. The default of the apps is 8.8.8.8. */
    val resolvers: List<String> = listOf("8.8.8.8"),
    /** The roles that can use them. Admin always can. */
    val roles: List<String> = listOf("Member"),
)

/**
 * Exit nodes ("Exit via <node>", "Exit via homelab"). A Pangolin client that selects one sends all
 * its traffic through it, and the traffic leaves for the internet there. The exit of a node is a
 * second Newt connector on the node itself. The exit of the homelab uses the homelab sites.
 */
@Serializable
data class ExitNodesConfig(
    /** Each node offers "Exit via <node>". */
    val nodes: Boolean = false,
    /** "Exit via homelab": the nearest node relays the traffic, which leaves from the homelab. */
    val homelab: Boolean = false,
    /** The roles that can use the exits. Admin always can. */
    val roles: List<String> = emptyList(),
    /** The connector image that the exit of a node runs. */
    @SerialName("newt_image") val newtImage: String = "docker.io/fosrl/newt:1.20.0",
)

/**
 * Where the database primary is. While the cluster cannot reach the preferred member, the primary
 * moves to a node. HomeGAP moves it back after that member is a replica with no lag for
 * [switchBackAfter].
 */
@Serializable
data class PrimaryConfig(
    /** `homelab` is the only value that HomeGAP supports. */
    val preferred: String = "homelab",
    @SerialName("switch_back_after") val switchBackAfter: String = "10m",
) {
    val switchBackDelay: Duration get() = parseDuration(switchBackAfter)
}

/**
 * The private WireGuard mesh between the homelab and each node. Cluster traffic (the database,
 * Valkey, the node API of Gerbil) goes only through the mesh. The homelab dials out, so it works
 * behind NAT and no record publishes its address.
 */
@Serializable
data class MeshConfig(
    /** The IPv4 subnet of the mesh addresses. It must not overlap Pangolin's 100.64.0.0/10 tunnel ranges. */
    val subnet: String = "10.77.0.0/24",
    /** The UDP port that each node listens on. The firewall of the node opens it. */
    val port: Int = 51821,
)

@Serializable
data class TunnelsConfig(
    /** The directory that the orchestrator shares with the `homegap tunnels` supervisor. */
    @SerialName("state_dir") val stateDir: String? = null,
    @SerialName("newt_binary") val newtBinary: String = "/usr/local/bin/newt",
    /**
     * The WireGuard MTU of each Newt connector and of the Gerbil of each node. The two ends of a
     * tunnel must have the same value. Null keeps the upstream default (1280). 1420 fits most paths
     * without fragmentation. Use a lower value for PPPoE or nested tunnels.
     */
    val mtu: Int? = null,
    /**
     * Runs each Newt main tunnel on a kernel WireGuard interface, not on the userspace network
     * stack of Newt. The kernel interface is faster. The `tunnels` container then needs
     * `cap_add: [NET_ADMIN]`, `/dev/net/tun` and a host kernel with WireGuard.
     */
    val native: Boolean = false,
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
)

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
    /** The hostname of the node's Pangolin API (Newt/Olm endpoint). The default is `pangolin-<id>.<domain>`. */
    @SerialName("management_host") val managementHost: String? = null,
    /** Routes the node's Pangolin web UI publicly on the management host. The SSO and PIN login pages need it. */
    @SerialName("dashboard_public") val dashboardPublic: Boolean = false,
    @SerialName("license_key_env") val licenseKeyEnv: String? = null,
    @SerialName("license_key_file") val licenseKeyFile: String? = null,
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
    fun managementHost(domain: String): String = managementHost ?: "pangolin-$id.$domain"

    fun ipv4(): String? = publicIpv4 ?: host.takeIf { isIpv4(it) }

    fun effectiveLocation(): GeoLocation? = location ?: REGION_CENTROIDS[region.uppercase()]

    fun licenseKey(defaults: PangolinNodeDefaults): SecretRef? {
        if (licenseKeyEnv == null && licenseKeyFile == null) {
            return defaults.licenseKey()
        }
        return SecretRef("nodes.$id.license_key", envVar = licenseKeyEnv, file = licenseKeyFile)
    }

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
) {
    fun authToken(): SecretRef? {
        if (authTokenEnv == null && authTokenFile == null) {
            return null
        }
        return SecretRef("dashboard.auth_token", envVar = authTokenEnv, file = authTokenFile)
    }

    val host: String get() = bind.substringBeforeLast(":")
    val port: Int get() = bind.substringAfterLast(":").toInt()
}

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
