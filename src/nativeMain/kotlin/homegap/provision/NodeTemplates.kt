package homegap.provision

import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.model.RawPort
import homegap.model.SniRoute
import homegap.reconcile.TunnelSlots
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The secrets of a node. The copy on the node is the authority, and the orchestrator writes it there. */
@Serializable
data class NodeSecrets(
    val setupToken: String,
    val serverSecret: String,
    val adminEmail: String,
    val adminPassword: String,
    val crowdsecBouncerKey: String,
    /** True if the server secret and the admin are those of the cluster, which all the nodes share. */
    val cluster: Boolean = false,
)

/**
 * The values that the stack of a node needs to run as part of the cluster: its mesh address and
 * the shared database passwords. Pangolin reaches the current primaries through `cluster-proxy`
 * and reads from the database member of its own node.
 */
data class ClusterLink(val meshIp: String, val db: ClusterSecrets)

data class RenderedFile(val path: String, val content: String, val mode: String = "0644") {
    /** The compose service to restart after a change of this file (a bind-mounted config). */
    var restarts: String? = null
}

/**
 * Renders the stack of a regional node: Pangolin EE, Gerbil and Traefik, and CrowdSec if the
 * config enables it. The paths are relative to the install directory of the node.
 */
object NodeTemplates {
    /** The metrics entry point of Traefik on each node. It is only on the loopback of the container. */
    const val METRICS_ENTRY_POINT = "metrics"
    const val METRICS_PORT = 8082

    private const val READ_METRICS = "docker exec traefik wget -qO- http://127.0.0.1:$METRICS_PORT/metrics 2>/dev/null"
    private const val SUM_REQUESTS = $$"/^traefik_entrypoint_requests_total/{r+=$NF}"
    private const val SUM_BYTES = $$"/^traefik_entrypoint_responses_bytes_total/{b+=$NF}"
    private const val PRINT_TRAFFIC = "END{if (NR>0) printf \"M %.0f %.0f\\n\", r, b}"

    /** Prints `M <requests> <response bytes>`, the sums over the entry points, if Traefik answers. */
    const val TRAFFIC_CMD = "$READ_METRICS | awk '$SUM_REQUESTS $SUM_BYTES $PRINT_TRAFFIC'"

    const val DYNAMIC_ROUTES = "config/traefik/dynamic/homegap-routes.yml"

    /**
     * The PROXY protocol version that HomeGAP asks Pangolin for. Pangolin gives such a TCP service
     * its `pp-transport-v<N>@file` transport. Pangolin defines that transport itself only in a
     * file on a clustered node. A standalone node gets it from the routes file of HomeGAP.
     */
    const val PROXY_PROTOCOL_VERSION = 2
    const val PROXY_PROTOCOL_TRANSPORT = "pp-transport-v$PROXY_PROTOCOL_VERSION"
    const val DYNAMIC_TLS = "config/traefik/dynamic/homegap-tls.yml"
    const val DYNAMIC_HEADERS = "config/traefik/dynamic/homegap-headers.yml"
    const val HSTS = "homegap-hsts"
    private const val HSTS_SECONDS = 31_536_000

    /** Requests in a second from one address to the public status page of a node, and the burst. */
    private const val STATUS_RATE = 30
    private const val STATUS_BURST = 150
    const val CERT = "config/traefik/certs/homegap.crt"
    const val KEY = "config/traefik/certs/homegap.key"

    /** Pangolin writes this resolver name into its routes. Traefik reads an empty name as no resolver. */
    const val NO_RESOLVER = ""
    const val SECRETS = "secrets/node.json"

    /** The routes of Pangolin itself in a cluster (file mode), in the dynamic directory with those of HomeGAP. */
    const val PANGOLIN_ROUTES = "/var/dynamic/router_config.yml"

    /** Under the `config/` of the node, which Pangolin sees as `/app/config`. */
    const val AI_CATALOG = "config/ai-model-catalog.json"
    const val GERBIL_API_PORT = 3004
    private val PRETTY_JSON = Json { prettyPrint = true }

    /**
     * HomeGAP renders this file into the install directory, so the file has a hash and a drift
     * check. The node installs it into /etc/sysctl.d.
     */
    const val SYSCTL = "config/homegap-sysctl.conf"

    private const val HEADER = "Managed by HomeGAP. Manual edits are reverted on the next reconcile\n" +
        "unless this node is paused from the dashboard."

    private const val CLUSTER_NETWORK_KEY = "cluster"

    // Names in the routes file of HomeGAP.
    private const val WEB = "web"
    private const val WEBSECURE = "websecure"
    private const val NOOP_SERVICE = "noop@internal"
    private const val REDIRECT_HTTPS = "homegap-redirect-https"
    private const val HEALTH_PATH = "homegap-health-path"
    private const val STATUS_RATE_LIMIT = "homegap-status-rate"
    private const val PANGOLIN_API = "homegap-pangolin-api"
    private const val PANGOLIN_NEXT = "homegap-pangolin-next"
    private const val UNAVAILABLE = "homegap-unavailable"

    // Router priorities. A higher number wins.
    private const val TOP_PRIORITY = 10000
    private const val UI_PRIORITY = 9999
    private const val HEALTH_PRIORITY = 60
    private const val HEALTH_FALLBACK_PRIORITY = 50
    private const val HTTPS_REDIRECT_PRIORITY = 10
    private const val MISDIRECTED_PRIORITY = 1

