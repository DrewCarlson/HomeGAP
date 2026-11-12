package homegap.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours

/**
 * The operator-facing controller configuration (one YAML file).
 *
 * Secrets are never inlined: fields ending in `_env` name an environment variable and fields
 * ending in `_file` name a mounted file. See [SecretRef].
 */
@Serializable
data class AppConfig(
    val project: String = "home-gap",
    /** Base domain served by the platform, e.g. `example.net`. */
    val domain: String,
    @SerialName("data_dir") val dataDir: String = "/data",
    val reconcile: ReconcileConfig = ReconcileConfig(),
    val bunny: BunnyConfig,
    val pangolin: PangolinNodeDefaults = PangolinNodeDefaults(),
    val acme: AcmeConfig = AcmeConfig(),
    val tunnels: TunnelsConfig = TunnelsConfig(),
    /** Clustered Pangolin across the nodes; null keeps one independent Pangolin per node. */
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
    /**
     * The homelab's site on a node. A cluster has one org shared by every node, so each node gets
     * its own site there (created through that node, which pins it to the node's exit node).
     */
    /** The cluster's client address (see [ClusterConfig.clientHostname]); null without a cluster. */
    val clientHost: String? get() = cluster?.let { "${it.clientHostname}.$domain" }

    /** The hostnames that HomeGAP routes itself. A service label cannot claim one of them. */
    fun reservedHosts(): Set<String> = buildSet {
        clientHost?.let(::add)
        for (n in nodes) {
            add(n.managementHost(domain))
            if (speedtest.enabled) add(speedtest.nodeHost(domain, n.id))
            if (statusPage.enabled) add(statusPage.nodeHost(domain, n.id))
        }
        if (speedtest.enabled) add(speedtest.autoHost(domain))
        if (statusPage.enabled) add(statusPage.autoHost(domain))
    }.mapTo(mutableSetOf()) { it.lowercase() }

    fun siteNiceId(nodeId: String): String = if (cluster != null) "${pangolin.siteName}-$nodeId" else pangolin.siteName
    val wildcardDomain: String get() = "*.$domain"
    fun node(id: String): NodeConfig? = nodes.firstOrNull { it.id == id }
}

@Serializable
data class ReconcileConfig(
    @SerialName("resync_interval") val resyncInterval: String = "60s",
    val debounce: String = "2s",
    @SerialName("dry_run") val dryRun: Boolean = false,
    /** How often the (comparatively expensive) SSH drift check runs per node. */
    @SerialName("node_check_interval") val nodeCheckInterval: String = "5m",
    /**
     * How often the full Bunny zone is re-read when the desired records have not changed (to
     * catch edits made in the Bunny console). Changes to desired records sync immediately.
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
    /** Health monitor attached to each geo record: `http` (port 80 via tunnel), `ping` or `none`. */
    val monitor: String = "http",
    @SerialName("api_url") val apiUrl: String = "https://api.bunny.net",
) {
    val apiKey: SecretRef get() = SecretRef("bunny.api_key", apiKeyEnv, apiKeyFile)
}

