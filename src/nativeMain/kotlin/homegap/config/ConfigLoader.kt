package homegap.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import homegap.platform.Files
import homegap.platform.nowMillis

object ConfigLoader {
    val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = true,
            encodeDefaults = false,
        )
    )

    fun parse(text: String): AppConfig = yaml.decodeFromString(AppConfig.serializer(), text).also { validate(it) }

    fun load(path: String): AppConfig {
        require(Files.exists(path)) { "Config file not found: $path" }
        return parse(Files.readText(path))
    }

    fun encode(config: AppConfig): String = yaml.encodeToString(AppConfig.serializer(), config)

    /** Rewrites the config file (used by dashboard edits), keeping a timestamped backup. */
    fun save(path: String, config: AppConfig) {
        validate(config)
        if (Files.exists(path)) {
            Files.writeAtomic("$path.bak-${nowMillis()}", Files.readText(path), "600".toInt(8))
        }
        Files.writeAtomic(
            path,
            "# Managed by HomeGAP; edited from the dashboard. Previous version saved alongside as .bak-*\n" + encode(config),
            "640".toInt(8),
        )
    }

    fun validate(c: AppConfig) {
        val errors = mutableListOf<String>()
        if (c.domain.isBlank() || c.domain.startsWith(".") || c.domain.contains("*")) errors += "domain must be a plain hostname"
        if (!(c.domain == c.bunny.zone || c.domain.endsWith("." + c.bunny.zone))) {
            errors += "bunny.zone (${c.bunny.zone}) must equal or be a parent of domain (${c.domain})"
        }
        if (c.bunny.routing !in setOf("geolocation", "latency", "none")) errors += "bunny.routing must be geolocation|latency|none"
        if (c.bunny.monitor !in setOf("http", "ping", "none")) errors += "bunny.monitor must be http|ping|none"
        if (c.certificates.source !in setOf("none", "files", "acme_json")) errors += "certificates.source must be none|files|acme_json"
        if (c.certificates.source == "files" && (c.certificates.certFile == null || c.certificates.keyFile == null)) {
            errors += "certificates.source=files requires cert_file and key_file"
        }
        if (c.certificates.source == "acme_json" && c.certificates.acmeJson == null) errors += "certificates.source=acme_json requires acme_json"
        val ids = mutableSetOf<String>()
        for (n in c.nodes) {
            if (!NODE_ID.matches(n.id)) errors += "node id '${n.id}' must match ${NODE_ID.pattern}"
            if (!ids.add(n.id)) errors += "duplicate node id '${n.id}'"
            if (n.ipv4() == null && n.publicIpv6 == null) errors += "node ${n.id}: set public_ipv4 (host is not an IPv4 literal)"
            if (c.bunny.routing == "geolocation" && n.effectiveLocation() == null) {
                errors += "node ${n.id}: geolocation routing needs `location` or a known region code (${REGION_CENTROIDS.keys.joinToString()})"
            }
            if (c.bunny.routing == "latency" && n.latencyZone == null) errors += "node ${n.id}: latency routing needs latency_zone"
        }
        try { c.reconcile.resync; c.reconcile.debounceDuration; c.reconcile.nodeCheck } catch (e: Exception) { errors += e.message ?: "bad duration" }
        c.tunnels.mtu?.let { if (it !in 1280..1500) errors += "tunnels.mtu must be between 1280 and 1500" }
        if (c.speedtest.enabled) {
            if (!Regex("^[a-z][a-z0-9]{0,20}$").matches(c.speedtest.hostname)) errors += "speedtest.hostname must be a single lowercase label"
            if (c.speedtest.phaseSeconds !in 2..30) errors += "speedtest.phase_seconds must be 2..30"
            if (c.speedtest.streams !in 1..homegap.speedtest.TunnelSpeedTest.MAX_STREAMS) errors += "speedtest.streams must be 1..${homegap.speedtest.TunnelSpeedTest.MAX_STREAMS}"
            try { parseDuration(c.speedtest.linkTtl); c.speedtest.port } catch (_: Exception) { errors += "speedtest: bad link_ttl or bind" }
            if (c.speedtest.bind == c.dashboard.bind) errors += "speedtest.bind must differ from dashboard.bind"
        }
        try { c.dashboard.port } catch (_: Exception) { errors += "dashboard.bind must be host:port" }
        if (errors.isNotEmpty()) throw IllegalArgumentException("Invalid configuration:\n  - " + errors.joinToString("\n  - "))
    }

    val NODE_ID = Regex("^[a-z][a-z0-9-]{0,30}$")
}