    fun adminEmail(config: AppConfig) = config.pangolin.adminEmail ?: "admin@${config.domain}"

    fun staticFiles(
        config: AppConfig,
        node: NodeConfig,
        secrets: NodeSecrets,
        rawPorts: List<RawPort>,
        slot: Int,
        cluster: ClusterLink? = null,
    ): List<RenderedFile> {
        val files = mutableListOf(
            RenderedFile("compose.yml", compose(config, rawPorts, cluster)),
            RenderedFile(".env", envFile(secrets, config.pangolin.crowdsec), "0600"),
            RenderedFile("config/config.yml", pangolinConfig(config, node, secrets, slot, cluster), "0600")
                .restarting("pangolin"),
            RenderedFile("config/traefik/traefik_config.yml", traefikStatic(config, rawPorts, cluster != null))
                .restarting("traefik"),
            RenderedFile(SECRETS, PRETTY_JSON.encodeToString(secrets), "0600"),
            RenderedFile(AI_CATALOG, OutsideCalls.EMPTY_AI_CATALOG),
        )
        files += RenderedFile(DYNAMIC_HEADERS, headersDynamic())
        files += RenderedFile("${MaintenancePage.DIR}/server.py", MaintenancePage.SCRIPT)
            .restarting(MaintenancePage.SERVICE)
        files += RenderedFile("${MaintenancePage.DIR}/index.html", MaintenancePage.page(config.domain, node.id))
        if (cluster != null) {
            files += RenderedFile("config/privateConfig.yml", pangolinPrivateConfig(node, cluster), "0600")
                .restarting("pangolin")
        }
        if (config.pangolin.crowdsec) {
            files += RenderedFile("config/crowdsec/acquis.d/traefik.yaml", crowdsecAcquisition())
                .restarting("crowdsec")
            files += RenderedFile(
                path = "config/traefik/dynamic/homegap-crowdsec.yml",
                content = crowdsecDynamic(secrets, config.pangolin.crowdsecFailClosed),
                mode = "0600",
            )
        }
        return files
    }

    private fun RenderedFile.restarting(service: String): RenderedFile = apply { restarts = service }

    private fun crowdsecAcquisition(): String = YamlEmitter.emit(
        buildJsonObject {
            put("poll_without_inotify", false)
            putJsonArray("filenames") { add("/var/log/traefik/*.log") }
            putJsonObject("labels") { put("type", "traefik") }
        },
        HEADER,
    )

    // ------------------------------------------------------------------ compose

