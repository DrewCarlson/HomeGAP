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
    val certificates: CertificatesConfig = CertificatesConfig(),
    @SerialName("primary_pangolin") val primaryPangolin: PrimaryPangolinConfig? = null,
    val tunnels: TunnelsConfig = TunnelsConfig(),
    val nodes: List<NodeConfig> = emptyList(),
    val dashboard: DashboardConfig = DashboardConfig(),
    val speedtest: SpeedtestConfig = SpeedtestConfig(),
) {
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
) {
    val resync: Duration get() = parseDuration(resyncInterval)
    val debounceDuration: Duration get() = parseDuration(debounce)
    val nodeCheck: Duration get() = parseDuration(nodeCheckInterval)
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
}

@Serializable
data class CertificatesConfig(
    /** `none` (nodes use ACME HTTP-01 only), `files` (PEM cert+key) or `acme_json` (homelab Traefik store). */
    val source: String = "none",
    @SerialName("cert_file") val certFile: String? = null,
    @SerialName("key_file") val keyFile: String? = null,
    @SerialName("acme_json") val acmeJson: String? = null,
    /** Resolver name inside acme.json; when null every resolver is searched. */
    val resolver: String? = null,
)

@Serializable
data class PrimaryPangolinConfig(
    /** Base URL of the primary's Integration API, e.g. `http://pangolin:3003/v1`. */
    val url: String,
    @SerialName("api_key_env") val apiKeyEnv: String? = "PRIMARY_PANGOLIN_KEY",
    @SerialName("api_key_file") val apiKeyFile: String? = null,
    val org: String = "homegap",
    /** niceId of the homelab site registered on the primary Pangolin. */
    val site: String = "homelab",
) {
    val apiKey: SecretRef get() = SecretRef("primary_pangolin.api_key", apiKeyEnv, apiKeyFile)
}

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
) {
    val host: String get() = bind.substringBeforeLast(":")
    val port: Int get() = bind.substringAfterLast(":").toInt()
    fun autoHost(domain: String) = "$hostname.$domain"
    fun nodeHost(domain: String, nodeId: String) = "$hostname-$nodeId.$domain"
}

@Serializable
data class GeoLocation(val latitude: Double, val longitude: Double)

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
