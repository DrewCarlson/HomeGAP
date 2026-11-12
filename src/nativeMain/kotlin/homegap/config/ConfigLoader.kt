package homegap.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import homegap.platform.Files
import homegap.platform.nowMillis
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

    /** Rewrites the config file (used by dashboard edits), keeping a timestamped backup. */
    fun save(path: String, config: AppConfig) {
        validate(config)
        if (Files.exists(path)) {
            Files.writeAtomic("$path.bak-${nowMillis()}", Files.readText(path), "600".toInt(8))
            val dir = path.substringBeforeLast('/', ".")
            val prefix = path.substringAfterLast('/') + ".bak-"
            Files.list(dir).filter { it.startsWith(prefix) }
                .sortedByDescending { it.removePrefix(prefix).toLongOrNull() ?: 0 }
                .drop(BACKUPS_KEPT)
                .forEach { Files.delete("$dir/$it") }
        }
        Files.writeAtomic(
            path,
            "# Managed by HomeGAP; edited from the dashboard. Previous version saved alongside as .bak-*\n" + encode(
                config
            ),
            "640".toInt(8),
        )
    }

    fun validate(c: AppConfig) {
        val errors = mutableListOf<String>()
        if (c.domain.isBlank() || c.domain.startsWith(".") || c.domain.contains("*")) errors += "domain must be a plain hostname"
        if (!(c.domain == c.bunny.zone || c.domain.endsWith("." + c.bunny.zone))) {
            errors += "bunny.zone (${c.bunny.zone}) must equal or be a parent of domain (${c.domain})"
        }
        if (c.bunny.routing !in setOf(
                "geolocation",
                "latency",
                "none"
            )
        ) errors += "bunny.routing must be geolocation|latency|none"
        if (c.bunny.monitor !in setOf("http", "ping", "none")) errors += "bunny.monitor must be http|ping|none"
        c.acme.directory?.let { if (!it.startsWith("https://")) errors += "acme.directory must be an https address" }
        if (c.acme.caa && !Regex("^[a-z0-9.-]+$").matches(c.acme.caaIssuer)) errors += "acme.caa_issuer must be a domain name"
        val ids = mutableSetOf<String>()
        for (n in c.nodes) {
            if (!NODE_ID.matches(n.id)) errors += "node id '${n.id}' must match ${NODE_ID.pattern}"
            if (!ids.add(n.id)) errors += "duplicate node id '${n.id}'"
            errors += nodeErrors(n)
            if (n.ipv4() == null && n.publicIpv6 == null) errors += "node ${n.id}: set public_ipv4 (host is not an IPv4 literal)"
            if (c.bunny.routing == "geolocation" && n.effectiveLocation() == null) {
                errors += "node ${n.id}: geolocation routing needs `location` or a known region code (${REGION_CENTROIDS.keys.joinToString()})"
            }
            if (c.bunny.routing == "latency" && n.latencyZone == null) errors += "node ${n.id}: latency routing needs latency_zone"
        }
        try {
            if (c.reconcile.resync.inWholeSeconds < 1) errors += "reconcile.resync_interval must be 1s or more"
            c.reconcile.debounceDuration
            c.reconcile.nodeCheck
            c.reconcile.dnsCheck
        } catch (e: Exception) {
            errors += e.message ?: "bad duration"
        }
        c.tunnels.mtu?.let { if (it !in 1280..1500) errors += "tunnels.mtu must be between 1280 and 1500" }
        if (c.statusPage.enabled) {
            if (!Regex("^[a-z][a-z0-9]{0,20}$").matches(c.statusPage.hostname)) errors += "status_page.hostname must be a single lowercase label"
            if (c.speedtest.enabled && c.statusPage.hostname == c.speedtest.hostname) errors += "status_page.hostname must differ from speedtest.hostname"
            if (c.statusPage.title.length > 60) errors += "status_page.title must be at most 60 characters"
            try {
                c.speedtest.port
            } catch (_: Exception) {
                errors += "speedtest.bind is not host:port (the status page uses it)"
            }
            if (c.speedtest.bind == c.dashboard.bind) errors += "speedtest.bind must differ from dashboard.bind (the status page uses it)"
        }
        if (c.speedtest.enabled) {
            if (!Regex("^[a-z][a-z0-9]{0,20}$").matches(c.speedtest.hostname)) errors += "speedtest.hostname must be a single lowercase label"
            if (c.speedtest.phaseSeconds !in 2..30) errors += "speedtest.phase_seconds must be 2..30"
            if (c.speedtest.linkBudgetGb !in 1..1000) errors += "speedtest.link_budget_gb must be 1..1000"
            if (c.speedtest.streams !in 1..homegap.speedtest.TunnelSpeedTest.MAX_STREAMS) errors += "speedtest.streams must be 1..${homegap.speedtest.TunnelSpeedTest.MAX_STREAMS}"
            try {
                parseDuration(c.speedtest.linkTtl)
                c.speedtest.port
            } catch (_: Exception) {
                errors += "speedtest: bad link_ttl or bind"
            }
            if (c.speedtest.bind == c.dashboard.bind) errors += "speedtest.bind must differ from dashboard.bind"
        }
        try {
            c.dashboard.port
        } catch (_: Exception) {
            errors += "dashboard.bind must be host:port"
        }
        c.cluster?.mesh?.let { m ->
            val subnet = runCatching { homegap.provision.Mesh.parseSubnet(m.subnet) }
                .onFailure { errors += "cluster.mesh.subnet: ${it.message}" }
                .getOrNull()
            if (subnet != null) {
                if (subnet.overlapsCgnat()) errors += "cluster.mesh.subnet must not overlap 100.64.0.0/10 (Pangolin's tunnel ranges)"
                if (!subnet.fits(homegap.reconcile.TunnelSlots.MAX)) errors += "cluster.mesh.subnet is too small for ${homegap.reconcile.TunnelSlots.MAX} nodes (use /26 or larger)"
            }
            if (m.port !in 1..65535) errors += "cluster.mesh.port must be 1-65535"
            if (m.port in setOf(51820, 21820)) {
                errors += "cluster.mesh.port must differ from Gerbil's ports (51820, 21820)"
            }
        }
        c.cluster?.let { cl ->
            if (cl.homelabRole !in setOf("database", "vote")) errors += "cluster.homelab_role must be database or vote"
            if (cl.primary.preferred != "homelab") errors += "cluster.primary.preferred must be homelab"
            try {
                cl.primary.switchBackDelay
            } catch (e: Exception) {
                errors += "cluster.primary.switch_back_after: ${e.message}"
            }
            if (cl.image.isBlank()) errors += "cluster.image must not be empty"
            cl.tunnelDns.resolvers.filterNot { homegap.setup.SetupScripts.isIpAddress(it) }.forEach {
                errors += "cluster.tunnel_dns.resolvers: '$it' is not an IP address"
            }
        }
        require(errors.isEmpty()) {
            "Invalid configuration:\n  - " + errors.joinToString("\n  - ")
        }
    }

    /** Returns the errors in the fields of one node that go into scripts, SSH commands and Traefik rules. */
    fun nodeErrors(n: NodeConfig): List<String> = buildList {
        fun bad(what: String) = add("node ${n.id}: $what")
        if (!HOST.matches(n.host)) bad("host '${n.host}' is not a hostname or an IP address")
        if (!UNIX_USER.matches(n.sshUser)) bad("ssh_user '${n.sshUser}' is not a valid user name")
        if (n.sshPort !in 1..65535) bad("ssh_port must be 1-65535")
        if (n.sshKeyFile.isBlank() || n.sshKeyFile.any { it.isISOControl() }) bad("ssh_key_file is not a valid path")
        n.managementHost?.let { if (!DNS_NAME.matches(it)) bad("management_host '$it' is not a hostname") }
        listOfNotNull(n.publicIpv4, n.publicIpv6).filterNot { homegap.setup.SetupScripts.isIpAddress(it) }.forEach {
            bad("'$it' is not an IP address")
        }
        n.sshAllowFrom.filterNot { SSH_SOURCE.matches(it) }.forEach {
            bad("ssh_allow_from entry '$it' is not an IP address or CIDR")
        }
    }

    /** A hostname, an IPv4 address or an IPv6 address. */
    val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.:-]{0,251}[A-Za-z0-9])?$")

    val DNS_NAME = Regex("^(?i)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    private const val BACKUPS_KEPT = 10

    val NODE_ID = Regex("^[a-z][a-z0-9-]{0,30}$")

    /** An IPv4 or IPv6 address, optionally with a prefix length (loose; `ufw` validates the rest). */
    val SSH_SOURCE = Regex("""^(\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?|[0-9a-fA-F:]*:[0-9a-fA-F:]*(/\d{1,3})?)$""")

    /** Linux user names HomeGAP creates or logs in as. */
    val UNIX_USER = Regex("^[a-z_][a-z0-9_-]{0,31}$")
}