@Serializable
data class PangolinNodeDefaults(
    @SerialName("pangolin_image") val pangolinImage: String = "docker.io/fosrl/pangolin:ee-1.26.0",
    @SerialName("gerbil_image") val gerbilImage: String = "docker.io/fosrl/gerbil:1.6.0",
    @SerialName("traefik_image") val traefikImage: String = "docker.io/traefik:v3.7.16",
    /**
     * Runs the page a node shows while the homelab is unreachable (Python's standard library only),
     * on nodes without `cluster:`. Clustered nodes run it from the cluster image they already have.
     */
    @SerialName("maintenance_image") val maintenanceImage: String = "docker.io/library/python:3.13-alpine",
    @SerialName("crowdsec_image") val crowdsecImage: String = "docker.io/crowdsecurity/crowdsec:latest",
    @SerialName("badger_version") val badgerVersion: String = "v1.8.0",
    @SerialName("crowdsec_bouncer_version") val crowdsecBouncerVersion: String = "v1.4.6",
    @SerialName("acme_email") val acmeEmail: String? = null,
    @SerialName("org_id") val orgId: String = "homegap",
    @SerialName("org_name") val orgName: String = "HomeGAP",
    /** Email of the admin account minted on each node; defaults to `admin@<domain>`. */
    @SerialName("admin_email") val adminEmail: String? = null,
    @SerialName("license_key_env") val licenseKeyEnv: String? = null,
    @SerialName("license_key_file") val licenseKeyFile: String? = null,
    @SerialName("install_dir") val installDir: String = "/opt/homegap",
    /** Enable the CrowdSec bouncer on every node's Traefik. */
    val crowdsec: Boolean = false,
    /**
     * What a node does with a request when it cannot reach the CrowdSec AppSec component: refuse
     * it (true) or let it through (false).
     */
    @SerialName("crowdsec_fail_closed") val crowdsecFailClosed: Boolean = false,
    /** Site name given to the homelab on each node's Pangolin. */
    @SerialName("site_name") val siteName: String = "homelab",
    /** Optional Pangolin blueprint (YAML or JSON) applied to every node before managed resources. */
    @SerialName("base_blueprint") val baseBlueprint: String? = null,
    /** Internal (unpublished) proxy-port range used for SNI passthrough resources. */
    @SerialName("passthrough_port_base") val passthroughPortBase: Int = 40000,
    /**
     * Apply TCP tuning for long, high-bandwidth paths on every node (BBR congestion control,
     * fq qdisc, larger socket buffers, MTU probing) via `/etc/sysctl.d/99-homegap.conf`.
     */
    @SerialName("tcp_tuning") val tcpTuning: Boolean = true,
) {
    fun licenseKey(): SecretRef? =
        if (licenseKeyEnv == null && licenseKeyFile == null) null
        else SecretRef("pangolin.license_key", licenseKeyEnv, licenseKeyFile)

    /**
     * The Pangolin image a node runs. A cluster needs the PostgreSQL build, so `ee-<version>` becomes
     * `ee-postgresql-<version>`; any other tag is used as given.
     */
    fun pangolinImage(cluster: Boolean): String {
        if (!cluster) return pangolinImage
        val tagAt = pangolinImage.lastIndexOf(':')
        if (tagAt < 0 || '/' in pangolinImage.substring(tagAt)) return pangolinImage
        val tag = pangolinImage.substring(tagAt + 1)
        return if (tag.startsWith("ee-") && !tag.startsWith("ee-postgresql-")) {
            pangolinImage.substring(0, tagAt + 1) + "ee-postgresql-" + tag.removePrefix("ee-")
        } else pangolinImage
    }
}

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
     * What the homelab is in the cluster. `database` (the default): a full member, holding a copy
     * of the database and Valkey, and the preferred primary. `vote`: it only votes (etcd and
     * Sentinel), and the database and Valkey live on the nodes alone, so nothing a node controls
     * runs a database process at home. Chosen before the cluster is created.
     */
    @SerialName("homelab_role") val homelabRole: String = "database",
    /** The cluster image every member runs (PostgreSQL with Patroni, etcd, Valkey, HAProxy). */
    val image: String = "ghcr.io/drewcarlson/homegap-cluster:latest",
    /**
     * `<client_hostname>.<domain>`: the address Pangolin clients and the login pages use, served
     * by every node and geo-routed to the nearest healthy one.
     */
    @SerialName("client_hostname") val clientHostname: String = "pangolin",
    /** Internet exits the Pangolin apps can pick: each node, and/or the homelab. */
    @SerialName("exit_nodes") val exitNodes: ExitNodesConfig = ExitNodesConfig(),
    /** DNS resolvers the Pangolin apps can reach through the tunnel, without an exit node. */
    @SerialName("tunnel_dns") val tunnelDns: TunnelDnsConfig = TunnelDnsConfig(),
)

/** The homelab only votes: it runs no PostgreSQL and no Valkey (`cluster.homelab_role: vote`). */
val ClusterConfig.homelabVotesOnly: Boolean get() = homelabRole == "vote"

/**
 * The Pangolin apps' "DNS over tunnel" sends their own DNS lookups through the tunnel to their
 * upstream DNS server (8.8.8.8 unless changed in the app), which must be a resource they can reach.
 * Each resolver here becomes one: on every node's exit site when nodes are exits (lookups leave
 * from the node nearest the device), otherwise on every node's homelab site (they leave from home).
 */
@Serializable
data class TunnelDnsConfig(
    /** IPv4 or IPv6 addresses of DNS servers; empty turns it off. The apps' default is 8.8.8.8. */
    val resolvers: List<String> = listOf("8.8.8.8"),
    /** Roles that may use them (Admin always can). */
    val roles: List<String> = listOf("Member"),
)

/**
 * Exit nodes ("Exit via <node>", "Exit via homelab"): a Pangolin client that picks one sends all its
 * traffic through it, and it leaves for the internet there. A node's exit is a second Newt connector
 * on the node itself; the homelab's uses the existing homelab sites.
 */