    fun compose(config: AppConfig, rawPorts: List<RawPort>, cluster: ClusterLink? = null): String {
        val root = buildJsonObject {
            put("name", "homegap")
            putJsonObject("services") {
                putJsonObject("pangolin") { pangolinContainer(config, cluster) }
                putJsonObject("gerbil") { gerbilContainer(config, rawPorts, cluster) }
                putJsonObject("traefik") { traefikContainer(config, cluster) }
                putJsonObject(MaintenancePage.SERVICE) { maintenanceContainer(config, cluster) }
                if (config.pangolin.crowdsec) {
                    putJsonObject("crowdsec") { crowdsecContainer(config) }
                }
            }
            putJsonObject("networks") {
                putJsonObject("default") {
                    put("driver", "bridge")
                    put("name", "homegap_node")
                }
                // The cluster member of the node (its own compose project) makes this network before the stack starts.
                if (cluster != null) {
                    putJsonObject(CLUSTER_NETWORK_KEY) {
                        put("external", true)
                        put("name", ClusterTemplates.NETWORK)
                    }
                }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    private fun JsonObjectBuilder.pangolinContainer(config: AppConfig, cluster: ClusterLink?) {
        put("image", config.pangolin.pangolinImage(cluster != null))
        put("container_name", "pangolin")
        put("restart", "unless-stopped")
        putJsonArray("env_file") { add(".env") }
        putJsonArray("extra_hosts") { OutsideCalls.PANGOLIN.forEach { add("$it=${OutsideCalls.NOWHERE}") } }
        putJsonArray("volumes") {
            add("./config:/app/config")
            if (cluster != null) {
                // File mode: Pangolin writes its routes where the file provider of Traefik reads.
                add("./config/traefik/dynamic:/var/dynamic")
                add("./config/certificates:/var/certificates")
            }
        }
        if (cluster != null) {
            putJsonArray("networks") {
                add("default")
                add(CLUSTER_NETWORK_KEY)
            }
        }
        // Loopback only: the orchestrator reaches these ports through SSH port-forwarding.
        putJsonArray("ports") {
            add("127.0.0.1:3000:3000")
            add("127.0.0.1:3002:3002")
            add("127.0.0.1:3003:3003")
        }
        putJsonObject("healthcheck") {
            putJsonArray("test") {
                add("CMD")
                add("curl")
                add("-f")
                add("http://localhost:3001/api/v1/")
            }
            put("interval", "10s")
            put("timeout", "10s")
            put("retries", 15)
        }
    }

    /** In a cluster, the Pangolin of each node reaches this Gerbil over the mesh, which encrypts the plain HTTP. */
    @Suppress("HttpUrlsUsage")
    private fun JsonObjectBuilder.gerbilContainer(config: AppConfig, rawPorts: List<RawPort>, cluster: ClusterLink?) {
        put("image", config.pangolin.gerbilImage)
        put("container_name", "gerbil")
        put("restart", "unless-stopped")
        putJsonObject("depends_on") { putJsonObject("pangolin") { put("condition", "service_healthy") } }
        putJsonArray("command") {
            add("--reachableAt=http://${cluster?.meshIp ?: "gerbil"}:$GERBIL_API_PORT")
            add("--generateAndSaveKeyTo=/var/config/key")
            add("--remoteConfig=http://pangolin:3001/api/v1/")
            config.tunnels.mtu?.let { add("--mtu=$it") }
        }
        putJsonArray("volumes") { add("./config/:/var/config") }
        putJsonArray("cap_add") {
            add("NET_ADMIN")
            add("SYS_MODULE")
        }
        // The TCP settings belong to a network namespace: the sysctl.d of the host does not
        // reach containers. Traefik shares this namespace, so each node -> homelab and
        // node -> viewer connection starts here. The namespace gets the congestion control
        // of the host when Docker makes it.
        if (config.pangolin.tcpTuning) {
            putJsonObject("sysctls") { CONTAINER_SYSCTLS.forEach { (name, value) -> put(name, value) } }
        }
        putJsonArray("ports") {
            add("51820:51820/udp")
            add("21820:21820/udp")
            add("443:443")
            add("443:443/udp")
            add("80:80")
            rawPorts.forEach { add("${it.port}:${it.port}/${it.protocol}") }
            // The mesh address only: the API of Gerbil must never be public.
            cluster?.let { add("${it.meshIp}:$GERBIL_API_PORT:$GERBIL_API_PORT") }
        }
    }

    private fun JsonObjectBuilder.traefikContainer(config: AppConfig, cluster: ClusterLink?) {
        put("image", config.pangolin.traefikImage)
        put("container_name", "traefik")
        put("restart", "unless-stopped")
        put("network_mode", "service:gerbil")
        putJsonObject("depends_on") { putJsonObject("pangolin") { put("condition", "service_healthy") } }
        putJsonArray("command") { add("--configFile=/etc/traefik/traefik_config.yml") }
        putJsonArray("volumes") {
            add("./config/traefik:/etc/traefik:ro")
            add("./config/traefik/logs:/var/log/traefik")
            if (cluster != null) add("./config/certificates:/var/certificates:ro")
        }
    }

    private fun JsonObjectBuilder.maintenanceContainer(config: AppConfig, cluster: ClusterLink?) {
        // A clustered node already has the cluster image (with Python). Only a standalone node
        // pulls an image for the page alone.
        put("image", config.cluster?.takeIf { cluster != null }?.image ?: config.pangolin.maintenanceImage)
        put("container_name", "homegap-maintenance")
        put("restart", "unless-stopped")
        put("stop_grace_period", "2s")
        // The namespace of Gerbil: the tunnel addresses that the service checks have a route
        // only there, and Traefik (also in it) reaches the page on loopback.
        put("network_mode", "service:gerbil")
        putJsonObject("depends_on") { putJsonObject("gerbil") { put("condition", "service_started") } }
        putJsonArray("entrypoint") {
            add("python3")
            add("-u")
            add("/maintenance/server.py")
        }
        putJsonArray("volumes") {
            add("./${MaintenancePage.DIR}:/maintenance:ro")
            add("./config/traefik/dynamic:/dynamic")
        }
    }

    private fun JsonObjectBuilder.crowdsecContainer(config: AppConfig) {
        put("image", config.pangolin.crowdsecImage)
        put("container_name", "crowdsec")
        put("restart", "unless-stopped")
        putJsonObject("environment") {
            put("GID", "1000")
            put(
                "COLLECTIONS",
                "crowdsecurity/traefik crowdsecurity/appsec-virtual-patching crowdsecurity/appsec-generic-rules",
            )
            put("PARSERS", "crowdsecurity/whitelists")
            put("BOUNCER_KEY_traefik", $$"${CROWDSEC_BOUNCER_KEY}")
        }
        putJsonArray("env_file") { add(".env") }
        putJsonArray("volumes") {
            add("./config/crowdsec:/etc/crowdsec")
            add("./config/crowdsec/db:/var/lib/crowdsec/data")
            add("./config/traefik/logs:/var/log/traefik:ro")
        }
    }

    /** The part of [sysctlConf] that belongs to a network namespace. The edge containers of the node get it. */
    val CONTAINER_SYSCTLS = linkedMapOf(
        "net.ipv4.tcp_rmem" to "4096 131072 67108864",
        "net.ipv4.tcp_wmem" to "4096 65536 67108864",
        "net.ipv4.tcp_mtu_probing" to "1",
        "net.ipv4.tcp_slow_start_after_idle" to "0",
        "net.ipv4.tcp_reordering" to "10",
        "net.ipv4.tcp_max_reordering" to "1000",
    )

    /**
     * TCP tuning for long fat paths (viewer <-> node <-> homelab). BBR keeps the throughput high
     * with loss and a high RTT. Larger buffers let the windows grow to the bandwidth-delay
     * product. MTU probing recovers when PMTU discovery fails across the tunnel. Long routes
     * often reorder packets, mostly across aggregated links. With higher reordering thresholds,
     * TCP does not read that as loss, which decreases the congestion window.
     */
    fun sysctlConf(): String = """
        |# Managed by HomeGAP (pangolin.tcp_tuning). Removed when tuning is disabled.
        |net.core.default_qdisc = fq
        |net.ipv4.tcp_congestion_control = bbr
        |net.core.rmem_max = 67108864
        |net.core.wmem_max = 67108864
        |net.ipv4.tcp_rmem = 4096 131072 67108864
        |net.ipv4.tcp_wmem = 4096 65536 67108864
        |net.ipv4.tcp_mtu_probing = 1
        |net.ipv4.tcp_slow_start_after_idle = 0
        |net.ipv4.tcp_reordering = 10
        |net.ipv4.tcp_max_reordering = 1000
        |
    """.trimMargin()

    fun envFile(secrets: NodeSecrets, crowdsec: Boolean): String = buildString {
        appendLine("PANGOLIN_SETUP_TOKEN=${secrets.setupToken}")
        if (crowdsec) appendLine("CROWDSEC_BOUNCER_KEY=${secrets.crowdsecBouncerKey}")
    }

    // ------------------------------------------------------------------ Pangolin

    fun pangolinConfig(
        config: AppConfig,
        node: NodeConfig,
        secrets: NodeSecrets,
        slot: Int,
        cluster: ClusterLink? = null,
    ): String {
        val managementHost = node.managementHost(config.domain)
        // In a cluster, links and redirects use the shared client address.
        val dashboard = config.clientHost.takeIf { cluster != null } ?: managementHost
        val root = buildJsonObject {
            putJsonObject("gerbil") {
                put("start_port", 51820)
                put("base_endpoint", managementHost)
                put("exit_node_name", "homegap-${node.id}")
                // Each node has its own tunnel subnet (from its permanent slot), so the homelab
                // can hold the tunnel of each node in one network namespace. With one subnet, the
                // kernel WireGuard routes collide. Slot 0 keeps the default first block of Pangolin.
                put("subnet_group", TunnelSlots.subnet(slot))
            }
            putJsonObject("app") {
                put("dashboard_url", "https://$dashboard")
                put("log_level", "info")
                putJsonObject("telemetry") { put("anonymous_usage", false) }
                // Without this, the dashboard asks the API of Fossorial for product news and the newest version.
                putJsonObject("notifications") {
                    put("product_updates", false)
                    put("new_releases", false)
                }
            }
            // Without this, Pangolin downloads the model catalog (AI pricing) from Fossorial every few hours.
            putJsonObject("ai") { putJsonObject("model_catalog") { put("file", "/app/$AI_CATALOG") } }
            putJsonObject("domains") {
                putJsonObject("homegap") {
                    put("base_domain", config.domain)
                    // No resolver: a node orders no certificate. See [homegap.acme.NodeCertificate].
                    put("cert_resolver", NO_RESOLVER)
                }
            }
            if (cluster != null) {
                putJsonObject("postgres") {
                    // Writes go to the current primary through HAProxy. Reads use the member of this node.
                    val primary = ClusterTemplates.PROXY_HOST to ClusterTemplates.PROXY_POSTGRES_PORT
                    val replica = cluster.meshIp to ClusterTemplates.POSTGRES_PORT
                    put("connection_string", postgresUrl(cluster, primary))
                    putJsonArray("replicas") {
                        addJsonObject { put("connection_string", postgresUrl(cluster, replica)) }
                    }
                }
            }
            putJsonObject("server") {
                put("secret", secrets.serverSecret)
                putJsonObject("cors") {
                    val origins = listOf(dashboard, managementHost).distinct()
                    putJsonArray("origins") { origins.forEach { add("https://$it") } }
                    putJsonArray("methods") { listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { add(it) } }
                    putJsonArray("allowed_headers") {
                        add("X-CSRF-Token")
                        add("Content-Type")
                    }
                    put("credentials", false)
                }
            }
            putJsonObject("traefik") {
                put("cert_resolver", NO_RESOLVER)
                put("http_entrypoint", WEB)
                put("https_entrypoint", WEBSECURE)
                if (cluster != null) {
                    // Each node writes the routes for its own sites. Traefik never polls a Pangolin.
                    put("file_mode", true)
                    put("dynamic_router_config_path", PANGOLIN_ROUTES)
                    putJsonArray("site_types") { add("newt") }
                }
            }
            putJsonObject("flags") {
                put("require_email_verification", false)
                put("disable_signup_without_invite", true)
                put("disable_user_create_org", true)
                put("allow_raw_resources", true)
                put("enable_integration_api", true)
                if (cluster != null) {
                    put("disable_local_sites", true)
                    put("disable_basic_wireguard_sites", true)
                }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    /**
     * The cluster settings that Pangolin reads from `privateConfig.yml`: the region of the node,
     * Valkey, and its local Gerbil. The region pins the sites that a client makes through the
     * node to the exit node of that node. The Gerbil URL uses plain HTTP on the Docker network
     * of the stack.
     */
    @Suppress("HttpUrlsUsage")
    fun pangolinPrivateConfig(node: NodeConfig, cluster: ClusterLink): String = YamlEmitter.emit(
        buildJsonObject {
            putJsonObject("app") { put("region", node.id) }
            putJsonObject("redis") {
                put("host", ClusterTemplates.PROXY_HOST)
                put("port", ClusterTemplates.PROXY_VALKEY_PORT)
                put("password", cluster.db.valkey)
            }
            putJsonObject("gerbil") { put("local_exit_node_reachable_at", "http://gerbil:$GERBIL_API_PORT") }
            putJsonObject("flags") { put("enable_redis", true) }
        },
        HEADER,
    )

    private fun postgresUrl(cluster: ClusterLink, server: Pair<String, Int>): String {
        val (host, port) = server
        val credentials = "${ClusterTemplates.PANGOLIN_USER}:${cluster.db.pangolin}"
        return "postgresql://$credentials@$host:$port/${ClusterTemplates.PANGOLIN_DB}"
    }

    /** The Traefik service that Pangolin generates for a resource, as the routes of HomeGAP name it. */
    fun pangolinService(resourceId: Int, niceId: String, cluster: Boolean) =
        "$resourceId-$niceId-service@${if (cluster) "file" else "http"}"

    // ------------------------------------------------------------------ Traefik static config

    fun traefikStatic(config: AppConfig, rawPorts: List<RawPort>, cluster: Boolean = false): String {
        val pangolin = config.pangolin
        val root = buildJsonObject {
            // No version check (update.traefik.io) and no usage data.
            putJsonObject("global") {
                put("checkNewVersion", false)
                put("sendAnonymousUsage", false)
            }
            putJsonObject("api") {
                put("insecure", false)
                put("dashboard", false)
            }
            putJsonObject("providers") {
                if (!cluster) {
                    putJsonObject("http") {
                        put("endpoint", "http://pangolin:3001/api/v1/traefik-config")
                        put("pollInterval", "5s")
                    }
                }
                putJsonObject("file") {
                    put("directory", "/etc/traefik/dynamic")
                    put("watch", true)
                }
            }
            putJsonObject("experimental") {
                putJsonObject("plugins") {
                    putJsonObject("badger") {
                        put("moduleName", "github.com/fosrl/badger")
                        put("version", pangolin.badgerVersion)
                    }
                    if (pangolin.crowdsec) {
                        putJsonObject("crowdsec") {
                            put("moduleName", "github.com/maxlerebourg/crowdsec-bouncer-traefik-plugin")
                            put("version", pangolin.crowdsecBouncerVersion)
                        }
                    }
                }
            }
            putJsonObject("log") {
                put("level", "INFO")
                put("format", if (pangolin.crowdsec) "json" else "common")
            }
            if (pangolin.crowdsec) {
                putJsonObject("accessLog") {
                    put("filePath", "/var/log/traefik/access.log")
                    put("format", "json")
                }
            }
            putJsonObject("entryPoints") {
                putJsonObject(WEB) { put("address", ":80") }
                putJsonObject(WEBSECURE) {
                    put("address", ":443")
                    putJsonObject("transport") { putJsonObject("respondingTimeouts") { put("readTimeout", "30m") } }
                    putJsonObject("http3") { put("advertisedPort", 443) }
                    putJsonObject("http") {
                        putJsonObject("tls") {}
                        putJsonArray("middlewares") {
                            add("$HSTS@file")
                            if (pangolin.crowdsec) add("crowdsec@file")
                        }
                        putJsonObject("encodedCharacters") {
                            put("allowEncodedSlash", true)
                            put("allowEncodedQuestionMark", true)
                        }
                    }
                }
                for (rawPort in rawPorts) {
                    putJsonObject(rawPort.entryPoint) { put("address", ":${rawPort.port}/${rawPort.protocol}") }
                }
                // Request and byte counters for the dashboard, which reads them with `docker exec`.
                // The entry point is on the loopback of the container, so nothing outside the
                // container can reach it.
                putJsonObject(METRICS_ENTRY_POINT) { put("address", "127.0.0.1:$METRICS_PORT") }
            }
            putJsonObject("metrics") {
                putJsonObject("prometheus") {
                    put("entryPoint", METRICS_ENTRY_POINT)
                    put("addEntryPointsLabels", true)
                    put("addRoutersLabels", false)
                    put("addServicesLabels", false)
                }
            }
            // The hop to the origin runs in the WireGuard tunnel to the Traefik of the homelab.
            // A short dial timeout: a dead tunnel answers 502 fast, and the health probes do not hang.
            putJsonObject("serversTransport") {
                put("insecureSkipVerify", true)
                putJsonObject("forwardingTimeouts") { put("dialTimeout", "5s") }
            }
            putJsonObject("ping") { put("entryPoint", WEB) }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    /**
     * The headers of each HTTPS answer of the node. HSTS tells a browser to use HTTPS for the name
     * for one year. It covers only the name itself: a node must not set a rule for other names.
     */
    fun headersDynamic(): String = YamlEmitter.emit(
        buildJsonObject {
            putJsonObject("http") {
                putJsonObject("middlewares") {
                    putJsonObject(HSTS) { putJsonObject("headers") { put("stsSeconds", HSTS_SECONDS) } }
                }
            }
        },
        HEADER,
    )

    private fun crowdsecDynamic(secrets: NodeSecrets, failClosed: Boolean): String = YamlEmitter.emit(
        buildJsonObject {
            putJsonObject("http") {
                putJsonObject("middlewares") {
                    putJsonObject("crowdsec") {
                        putJsonObject("plugin") {
                            putJsonObject("crowdsec") {
                                put("enabled", true)
                                put("crowdsecMode", "live")
                                put("crowdsecAppsecEnabled", true)
                                put("crowdsecAppsecHost", "crowdsec:7422")
                                put("crowdsecAppsecFailureBlock", true)
                                put("crowdsecAppsecUnreachableBlock", failClosed)
                                put("crowdsecLapiKey", secrets.crowdsecBouncerKey)
                                put("crowdsecLapiHost", "crowdsec:8080")
                                put("crowdsecLapiScheme", "http")
                            }
                        }
                    }
                }
            }
        },
        HEADER,
    )

    // ------------------------------------------------------------------ dynamic routes

    /**
     * The routing file, which Traefik loads again after each change: the management API, the SNI
     * passthrough routers, the HTTP->HTTPS redirects and the health endpoint on port 80 that the
     * monitor of Bunny probes.
     *
     * [resourceIds] gives the numeric id of each Pangolin resource niceId. The Traefik service
     * names that Pangolin generates include the id. The file has no route for a resource that
     * does not exist yet.
     */
    fun dynamicRoutes(desired: NodeDesired, resourceIds: Map<String, Int>, cluster: Boolean = false): String {
        val sniRoutes = desired.sniRoutes.mapNotNull { route -> resourceIds[route.resourceNiceId]?.let { route to it } }
        val misdirected = misdirectedRule(sniRoutes.map { it.first }, ownNames(desired))
        val sharedNames = sharedNamesOf(desired)
        val proxyTransport = !cluster && desired.publicResources.any { it.proxyProtocol }
        val root = buildJsonObject {
            if (misdirected != null) {
                putJsonObject("tls") {
                    putJsonObject("options") {
                        putJsonObject(ELSEWHERE_TLS_OPTIONS) { put("minVersion", "VersionTLS12") }
                    }
                }
            }
            putJsonObject("http") {
                putJsonObject("middlewares") { httpMiddlewares(desired, sharedNames) }
                putJsonObject("routers") {
                    misdirected?.let { misdirectedRouter(it) }
                    pangolinRouters(desired)
                    sharedNames.forEach { sharedNameRouters(it) }
                    redirectRouter("homegap-mgmt-redirect", "Host(`${desired.managementHost}`)", TOP_PRIORITY)
                    httpsRedirectRouter(desired)
                    healthRouters(desired, resourceIds, cluster)
                }
                putJsonObject("services") {
                    for (shared in sharedNames) {
                        putJsonObject(shared.key) { selfLoadBalancer(shared.key) }
                    }
                    putJsonObject(PANGOLIN_API) { loadBalancer("http://pangolin:3000") }
                    putJsonObject(PANGOLIN_NEXT) { loadBalancer("http://pangolin:3002") }
                    putJsonObject(UNAVAILABLE) { loadBalancer("http://127.0.0.1:9") }
                }
                if (sharedNames.isNotEmpty()) {
                    putJsonObject("serversTransports") {
                        for (shared in sharedNames) {
                            putJsonObject(shared.key) {
                                put("serverName", shared.own)
                                // The connection goes to the :443 of this node. From there it goes,
                                // still encrypted, through the front door and the tunnel to the
                                // Traefik of the homelab. The node already ends the TLS of the
                                // visitor for this name, and there is no other hop here.
                                put("insecureSkipVerify", true)
                            }
                        }
                    }
                }
            }
            if (sniRoutes.isNotEmpty() || proxyTransport) {
                putJsonObject("tcp") { tcpRoutes(sniRoutes, proxyTransport, cluster) }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    private fun JsonObjectBuilder.httpMiddlewares(desired: NodeDesired, sharedNames: List<SharedName>) {
        putJsonObject(REDIRECT_HTTPS) {
            putJsonObject("redirectScheme") {
                put("scheme", "https")
                put("permanent", true)
            }
        }
        putJsonObject(HEALTH_PATH) { putJsonObject("replacePath") { put("path", desired.healthPath) } }
        // The status page needs no token, so one address gets a limit on it.
        if (desired.statusRedirect != null) {
            putJsonObject(STATUS_RATE_LIMIT) {
                putJsonObject("rateLimit") {
                    put("average", STATUS_RATE)
                    put("burst", STATUS_BURST)
                }
            }
        }
        // The requests for a shared name continue under the own name of this node (see [selfLoadBalancer]).
        for (shared in sharedNames) {
            putJsonObject(shared.key) {
                putJsonObject("headers") { putJsonObject("customRequestHeaders") { put("Host", shared.own) } }
            }
        }
    }

    /**
     * The router for the names that this node passes through, when a request for one comes on a
     * connection that the node ends itself. A browser uses such a connection again, because the
     * names share an IP address and a wildcard certificate. The TLS options are different, so
     * Traefik answers 421 and the browser connects again.
     */
    private fun JsonObjectBuilder.misdirectedRouter(rule: String) {
        putJsonObject(MISDIRECTED_ROUTER) {
            put("rule", rule)
            putJsonArray("entryPoints") { add(WEBSECURE) }
            put("service", NOOP_SERVICE)
            put("priority", MISDIRECTED_PRIORITY)
            putJsonObject("tls") { put("options", ELSEWHERE_TLS_OPTIONS) }
        }
    }

    /** The routers for the API and the pages of Pangolin, on the management host and on the client host. */
    private fun JsonObjectBuilder.pangolinRouters(desired: NodeDesired) {
        val managementHost = desired.managementHost
        httpsRouter("homegap-mgmt-api", apiRoute(managementHost))
        if (desired.node.dashboardPublic) {
            httpsRouter("homegap-mgmt-ui", pagesRoute(managementHost))
        }
        // The client address of the cluster: the login pages of Pangolin and the API (with the
        // client websocket) on each node.
        desired.clientHost?.let { client ->
            httpsRouter("homegap-client-api", apiRoute(client))
            httpsRouter("homegap-client-ui", pagesRoute(client))
            redirectRouter("homegap-client-redirect", "Host(`$client`)", TOP_PRIORITY)
        }
    }

    private fun apiRoute(host: String) = HttpRoute(
        rule = "Host(`$host`) && PathPrefix(`/api/v1`)",
        service = PANGOLIN_API,
        priority = TOP_PRIORITY,
    )

    private fun pagesRoute(host: String) = HttpRoute(
        rule = "Host(`$host`)",
        service = PANGOLIN_NEXT,
        priority = UI_PRIORITY,
    )

    /**
     * The routers for a shared name (status.<domain>, speedtest.<domain>). The node that DNS
     * selects answers the name with its own certificate. It sends each request on under its own
     * name, so the page knows the node that the visitor came through. The address bar keeps the
     * shared name.
     */
    private fun JsonObjectBuilder.sharedNameRouters(shared: SharedName) {
        val rule = "Host(`${shared.shared}`)"
        val middlewares = buildList {
            if (shared.name == "status") add(STATUS_RATE_LIMIT)
            add(shared.key)
        }
        val route = HttpRoute(rule = rule, service = shared.key, priority = TOP_PRIORITY, middlewares = middlewares)
        httpsRouter(shared.key, route)
        redirectRouter("${shared.key}-http", rule, TOP_PRIORITY)
    }

    private fun JsonObjectBuilder.httpsRedirectRouter(desired: NodeDesired) {
        val hosts = buildList {
            desired.httpsRedirectHosts.forEach { add("Host(`$it`)") }
            desired.wildcardRedirectDomain?.let { add("HostRegexp(`${wildcardRegex(it)}`)") }
        }
        if (hosts.isNotEmpty()) {
            redirectRouter("homegap-https-redirect", hosts.joinToString(" || "), HTTPS_REDIRECT_PRIORITY)
        }
    }

    /** The health endpoint on the IP addresses of the node, on port 80. */
    private fun JsonObjectBuilder.healthRouters(desired: NodeDesired, resourceIds: Map<String, Int>, cluster: Boolean) {
        val node = desired.node
        val ipHosts = listOfNotNull(node.ipv4(), node.publicIpv6?.let { "[$it]" })
        if (ipHosts.isEmpty()) return
        val ipRule = ipHosts.joinToString(" || ") { "Host(`$it`)" }
        val healthNiceId = desired.healthResourceNiceId
        val healthId = healthNiceId?.let { resourceIds[it] }
        if (healthNiceId != null && healthId != null) {
            val health = HttpRoute(
                rule = ipRule,
                service = pangolinService(healthId, healthNiceId, cluster),
                priority = HEALTH_PRIORITY,
                middlewares = listOf(HEALTH_PATH),
            )
            httpRouter("homegap-health", health)
        }
        // The fallback answers 502, so a health path that does not work shows as "offline".
        val fallback = HttpRoute(rule = ipRule, service = UNAVAILABLE, priority = HEALTH_FALLBACK_PRIORITY)
        httpRouter("homegap-health-fallback", fallback)
    }

    private fun JsonObjectBuilder.tcpRoutes(
        sniRoutes: List<Pair<SniRoute, Int>>,
        proxyTransport: Boolean,
        cluster: Boolean,
    ) {
        if (proxyTransport) {
            putJsonObject("serversTransports") {
                putJsonObject(PROXY_PROTOCOL_TRANSPORT) {
                    putJsonObject("proxyProtocol") { put("version", PROXY_PROTOCOL_VERSION) }
                }
            }
        }
        if (sniRoutes.isNotEmpty()) {
            putJsonObject("routers") {
                for ((route, resourceId) in sniRoutes) {
                    putJsonObject(route.name) {
                        putJsonArray("entryPoints") { add(WEBSECURE) }
                        put("rule", sniRule(route))
                        put("service", pangolinService(resourceId, route.resourceNiceId, cluster))
                        put("priority", route.priority)
                        putJsonObject("tls") { put("passthrough", true) }
                    }
                }
            }
        }
    }

    /** An HTTP router without its name and its entry point. */
    private class HttpRoute(
        val rule: String,
        val service: String,
        val priority: Int,
        val middlewares: List<String> = emptyList(),
    )

    /** A router on the HTTPS entry point. The node ends TLS for it. */
    private fun JsonObjectBuilder.httpsRouter(name: String, route: HttpRoute) {
        putJsonObject(name) {
            routerFields(route, WEBSECURE)
            putJsonObject("tls") {}
        }
    }

    /** A router on the HTTP entry point (port 80). */
    private fun JsonObjectBuilder.httpRouter(name: String, route: HttpRoute) {
        putJsonObject(name) { routerFields(route, WEB) }
    }

    /** A router on port 80 that sends the browser to HTTPS. */
    private fun JsonObjectBuilder.redirectRouter(name: String, rule: String, priority: Int) {
        val route = HttpRoute(
            rule = rule,
            service = NOOP_SERVICE,
            priority = priority,
            middlewares = listOf(REDIRECT_HTTPS),
        )
        httpRouter(name, route)
    }

    private fun JsonObjectBuilder.routerFields(route: HttpRoute, entryPoint: String) {
        put("rule", route.rule)
        putJsonArray("entryPoints") { add(entryPoint) }
        if (route.middlewares.isNotEmpty()) {
            putJsonArray("middlewares") { route.middlewares.forEach { add(it) } }
        }
        put("service", route.service)
        put("priority", route.priority)
    }

    /** A shared name on this node. [name] is `status` or `speedtest`. [own] is the name of this node for it. */
    private class SharedName(val name: String, val shared: String, val own: String) {
        /** The name of the router, the middleware, the service and the transport of the shared name. */
        val key = "homegap-$name-via"
    }

    /** The shared names of the status page and of the speed test on this node. */
    private fun sharedNamesOf(desired: NodeDesired): List<SharedName> = listOfNotNull(
        desired.statusRedirect?.let { (shared, own) -> SharedName("status", shared, own) },
        desired.speedtestRedirect?.let { (shared, own) -> SharedName("speedtest", shared, own) },
    )

    /**
     * A load balancer that goes back into the :443 of this node under the own name of the node.
     * The front door carries that name to the homelab, as it does each other name under the domain.
     */
    private fun JsonObjectBuilder.selfLoadBalancer(transport: String) {
        putJsonObject("loadBalancer") {
            putJsonArray("servers") { addJsonObject { put("url", "https://127.0.0.1:443") } }
            put("serversTransport", transport)
        }
    }

    private fun JsonObjectBuilder.loadBalancer(url: String) {
        putJsonObject("loadBalancer") { putJsonArray("servers") { addJsonObject { put("url", url) } } }
    }

    /** The TLS options of the routes for names that another server answers (any name but "default"). */
    const val ELSEWHERE_TLS_OPTIONS = "homegap-elsewhere"
    const val MISDIRECTED_ROUTER = "homegap-misdirected"

    /**
     * A name that nobody asks for (`.invalid` is reserved). A route keeps its own TLS options only
     * if its rule has one plain Host() or more. A plain Host() for a passthrough name takes that
     * name from its SNI route, so the rule uses this name.
     */
    const val MISDIRECTED_ANCHOR = "homegap-misdirected.invalid"

    /**
     * The rule that matches each name that the node passes through ([routes]), as patterns, or
     * null if there are no names. The rule does not match the [own] names of the node, also where
     * a passthrough wildcard covers them. Thus a path that the node does not route there gets a
     * 404, not a 421. Traefik ignores a negated Host() when it selects the TLS options, so the
     * exception does not change the certificates of those names.
     */
    fun misdirectedRule(routes: List<SniRoute>, own: List<String> = emptyList()): String? {
        val patterns = routes.flatMap { route ->
            val exact = route.exactHosts.map { "(?i)^" + it.replace(".", "\\.") + "$" }
            exact + listOfNotNull(route.wildcardOf?.let(::wildcardRegex))
        }.distinct()
        if (patterns.isEmpty()) return null
        val hosts = listOf("Host(`$MISDIRECTED_ANCHOR`)") + patterns.map { "HostRegexp(`$it`)" }
        val matches = hosts.joinToString(" || ")
        val except = own.map { it.lowercase() }.distinct()
        return if (except.isEmpty()) matches else "($matches)" + except.joinToString("") { " && !Host(`$it`)" }
    }

    /** The names that this node serves itself: its management host, the client host, the shared names. */
    private fun ownNames(desired: NodeDesired): List<String> = buildList {
        add(desired.managementHost)
        desired.clientHost?.let(::add)
        for (shared in sharedNamesOf(desired)) {
            add(shared.shared)
            add(shared.own)
        }
    }

    /** A pattern that matches one label before [domain], as the wildcard of Pangolin does. */
    fun wildcardRegex(domain: String): String =
        "(?i)^[a-z0-9]([a-z0-9-]*[a-z0-9])?\\." + domain.replace(".", "\\.") + "$"

    fun sniRule(route: SniRoute): String {
        val include = buildList {
            route.exactHosts.forEach { add("HostSNI(`$it`)") }
            route.wildcardOf?.let { add("HostSNIRegexp(`${wildcardRegex(it)}`)") }
        }.joinToString(" || ")
        if (route.excludeHosts.isEmpty()) return include
        val exclude = route.excludeHosts.joinToString(" && ") { "!HostSNI(`$it`)" }
        return "($include) && $exclude"
    }

    /**
     * Points Traefik at the certificate files. Traefik watches this file and not the certificate
     * files, so the [hash] of the certificate is in the header: a new certificate changes the file.
     */
    fun tlsDynamic(hash: String): String = YamlEmitter.emit(
        buildJsonObject {
            putJsonObject("tls") {
                putJsonArray("certificates") {
                    addJsonObject {
                        put("certFile", "/etc/traefik/certs/homegap.crt")
                        put("keyFile", "/etc/traefik/certs/homegap.key")
                    }
                }
            }
        },
        "$HEADER\ncertificate $hash",
    )
}
