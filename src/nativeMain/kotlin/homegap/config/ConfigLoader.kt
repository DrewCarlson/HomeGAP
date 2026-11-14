package homegap.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.provision.Mesh
import homegap.reconcile.TunnelSlots
import homegap.setup.SetupScripts
import homegap.speedtest.TunnelSpeedTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object ConfigLoader {
    val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = true,
            encodeDefaults = false,
        )
    )

    fun parse(text: String): AppConfig = yaml.decodeFromString<AppConfig>(text).also { validate(it) }

    fun load(path: String): AppConfig {
        require(Files.exists(path)) { "Config file not found: $path" }
        return parse(Files.readText(path))
    }

    fun encode(config: AppConfig): String = yaml.encodeToString(config)

    /** Writes the config file again after an edit in the dashboard. Keeps a backup with the time in its name. */
    fun save(path: String, config: AppConfig) {
        validate(config)
        if (Files.exists(path)) {
            Files.writeAtomic("$path.bak-${nowMillis()}", Files.readText(path), BACKUP_MODE)
            deleteOldBackups(path)
        }
        Files.writeAtomic(path, SAVED_HEADER + encode(config), CONFIG_MODE)
    }

    private fun deleteOldBackups(path: String) {
        val dir = path.substringBeforeLast('/', ".")
        val prefix = path.substringAfterLast('/') + ".bak-"
        Files.list(dir)
            .filter { it.startsWith(prefix) }
            .sortedByDescending { it.removePrefix(prefix).toLongOrNull() ?: 0 }
            .drop(BACKUPS_KEPT)
            .forEach { Files.delete("$dir/$it") }
    }

    fun validate(config: AppConfig) {
        val errors = buildList {
            addAll(domainErrors(config))
            addAll(acmeErrors(config.acme))
            addAll(allNodeErrors(config))
            addAll(reconcileErrors(config.reconcile))
            addAll(tunnelErrors(config.tunnels))
            addAll(statusPageErrors(config))
            addAll(speedtestErrors(config))
            addAll(dashboardErrors(config.dashboard))
            addAll(meshErrors(config.cluster?.mesh))
            addAll(clusterErrors(config.cluster))
        }
        require(errors.isEmpty()) {
            "Invalid configuration:\n  - " + errors.joinToString("\n  - ")
        }
    }

    private fun domainErrors(config: AppConfig): List<String> = buildList {
        val domain = config.domain
        val zone = config.bunny.zone
        if (domain.isBlank() || domain.startsWith(".") || domain.contains("*")) {
            add("domain must be a plain hostname")
        }
        if (!(domain == zone || domain.endsWith(".$zone"))) {
            add("bunny.zone ($zone) must equal or be a parent of domain ($domain)")
        }
        addAll(extraDomainErrors(config))
        if (config.bunny.routing !in BUNNY_ROUTINGS) {
            add("bunny.routing must be geolocation|latency|none")
        }
        if (config.bunny.monitor !in BUNNY_MONITORS) {
            add("bunny.monitor must be http|ping|none")
        }
    }

    private fun extraDomainErrors(config: AppConfig): List<String> = buildList {
        val seen = mutableListOf(config.domain)
        for (extra in config.extraDomains) {
            val domain = extra.domain
            val zone = extra.zoneName
            if (!PLAIN_HOSTNAME.matches(domain)) {
                add("extra_domains: '$domain' must be a plain hostname in lower case")
            }
            if (!(domain == zone || domain.endsWith(".$zone"))) {
                add("extra_domains: the zone ($zone) must equal or be a parent of the domain ($domain)")
            }
            val overlap = seen.firstOrNull { domain == it || domain.endsWith(".$it") || it.endsWith(".$domain") }
            if (overlap != null) {
                add("extra_domains: '$domain' overlaps the domain '$overlap'")
            }
            seen += domain
        }
    }

    private fun acmeErrors(acme: AcmeConfig): List<String> = buildList {
        if (acme.directory != null && !acme.directory.startsWith("https://")) {
            add("acme.directory must be an https address")
        }
        if (acme.caa && !CAA_ISSUER.matches(acme.caaIssuer)) {
            add("acme.caa_issuer must be a domain name")
        }
    }

    private fun allNodeErrors(config: AppConfig): List<String> = buildList {
        val routing = config.bunny.routing
        val ids = mutableSetOf<String>()
        for (node in config.nodes) {
            if (!NODE_ID.matches(node.id)) {
                add("node id '${node.id}' must match ${NODE_ID.pattern}")
            }
            if (!ids.add(node.id)) {
                add("duplicate node id '${node.id}'")
            }
            addAll(nodeErrors(node))
            if (node.ipv4() == null && node.publicIpv6 == null) {
                add("node ${node.id}: set public_ipv4 (host is not an IPv4 literal)")
            }
            if (routing == "geolocation" && node.effectiveLocation() == null) {
                val regions = REGION_CENTROIDS.keys.joinToString()
                add("node ${node.id}: geolocation routing needs `location` or a known region code ($regions)")
            }
            if (routing == "latency" && node.latencyZone == null) {
                add("node ${node.id}: latency routing needs latency_zone")
            }
        }
    }

    private fun reconcileErrors(reconcile: ReconcileConfig): List<String> = buildList {
        try {
            if (reconcile.resync.inWholeSeconds < 1) {
                add("reconcile.resync_interval must be 1s or more")
            }
            // Each getter throws if its text is not a duration.
            reconcile.debounceDuration
            reconcile.nodeCheck
            reconcile.dnsCheck
            reconcile.removalGraceDuration
        } catch (e: Exception) {
            add(e.message ?: "bad duration")
        }
    }

    private fun tunnelErrors(tunnels: TunnelsConfig): List<String> = buildList {
        if (tunnels.mtu != null && tunnels.mtu !in 1280..1500) {
            add("tunnels.mtu must be between 1280 and 1500")
        }
    }

    private fun statusPageErrors(config: AppConfig): List<String> = buildList {
        val statusPage = config.statusPage
        val speedtest = config.speedtest
        if (!statusPage.enabled) {
            return@buildList
        }
        if (!HOST_LABEL.matches(statusPage.hostname)) {
            add("status_page.hostname must be a single lowercase label")
        }
        if (speedtest.enabled && statusPage.hostname == speedtest.hostname) {
            add("status_page.hostname must differ from speedtest.hostname")
        }
        if (statusPage.title.length > STATUS_TITLE_MAX) {
            add("status_page.title must be at most 60 characters")
        }
        try {
            speedtest.port
        } catch (_: Exception) {
            add("speedtest.bind is not host:port (the status page uses it)")
        }
        if (speedtest.bind == config.dashboard.bind) {
            add("speedtest.bind must differ from dashboard.bind (the status page uses it)")
        }
    }

    private fun speedtestErrors(config: AppConfig): List<String> = buildList {
        val speedtest = config.speedtest
        if (!speedtest.enabled) {
            return@buildList
        }
        if (!HOST_LABEL.matches(speedtest.hostname)) {
            add("speedtest.hostname must be a single lowercase label")
        }
        if (speedtest.phaseSeconds !in 2..30) {
            add("speedtest.phase_seconds must be 2..30")
        }
        if (speedtest.linkBudgetGb !in 1..1000) {
            add("speedtest.link_budget_gb must be 1..1000")
        }
        if (speedtest.streams !in 1..TunnelSpeedTest.MAX_STREAMS) {
            add("speedtest.streams must be 1..${TunnelSpeedTest.MAX_STREAMS}")
        }
        try {
            parseDuration(speedtest.linkTtl)
            speedtest.port
        } catch (_: Exception) {
            add("speedtest: bad link_ttl or bind")
        }
        if (speedtest.bind == config.dashboard.bind) {
            add("speedtest.bind must differ from dashboard.bind")
        }
    }

    private fun dashboardErrors(dashboard: DashboardConfig): List<String> = buildList {
        try {
            dashboard.port
        } catch (_: Exception) {
            add("dashboard.bind must be host:port")
        }
    }

    private fun meshErrors(mesh: MeshConfig?): List<String> = buildList {
        if (mesh == null) {
            return@buildList
        }
        val subnet = runCatching { Mesh.parseSubnet(mesh.subnet) }
            .onFailure { add("cluster.mesh.subnet: ${it.message}") }
            .getOrNull()
        if (subnet != null) {
            if (subnet.overlapsCgnat()) {
                add("cluster.mesh.subnet must not overlap 100.64.0.0/10 (Pangolin's tunnel ranges)")
            }
            if (!subnet.fits(TunnelSlots.MAX)) {
                add("cluster.mesh.subnet is too small for ${TunnelSlots.MAX} nodes (use /26 or larger)")
            }
        }
        if (mesh.port !in 1..65535) {
            add("cluster.mesh.port must be 1-65535")
        }
        if (mesh.port in GERBIL_PORTS) {
            add("cluster.mesh.port must differ from Gerbil's ports (51820, 21820)")
        }
    }

    private fun clusterErrors(cluster: ClusterConfig?): List<String> = buildList {
        if (cluster == null) {
            return@buildList
        }
        if (cluster.homelabRole !in HOMELAB_ROLES) {
            add("cluster.homelab_role must be database or vote")
        }
        if (cluster.primary.preferred != "homelab") {
            add("cluster.primary.preferred must be homelab")
        }
        try {
            cluster.primary.switchBackDelay
        } catch (e: Exception) {
            add("cluster.primary.switch_back_after: ${e.message}")
        }
        if (cluster.image.isBlank()) {
            add("cluster.image must not be empty")
        }
        cluster.tunnelDns.resolvers.filterNot { SetupScripts.isIpAddress(it) }.forEach {
            add("cluster.tunnel_dns.resolvers: '$it' is not an IP address")
        }
    }

    /** Returns the errors in the fields of one node that go into scripts, SSH commands and Traefik rules. */
    fun nodeErrors(node: NodeConfig): List<String> = buildList {
        fun bad(what: String) = add("node ${node.id}: $what")
        if (!HOST.matches(node.host)) {
            bad("host '${node.host}' is not a hostname or an IP address")
        }
        if (!UNIX_USER.matches(node.sshUser)) {
            bad("ssh_user '${node.sshUser}' is not a valid user name")
        }
        if (node.sshPort !in 1..65535) {
            bad("ssh_port must be 1-65535")
        }
        if (node.sshKeyFile.isBlank() || node.sshKeyFile.any { it.isISOControl() }) {
            bad("ssh_key_file is not a valid path")
        }
        if (node.managementHost != null && !DNS_NAME.matches(node.managementHost)) {
            bad("management_host '${node.managementHost}' is not a hostname")
        }
        listOfNotNull(node.publicIpv4, node.publicIpv6).filterNot { SetupScripts.isIpAddress(it) }.forEach {
            bad("'$it' is not an IP address")
        }
        node.sshAllowFrom.filterNot { SSH_SOURCE.matches(it) }.forEach {
            bad("ssh_allow_from entry '$it' is not an IP address or CIDR")
        }
    }

    /** A hostname, an IPv4 address or an IPv6 address. */
    val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.:-]{0,251}[A-Za-z0-9])?$")

    val DNS_NAME = Regex("^(?i)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    private const val BACKUPS_KEPT = 10
    private const val STATUS_TITLE_MAX = 60
    private const val SAVED_HEADER =
        "# Managed by HomeGAP; edited from the dashboard. Previous version saved alongside as .bak-*\n"
    private val BACKUP_MODE = "600".toInt(8)
    private val CONFIG_MODE = "640".toInt(8)

    private val BUNNY_ROUTINGS = setOf("geolocation", "latency", "none")
    private val BUNNY_MONITORS = setOf("http", "ping", "none")
    private val HOMELAB_ROLES = setOf("database", "vote")
    private val GERBIL_PORTS = setOf(51820, 21820)

    /** The first label of the hostname of the speed test and of the status page. */
    private val HOST_LABEL = Regex("^[a-z][a-z0-9]{0,20}$")
    private val CAA_ISSUER = Regex("^[a-z0-9.-]+$")
    private val PLAIN_HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    val NODE_ID = Regex("^[a-z][a-z0-9-]{0,30}$")

    /** An IPv4 or IPv6 address, with or without a prefix length. The check is loose, and `ufw` checks the rest. */
    val SSH_SOURCE = Regex("""^(\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?|[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d{1,3})?)$""")

    /** Linux user names HomeGAP creates or logs in as. */
    val UNIX_USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")
}