@Serializable
data class ExitNodesConfig(
    /** Each node offers "Exit via <node>". */
    val nodes: Boolean = false,
    /** "Exit via homelab": traffic relayed by the nearest node, leaving from the homelab. */
    val homelab: Boolean = false,
    /** Roles that may use the exits (Admin always can). */
    val roles: List<String> = emptyList(),
    /** The connector image a node's exit runs. */
    @SerialName("newt_image") val newtImage: String = "docker.io/fosrl/newt:1.20.0",
)

/**
 * Where the database primary lives. It moves to a node automatically while the preferred member
 * is unreachable, and HomeGAP switches it back once that member has been a caught-up replica for
 * [switchBackAfter].
 */
@Serializable
data class PrimaryConfig(
    /** `homelab` (the only supported value for now). */
    val preferred: String = "homelab",
    @SerialName("switch_back_after") val switchBackAfter: String = "10m",
) {
    val switchBackDelay: Duration get() = parseDuration(switchBackAfter)
}

/**
 * The private WireGuard mesh between the homelab and every node. Cluster traffic (database,
 * Valkey, Gerbil's node API) only ever crosses it. The homelab dials out, so it works behind NAT
 * and its address is never published.
 */
@Serializable
data class MeshConfig(
    /** IPv4 subnet for mesh addresses; must not overlap Pangolin's 100.64.0.0/10 tunnel ranges. */
    val subnet: String = "10.77.0.0/24",
    /** UDP port every node listens on (opened in the node firewall). */
    val port: Int = 51821,
)

@Serializable
data class TunnelsConfig(
    /** Shared directory between the orchestrator and the `homegap tunnels` supervisor. */
    @SerialName("state_dir") val stateDir: String? = null,
    @SerialName("newt_binary") val newtBinary: String = "/usr/local/bin/newt",
    /**
     * WireGuard MTU used by every Newt connector and every node's Gerbil (both ends of a tunnel
     * must agree). Null keeps the upstream default (1280); 1420 suits most paths without
     * fragmentation, lower it for PPPoE or nested tunnels.
     */
    val mtu: Int? = null,
    /**
     * Run each Newt main tunnel on a kernel WireGuard interface instead of Newt's userspace
     * network stack. Usually much faster; the `tunnels` container then needs `cap_add: [NET_ADMIN]`,
     * `/dev/net/tun` and a host kernel with WireGuard.
     */
    val native: Boolean = false,
)

@Serializable
data class SpeedtestConfig(
    /** Serve the speed test endpoints (homelab <-> node and device -> node -> homelab). */
    val enabled: Boolean = false,
    /** Host label: `<hostname>.<domain>` (auto) and `<hostname>-<node>.<domain>` (per node). */
    val hostname: String = "speedtest",
    /** Listener reached by the homelab reverse proxy; never publish it directly. */
    val bind: String = "0.0.0.0:8089",
    /** Lifetime of device test links created from the dashboard. */
    @SerialName("link_ttl") val linkTtl: String = "2h",
    /** Duration of each download/upload phase. */
    @SerialName("phase_seconds") val phaseSeconds: Int = 8,
    /** The bytes that one device link can send and receive in total, in GiB. */
    @SerialName("link_budget_gb") val linkBudgetGb: Int = 20,
    /** Parallel TCP streams for homelab <-> node tests (the dashboard can override per run). */
    val streams: Int = 4,
) {
    val host: String get() = bind.substringBeforeLast(":")
    val linkBudgetBytes: Long get() = linkBudgetGb * 1024L * 1024 * 1024
    val port: Int get() = bind.substringAfterLast(":").toInt()
    fun autoHost(domain: String) = "$hostname.$domain"
    fun nodeHost(domain: String, nodeId: String) = "$hostname-$nodeId.$domain"
}

/**
 * A public status page: which node a visitor came through, every node's state, and the
 * visitor's round trip to the homelab through each node. Served by the same listener as the
 * speed test (`speedtest.bind`), so the homelab proxy must route `<hostname>*.<domain>` there.
 */
@Serializable
data class StatusPageConfig(
    val enabled: Boolean = false,
    /** Host label: `<hostname>.<domain>` (answered by the nearest node) and `<hostname>-<node>.<domain>`. */
    val hostname: String = "status",
    /** Heading shown on the page. */
    val title: String = "Status",
) {
    fun autoHost(domain: String) = "$hostname.$domain"
    fun nodeHost(domain: String, nodeId: String) = "$hostname-$nodeId.$domain"
}

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    /** Shown on maps and in the dashboard, e.g. `Dallas, Texas`. */
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
    /** The PMTiles archive the maps draw from; without it they show markers on a plain background. */
    val tiles: String = "/usr/share/homegap/map/world.pmtiles",
)

@Serializable
data class NodeConfig(
    val id: String,
    /** Coarse region code (NA, SA, EU, AF, AS, OC, ME); used for labels and default coordinates. */
    val region: String = "",
    val provider: String = "",
    /** SSH address of the node. */
    val host: String,
    /** Public IPv4 answered in DNS; defaults to [host] when that is an IPv4 literal. */
    @SerialName("public_ipv4") val publicIpv4: String? = null,
    @SerialName("public_ipv6") val publicIpv6: String? = null,
    val os: String = "",
    @SerialName("ssh_user") val sshUser: String = "root",
    @SerialName("ssh_port") val sshPort: Int = 22,
    @SerialName("ssh_key_file") val sshKeyFile: String,
    val location: GeoLocation? = null,
    @SerialName("latency_zone") val latencyZone: String? = null,
    /** Hostname of the node's Pangolin API (Newt/Olm endpoint); default `pangolin-<id>.<domain>`. */
    @SerialName("management_host") val managementHost: String? = null,
    /** Route the node's Pangolin web UI on the management host publicly (needed for SSO/PIN login pages). */
    @SerialName("dashboard_public") val dashboardPublic: Boolean = false,
    @SerialName("license_key_env") val licenseKeyEnv: String? = null,
    @SerialName("license_key_file") val licenseKeyFile: String? = null,
    /** Manage `ufw` on the node (disable only where another firewall layer is authoritative). */
    val firewall: Boolean = true,
    /**
     * Sources (IPs or CIDRs) allowed to reach SSH through the node firewall; empty allows any
     * source. If the orchestrator's public IP changes and is not covered, it loses access to the
     * node until the rule is fixed from the provider's console.
     */
    @SerialName("ssh_allow_from") val sshAllowFrom: List<String> = emptyList(),
    /** Install Docker Engine when missing (disable for hosts with a pre-provisioned runtime). */
    @SerialName("install_docker") val installDocker: Boolean = true,
) {
    fun managementHost(domain: String): String = managementHost ?: "pangolin-$id.$domain"

    fun ipv4(): String? = publicIpv4 ?: host.takeIf { isIpv4(it) }

    fun effectiveLocation(): GeoLocation? = location ?: REGION_CENTROIDS[region.uppercase()]

    fun licenseKey(defaults: PangolinNodeDefaults): SecretRef? =
        if (licenseKeyEnv != null || licenseKeyFile != null) SecretRef("nodes.$id.license_key", licenseKeyEnv, licenseKeyFile)
        else defaults.licenseKey()

    val sshKey: SecretRef get() = SecretRef("nodes.$id.ssh_key", null, sshKeyFile)
}

@Serializable
data class DashboardConfig(
    val bind: String = "127.0.0.1:8088",
    @SerialName("auth_token_env") val authTokenEnv: String? = null,
    @SerialName("auth_token_file") val authTokenFile: String? = null,
    /** Allow node add/remove from the dashboard (rewrites the config file). */
    @SerialName("allow_config_edits") val allowConfigEdits: Boolean = true,
) {
    fun authToken(): SecretRef? =
        if (authTokenEnv == null && authTokenFile == null) null
        else SecretRef("dashboard.auth_token", authTokenEnv, authTokenFile)

    val host: String get() = bind.substringBeforeLast(":")
    val port: Int get() = bind.substringAfterLast(":").toInt()
}

/** Rough centroid coordinates used when a node declares only a region code. */
val REGION_CENTROIDS: Map<String, GeoLocation> = mapOf(
    "NA" to GeoLocation(39.8, -98.6),
    "SA" to GeoLocation(-15.6, -56.1),
    "EU" to GeoLocation(50.1, 8.7),
    "AF" to GeoLocation(1.7, 17.3),
    "ME" to GeoLocation(25.3, 51.5),
    "AS" to GeoLocation(22.0, 79.0),
    "OC" to GeoLocation(-25.3, 133.8),
)

fun isIpv4(s: String): Boolean {
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

fun parseDuration(s: String): Duration {
    val t = s.trim().lowercase()
    val num = t.takeWhile { it.isDigit() || it == '.' }
    val unit = t.drop(num.length).trim()
    val n = num.toDoubleOrNull() ?: error("Invalid duration: '$s'")
    return when (unit) {
        "ms" -> n.milliseconds
        "", "s" -> n.seconds
        "m" -> n.minutes
        "h" -> n.hours
        else -> error("Invalid duration unit in '$s'")
    }
}
