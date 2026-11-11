package homegap.provision

import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.model.NodeDesired
import homegap.model.RawPort
import homegap.model.SniRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Secrets that live on (and are authoritative on) each node, pushed by the orchestrator. */
@Serializable
data class NodeSecrets(
    val setupToken: String,
    val serverSecret: String,
    val adminEmail: String,
    val adminPassword: String,
    val crowdsecBouncerKey: String,
    /** The server secret and admin are the cluster's, shared by every node. */
    val cluster: Boolean = false,
)

/**
 * What a node's stack needs to run as part of the cluster: its mesh address and the shared
 * database passwords. Pangolin reaches the current primaries through `cluster-proxy` and reads from
 * the node's own database member.
 */
data class ClusterLink(val meshIp: String, val db: ClusterSecrets)

data class RenderedFile(val path: String, val content: String, val mode: String = "0644") {
    /** Restart the named compose service when this file changes (bind-mounted config). */
    var restarts: String? = null
}

/**
 * Renders the regional node stack: Pangolin EE + Gerbil + Traefik (+ optional CrowdSec).
 * Paths are relative to the node's install dir.
 */
object NodeTemplates {
    /** Traefik's metrics entry point on each node, on the container's loopback only. */
    const val METRICS_ENTRY_POINT = "metrics"
    const val METRICS_PORT = 8082

    /** Prints `M <requests> <response bytes>`, summed over the entry points, when Traefik answers. */
    val TRAFFIC_CMD = "docker exec traefik wget -qO- http://127.0.0.1:$METRICS_PORT/metrics 2>/dev/null | " +
        "awk '/^traefik_entrypoint_requests_total/{r+=\$NF} /^traefik_entrypoint_responses_bytes_total/{b+=\$NF} " +
        "END{if (NR>0) printf \"M %.0f %.0f\\n\", r, b}'"

    const val DYNAMIC_ROUTES = "config/traefik/dynamic/homegap-routes.yml"

    /**
     * PROXY protocol version HomeGAP asks Pangolin for. Pangolin points such a TCP service at its
     * `pp-transport-v<N>@file` transport, which it defines itself only in a file on clustered
     * nodes; standalone nodes get it from HomeGAP's routes file.
     */
    const val PROXY_PROTOCOL_VERSION = 2
    const val PROXY_PROTOCOL_TRANSPORT = "pp-transport-v$PROXY_PROTOCOL_VERSION"
    const val DYNAMIC_TLS = "config/traefik/dynamic/homegap-tls.yml"
    const val CERT = "config/traefik/certs/homegap.crt"
    const val KEY = "config/traefik/certs/homegap.key"
    const val SECRETS = "secrets/node.json"
    /** Pangolin's own routes in a cluster (file mode), next to HomeGAP's in the dynamic directory. */
    const val PANGOLIN_ROUTES = "/var/dynamic/router_config.yml"
    /** Under the node's `config/`, which Pangolin sees as `/app/config`. */
    const val AI_CATALOG = "config/ai-model-catalog.json"
    const val GERBIL_API_PORT = 3004
    private val PRETTY_JSON = Json { prettyPrint = true }
    /** Rendered into the install dir (so it is hashed and drift-checked), installed into /etc/sysctl.d. */
    const val SYSCTL = "config/homegap-sysctl.conf"

    private val HEADER = "Managed by HomeGAP. Manual edits are reverted on the next reconcile\nunless this node is paused from the dashboard."

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
            RenderedFile("config/config.yml", pangolinConfig(config, node, secrets, slot, cluster), "0600").apply { restarts = "pangolin" },
            RenderedFile("config/traefik/traefik_config.yml", traefikStatic(config, rawPorts, cluster != null)).apply { restarts = "traefik" },
            RenderedFile(SECRETS, PRETTY_JSON.encodeToString(secrets), "0600"),
            RenderedFile(AI_CATALOG, OutsideCalls.EMPTY_AI_CATALOG),
        )
        files += RenderedFile("${MaintenancePage.DIR}/server.py", MaintenancePage.SCRIPT).apply { restarts = MaintenancePage.SERVICE }
        files += RenderedFile("${MaintenancePage.DIR}/index.html", MaintenancePage.page(config.domain, node.id))
        if (cluster != null) {
            files += RenderedFile("config/privateConfig.yml", pangolinPrivateConfig(node, cluster), "0600").apply { restarts = "pangolin" }
        }
        if (config.pangolin.crowdsec) {
            files += RenderedFile("config/crowdsec/acquis.d/traefik.yaml", YamlEmitter.emit(buildJsonObject {
                put("poll_without_inotify", false)
                putJsonArray("filenames") { add("/var/log/traefik/*.log") }
                putJsonObject("labels") { put("type", "traefik") }
            }, HEADER)).apply { restarts = "crowdsec" }
            files += RenderedFile("config/traefik/dynamic/homegap-crowdsec.yml", crowdsecDynamic(secrets), "0600")
        }
        return files
    }

    fun compose(config: AppConfig, rawPorts: List<RawPort>, cluster: ClusterLink? = null): String {
        val p = config.pangolin
        val root = buildJsonObject {
            put("name", "homegap")
            putJsonObject("services") {
                putJsonObject("pangolin") {
                    put("image", p.pangolinImage(cluster != null))
                    put("container_name", "pangolin")
                    put("restart", "unless-stopped")
                    putJsonArray("env_file") { add(".env") }
                    putJsonArray("extra_hosts") { OutsideCalls.PANGOLIN.forEach { add("$it=${OutsideCalls.NOWHERE}") } }
                    putJsonArray("volumes") {
                        add("./config:/app/config")
                        if (cluster != null) {
                            // File mode: Pangolin writes its routes where Traefik's file provider reads.
                            add("./config/traefik/dynamic:/var/dynamic")
                            add("./config/certificates:/var/certificates")
                        }
                    }
                    if (cluster != null) putJsonArray("networks") { add("default"); add(CLUSTER_NETWORK_KEY) }
                    // Loopback only: reached by the orchestrator through SSH port-forwarding.
                    putJsonArray("ports") {
                        add("127.0.0.1:3000:3000"); add("127.0.0.1:3002:3002"); add("127.0.0.1:3003:3003")
                    }
                    putJsonObject("healthcheck") {
                        putJsonArray("test") { add("CMD"); add("curl"); add("-f"); add("http://localhost:3001/api/v1/") }
                        put("interval", "10s"); put("timeout", "10s"); put("retries", 15)
                    }
                }
                putJsonObject("gerbil") {
                    put("image", p.gerbilImage)
                    put("container_name", "gerbil")
                    put("restart", "unless-stopped")
                    putJsonObject("depends_on") { putJsonObject("pangolin") { put("condition", "service_healthy") } }
                    putJsonArray("command") {
                        // In a cluster, every node's Pangolin reaches this Gerbil over the mesh.
                        add("--reachableAt=http://${cluster?.meshIp ?: "gerbil"}:$GERBIL_API_PORT")
                        add("--generateAndSaveKeyTo=/var/config/key")
                        add("--remoteConfig=http://pangolin:3001/api/v1/")
                        config.tunnels.mtu?.let { add("--mtu=$it") }
                    }
                    putJsonArray("volumes") { add("./config/:/var/config") }
                    putJsonArray("cap_add") { add("NET_ADMIN"); add("SYS_MODULE") }
                    // TCP settings are per network namespace: the host's sysctl.d does not reach
                    // containers. Traefik shares this namespace, so this is where every
                    // node -> homelab and node -> viewer connection is sent from. Congestion
                    // control is inherited from the host when the namespace is created.
                    if (p.tcpTuning) putJsonObject("sysctls") { CONTAINER_SYSCTLS.forEach { (k, v) -> put(k, v) } }
                    putJsonArray("ports") {
                        add("51820:51820/udp"); add("21820:21820/udp")
                        add("443:443"); add("443:443/udp"); add("80:80")
                        rawPorts.forEach { add("${it.port}:${it.port}/${it.protocol}") }
                        // The mesh address only: Gerbil's API must never be public.
                        cluster?.let { add("${it.meshIp}:$GERBIL_API_PORT:$GERBIL_API_PORT") }
                    }
                }
                putJsonObject("traefik") {
                    put("image", p.traefikImage)
                    put("container_name", "traefik")
                    put("restart", "unless-stopped")
                    put("network_mode", "service:gerbil")
                    putJsonObject("depends_on") { putJsonObject("pangolin") { put("condition", "service_healthy") } }
                    putJsonArray("command") { add("--configFile=/etc/traefik/traefik_config.yml") }
                    putJsonArray("volumes") {
                        add("./config/traefik:/etc/traefik:ro")
                        add("./config/letsencrypt:/letsencrypt")
                        add("./config/traefik/logs:/var/log/traefik")
                        if (cluster != null) add("./config/certificates:/var/certificates:ro")
                    }
                }
                putJsonObject(MaintenancePage.SERVICE) {
                    // A clustered node already has the cluster image (with Python); only a
                    // standalone node pulls an image just for the page.
                    put("image", config.cluster?.takeIf { cluster != null }?.image ?: p.maintenanceImage)
                    put("container_name", "homegap-maintenance")
                    put("restart", "unless-stopped")
                    put("stop_grace_period", "2s")
                    // Gerbil's namespace: the tunnel addresses it checks only route there, and
                    // Traefik (also in it) reaches the page on loopback.
                    put("network_mode", "service:gerbil")
                    putJsonObject("depends_on") { putJsonObject("gerbil") { put("condition", "service_started") } }
                    putJsonArray("entrypoint") { add("python3"); add("-u"); add("/maintenance/server.py") }
                    putJsonArray("volumes") {
                        add("./${MaintenancePage.DIR}:/maintenance:ro")
                        add("./config/traefik/dynamic:/dynamic")
                    }
                }
                if (p.crowdsec) {
                    putJsonObject("crowdsec") {
                        put("image", p.crowdsecImage)
                        put("container_name", "crowdsec")
                        put("restart", "unless-stopped")
                        putJsonObject("environment") {
                            put("GID", "1000")
                            put("COLLECTIONS", "crowdsecurity/traefik crowdsecurity/appsec-virtual-patching crowdsecurity/appsec-generic-rules")
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
                }
            }
            putJsonObject("networks") {
                putJsonObject("default") { put("driver", "bridge"); put("name", "homegap_node") }
                // Created by the node's cluster member (its own compose project) before the stack starts.
                if (cluster != null) putJsonObject(CLUSTER_NETWORK_KEY) { put("external", true); put("name", ClusterTemplates.NETWORK) }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    /**
     * TCP tuning for long fat paths (viewer <-> node <-> homelab): BBR keeps throughput up under
     * loss and high RTT, larger buffers let windows grow to the bandwidth-delay product, MTU
     * probing recovers from black-holed PMTU discovery across the tunnel. Higher reordering
     * thresholds stop long routes that reorder packets (common across aggregated links) from
     * being mistaken for loss, which would otherwise cut the congestion window.
     */
    /** Namespaced subset of [sysctlConf] applied to the node's edge containers. */
    val CONTAINER_SYSCTLS = linkedMapOf(
        "net.ipv4.tcp_rmem" to "4096 131072 67108864",
        "net.ipv4.tcp_wmem" to "4096 65536 67108864",
        "net.ipv4.tcp_mtu_probing" to "1",
        "net.ipv4.tcp_slow_start_after_idle" to "0",
        "net.ipv4.tcp_reordering" to "10",
        "net.ipv4.tcp_max_reordering" to "1000",
    )

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

    private const val CLUSTER_NETWORK_KEY = "cluster"

    fun pangolinConfig(config: AppConfig, node: NodeConfig, secrets: NodeSecrets, slot: Int, cluster: ClusterLink? = null): String {
        val mgmt = node.managementHost(config.domain)
        // In a cluster, links and redirects use the shared client address.
        val dashboard = config.clientHost.takeIf { cluster != null } ?: mgmt
        val root = buildJsonObject {
            putJsonObject("gerbil") {
                put("start_port", 51820)
                put("base_endpoint", mgmt)
                put("exit_node_name", "homegap-${node.id}")
                // Distinct tunnel subnet per node (its permanent slot), so the homelab can hold
                // every node's tunnel in one network namespace (kernel WireGuard routes would
                // collide otherwise). Slot 0 keeps Pangolin's default first block.
                put("subnet_group", homegap.reconcile.TunnelSlots.subnet(slot))
            }
            putJsonObject("app") {
                put("dashboard_url", "https://$dashboard")
                put("log_level", "info")
                putJsonObject("telemetry") { put("anonymous_usage", false) }
                // The dashboard would otherwise ask Fossorial's API for product news and the newest version.
                putJsonObject("notifications") { put("product_updates", false); put("new_releases", false) }
            }
            // The model catalog (AI pricing) would otherwise be downloaded from Fossorial every few hours.
            putJsonObject("ai") { putJsonObject("model_catalog") { put("file", "/app/$AI_CATALOG") } }
            putJsonObject("domains") {
                putJsonObject("homegap") {
                    put("base_domain", config.domain)
                    put("cert_resolver", "letsencrypt")
                    put("prefer_wildcard_cert", config.certificates.source != "none")
                }
            }
            if (cluster != null) {
                putJsonObject("postgres") {
                    // Writes go to the current primary through HAProxy; reads use this node's member.
                    put("connection_string", postgresUrl(cluster, ClusterTemplates.PROXY_HOST, ClusterTemplates.PROXY_POSTGRES_PORT))
                    putJsonArray("replicas") {
                        addJsonObject { put("connection_string", postgresUrl(cluster, cluster.meshIp, ClusterTemplates.POSTGRES_PORT)) }
                    }
                }
            }
            putJsonObject("server") {
                put("secret", secrets.serverSecret)
                putJsonObject("cors") {
                    putJsonArray("origins") { listOf(dashboard, mgmt).distinct().forEach { add("https://$it") } }
                    putJsonArray("methods") { listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { add(it) } }
                    putJsonArray("allowed_headers") { add("X-CSRF-Token"); add("Content-Type") }
                    put("credentials", false)
                }
            }
            putJsonObject("traefik") {
                put("cert_resolver", "letsencrypt")
                put("http_entrypoint", "web")
                put("https_entrypoint", "websecure")
                if (cluster != null) {
                    // Each node writes the routes for its own sites; Traefik never polls a Pangolin.
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
     * The cluster settings Pangolin reads from `privateConfig.yml`: the node's region (which pins
     * the sites created through it to its own exit node), Valkey, and its local Gerbil.
     */
    fun pangolinPrivateConfig(node: NodeConfig, cluster: ClusterLink): String = YamlEmitter.emit(buildJsonObject {
        putJsonObject("app") { put("region", node.id) }
        putJsonObject("redis") {
            put("host", ClusterTemplates.PROXY_HOST)
            put("port", ClusterTemplates.PROXY_VALKEY_PORT)
            put("password", cluster.db.valkey)
        }
        putJsonObject("gerbil") { put("local_exit_node_reachable_at", "http://gerbil:$GERBIL_API_PORT") }
        putJsonObject("flags") { put("enable_redis", true) }
    }, HEADER)

    private fun postgresUrl(cluster: ClusterLink, host: String, port: Int) =
        "postgresql://postgres:${cluster.db.postgres}@$host:$port/postgres"

    /** The Traefik service Pangolin generates for a resource, as HomeGAP's routes reference it. */
    fun pangolinService(resourceId: Int, niceId: String, cluster: Boolean) =
        "$resourceId-$niceId-service@${if (cluster) "file" else "http"}"

    fun traefikStatic(config: AppConfig, rawPorts: List<RawPort>, cluster: Boolean = false): String {
        val p = config.pangolin
        val email = p.acmeEmail ?: adminEmail(config)
        val root = buildJsonObject {
            // No version check (update.traefik.io) and no usage data.
            putJsonObject("global") { put("checkNewVersion", false); put("sendAnonymousUsage", false) }
            putJsonObject("api") { put("insecure", false); put("dashboard", false) }
            putJsonObject("providers") {
                if (!cluster) putJsonObject("http") {
                    put("endpoint", "http://pangolin:3001/api/v1/traefik-config")
                    put("pollInterval", "5s")
                }
                putJsonObject("file") {
                    put("directory", "/etc/traefik/dynamic")
                    put("watch", true)
                }
            }
            putJsonObject("experimental") {
                putJsonObject("plugins") {
                    putJsonObject("badger") { put("moduleName", "github.com/fosrl/badger"); put("version", p.badgerVersion) }
                    if (p.crowdsec) {
                        putJsonObject("crowdsec") {
                            put("moduleName", "github.com/maxlerebourg/crowdsec-bouncer-traefik-plugin")
                            put("version", p.crowdsecBouncerVersion)
                        }
                    }
                }
            }
            putJsonObject("log") { put("level", "INFO"); put("format", if (p.crowdsec) "json" else "common") }
            if (p.crowdsec) {
                putJsonObject("accessLog") { put("filePath", "/var/log/traefik/access.log"); put("format", "json") }
            }
            putJsonObject("certificatesResolvers") {
                putJsonObject("letsencrypt") {
                    putJsonObject("acme") {
                        putJsonObject("httpChallenge") { put("entryPoint", "web") }
                        put("email", email)
                        put("storage", "/letsencrypt/acme.json")
                        put("caServer", "https://acme-v02.api.letsencrypt.org/directory")
                    }
                }
            }
            putJsonObject("entryPoints") {
                putJsonObject("web") { put("address", ":80") }
                putJsonObject("websecure") {
                    put("address", ":443")
                    putJsonObject("transport") { putJsonObject("respondingTimeouts") { put("readTimeout", "30m") } }
                    putJsonObject("http3") { put("advertisedPort", 443) }
                    putJsonObject("http") {
                        putJsonObject("tls") { put("certResolver", "letsencrypt") }
                        if (p.crowdsec) putJsonArray("middlewares") { add("crowdsec@file") }
                        putJsonObject("encodedCharacters") {
                            put("allowEncodedSlash", true)
                            put("allowEncodedQuestionMark", true)
                        }
                    }
                }
                for (rp in rawPorts) {
                    putJsonObject(rp.entryPoint) { put("address", ":${rp.port}/${rp.protocol}") }
                }
                // Request and byte counters for the dashboard, read with `docker exec`: bound to the
                // container's own loopback, so nothing outside the container can reach it.
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
            // The hop to the origin runs inside the WireGuard tunnel to our own Traefik.
            // Short dial timeout: a dead tunnel answers 502 fast instead of hanging health probes.
            putJsonObject("serversTransport") {
                put("insecureSkipVerify", true)
                putJsonObject("forwardingTimeouts") { put("dialTimeout", "5s") }
            }
            putJsonObject("ping") { put("entryPoint", "web") }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    private fun crowdsecDynamic(secrets: NodeSecrets): String = YamlEmitter.emit(buildJsonObject {
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
                            put("crowdsecAppsecUnreachableBlock", false)
                            put("crowdsecLapiKey", secrets.crowdsecBouncerKey)
                            put("crowdsecLapiHost", "crowdsec:8080")
                            put("crowdsecLapiScheme", "http")
                        }
                    }
                }
            }
        }
    }, HEADER)

    /**
     * The hot-reloaded routing file: management API, SNI passthrough routers, HTTP->HTTPS
     * redirects and the port-80 health endpoint probed by Bunny's monitor.
     *
     * [resourceIds] maps Pangolin resource niceIds to numeric ids (Pangolin's generated Traefik
     * service names embed them); routes whose resource does not exist yet are omitted.
     */
    fun dynamicRoutes(desired: NodeDesired, resourceIds: Map<String, Int>, cluster: Boolean = false): String {
        val node = desired.node
        val mgmt = desired.managementHost
        val sni = desired.sniRoutes.mapNotNull { r -> resourceIds[r.resourceNiceId]?.let { r to it } }
        val root = buildJsonObject {
            if (misdirectedRule(sni.map { it.first }, ownNames(desired)) != null) putJsonObject("tls") {
                putJsonObject("options") { putJsonObject(ELSEWHERE_TLS_OPTIONS) { put("minVersion", "VersionTLS12") } }
            }
            putJsonObject("http") {
                putJsonObject("middlewares") {
                    putJsonObject("homegap-redirect-https") { putJsonObject("redirectScheme") { put("scheme", "https"); put("permanent", true) } }
                    putJsonObject("homegap-health-path") { putJsonObject("replacePath") { put("path", desired.healthPath) } }
                    // A shared name's requests continue under this node's own name (see [selfVia]).
                    for ((name, pair) in sharedNames(desired)) putJsonObject("homegap-$name-via") {
                        putJsonObject("headers") { putJsonObject("customRequestHeaders") { put("Host", pair.second) } }
                    }
                }
                putJsonObject("routers") {
                    // Names this node passes through, asked for on a connection the node ends itself
                    // (a browser reusing it, the names sharing an IP and a wildcard certificate):
                    // the TLS options differ, so Traefik answers 421 and the browser reconnects.
                    misdirectedRule(sni.map { it.first }, ownNames(desired))?.let { rule ->
                        putJsonObject(MISDIRECTED_ROUTER) {
                            put("rule", rule)
                            putJsonArray("entryPoints") { add("websecure") }
                            put("service", "noop@internal")
                            put("priority", 1)
                            putJsonObject("tls") { put("options", ELSEWHERE_TLS_OPTIONS) }
                        }
                    }
                    putJsonObject("homegap-mgmt-api") {
                        put("rule", "Host(`$mgmt`) && PathPrefix(`/api/v1`)")
                        putJsonArray("entryPoints") { add("websecure") }
                        put("service", "homegap-pangolin-api")
                        put("priority", 10000)
                        putJsonObject("tls") { put("certResolver", "letsencrypt") }
                    }
                    if (node.dashboardPublic) {
                        putJsonObject("homegap-mgmt-ui") {
                            put("rule", "Host(`$mgmt`)")
                            putJsonArray("entryPoints") { add("websecure") }
                            put("service", "homegap-pangolin-next")
                            put("priority", 9999)
                            putJsonObject("tls") { put("certResolver", "letsencrypt") }
                        }
                    }
                    desired.clientHost?.let { client ->
                        // The cluster's client address: Pangolin's login pages and the API (with
                        // the client websocket) on every node.
                        putJsonObject("homegap-client-api") {
                            put("rule", "Host(`$client`) && PathPrefix(`/api/v1`)")
                            putJsonArray("entryPoints") { add("websecure") }
                            put("service", "homegap-pangolin-api")
                            put("priority", 10000)
                            putJsonObject("tls") { put("certResolver", "letsencrypt") }
                        }
                        putJsonObject("homegap-client-ui") {
                            put("rule", "Host(`$client`)")
                            putJsonArray("entryPoints") { add("websecure") }
                            put("service", "homegap-pangolin-next")
                            put("priority", 9999)
                            putJsonObject("tls") { put("certResolver", "letsencrypt") }
                        }
                        putJsonObject("homegap-client-redirect") {
                            put("rule", "Host(`$client`)")
                            putJsonArray("entryPoints") { add("web") }
                            putJsonArray("middlewares") { add("homegap-redirect-https") }
                            put("service", "noop@internal")
                            put("priority", 10000)
                        }
                    }
                    for ((name, pair) in sharedNames(desired)) {
                        val (from, _) = pair
                        // The shared name (status.<domain>, speedtest.<domain>): whichever node DNS
                        // picked answers it with its own certificate and sends each request on under
                        // its own name, so the page knows the node the visitor came through. The
                        // address bar keeps the shared name.
                        putJsonObject("homegap-$name-via") {
                            put("rule", "Host(`$from`)")
                            putJsonArray("entryPoints") { add("websecure") }
                            putJsonArray("middlewares") { add("homegap-$name-via") }
                            put("service", "homegap-$name-via")
                            put("priority", 10000)
                            putJsonObject("tls") { put("certResolver", "letsencrypt") }
                        }
                        putJsonObject("homegap-$name-via-http") {
                            put("rule", "Host(`$from`)")
                            putJsonArray("entryPoints") { add("web") }
                            putJsonArray("middlewares") { add("homegap-redirect-https") }
                            put("service", "noop@internal")
                            put("priority", 10000)
                        }
                    }
                    putJsonObject("homegap-mgmt-redirect") {
                        put("rule", "Host(`$mgmt`)")
                        putJsonArray("entryPoints") { add("web") }
                        putJsonArray("middlewares") { add("homegap-redirect-https") }
                        put("service", "noop@internal")
                        put("priority", 10000)
                    }
                    val redirectRule = buildList {
                        desired.httpsRedirectHosts.forEach { add("Host(`$it`)") }
                        desired.wildcardRedirectDomain?.let { add("HostRegexp(`${wildcardRegex(it)}`)") }
                    }
                    if (redirectRule.isNotEmpty()) {
                        putJsonObject("homegap-https-redirect") {
                            put("rule", redirectRule.joinToString(" || "))
                            putJsonArray("entryPoints") { add("web") }
                            putJsonArray("middlewares") { add("homegap-redirect-https") }
                            put("service", "noop@internal")
                            put("priority", 10)
                        }
                    }
                    val ipHosts = listOfNotNull(node.ipv4(), node.publicIpv6?.let { "[$it]" })
                    if (ipHosts.isNotEmpty()) {
                        val ipRule = ipHosts.joinToString(" || ") { "Host(`$it`)" }
                        val healthNiceId = desired.healthResourceNiceId
                        val healthId = healthNiceId?.let { resourceIds[it] }
                        if (healthNiceId != null && healthId != null) {
                            putJsonObject("homegap-health") {
                                put("rule", ipRule)
                                putJsonArray("entryPoints") { add("web") }
                                putJsonArray("middlewares") { add("homegap-health-path") }
                                put("service", pangolinService(healthId, healthNiceId, cluster))
                                put("priority", 60)
                            }
                        }
                        // Fallback answers 502 so a broken health path reads as "offline".
                        putJsonObject("homegap-health-fallback") {
                            put("rule", ipRule)
                            putJsonArray("entryPoints") { add("web") }
                            put("service", "homegap-unavailable")
                            put("priority", 50)
                        }
                    }
                }
                putJsonObject("services") {
                    for ((name, _) in sharedNames(desired)) putJsonObject("homegap-$name-via") { selfVia("homegap-$name-via") }
                    putJsonObject("homegap-pangolin-api") { lb("http://pangolin:3000") }
                    putJsonObject("homegap-pangolin-next") { lb("http://pangolin:3002") }
                    putJsonObject("homegap-unavailable") { lb("http://127.0.0.1:9") }
                }
                if (sharedNames(desired).isNotEmpty()) putJsonObject("serversTransports") {
                    for ((name, pair) in sharedNames(desired)) putJsonObject("homegap-$name-via") {
                        put("serverName", pair.second)
                        // The connection goes to this node's own :443 and from there, still encrypted,
                        // through the front door and the tunnel to the homelab Traefik. The node
                        // already ends the visitor's TLS for this name; there is no other hop here.
                        put("insecureSkipVerify", true)
                    }
                }
            }
            val transport = !cluster && desired.publicResources.any { it.proxyProtocol }
            if (sni.isNotEmpty() || transport) {
                putJsonObject("tcp") {
                    if (transport) putJsonObject("serversTransports") {
                        putJsonObject(PROXY_PROTOCOL_TRANSPORT) { putJsonObject("proxyProtocol") { put("version", PROXY_PROTOCOL_VERSION) } }
                    }
                    if (sni.isNotEmpty()) putJsonObject("routers") {
                        for ((route, id) in sni) {
                            putJsonObject(route.name) {
                                putJsonArray("entryPoints") { add("websecure") }
                                put("rule", sniRule(route))
                                put("service", pangolinService(id, route.resourceNiceId, cluster))
                                put("priority", route.priority)
                                putJsonObject("tls") { put("passthrough", true) }
                            }
                        }
                    }
                }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    /** The status page's and the speed test's shared names on this node: name -> (shared, own). */
    private fun sharedNames(desired: NodeDesired): List<Pair<String, Pair<String, String>>> =
        listOfNotNull(desired.statusRedirect?.let { "status" to it }, desired.speedtestRedirect?.let { "speedtest" to it })

    /**
     * Back into this node's own :443 under its own per-node name, which the front door carries to
     * the homelab like any other name under the domain.
     */
    private fun JsonObjectBuilder.selfVia(transport: String) {
        putJsonObject("loadBalancer") {
            putJsonArray("servers") { addJsonObject { put("url", "https://127.0.0.1:443") } }
            put("serversTransport", transport)
        }
    }

    private fun JsonObjectBuilder.lb(url: String) {
        putJsonObject("loadBalancer") { putJsonArray("servers") { addJsonObject { put("url", url) } } }
    }

    /** The TLS options of the routes for names served elsewhere (any name but "default"). */
    const val ELSEWHERE_TLS_OPTIONS = "homegap-elsewhere"
    const val MISDIRECTED_ROUTER = "homegap-misdirected"

    /**
     * Never asked for (`.invalid` is reserved): a route keeps its own TLS options only with at least
     * one plain Host() in its rule, and a plain Host() for a passthrough name would take that name
     * from its SNI route.
     */
    const val MISDIRECTED_ANCHOR = "homegap-misdirected.invalid"

    /**
     * The rule matching every name the node passes through ([routes]), as patterns, or null when
     * there are none. The node's [own] names are left out, even where a passthrough wildcard covers
     * them: a path the node does not route there gets a 404, not a 421. Traefik leaves negated
     * Host()s out when it picks TLS options, so this does not touch their certificates.
     */
    fun misdirectedRule(routes: List<SniRoute>, own: List<String> = emptyList()): String? {
        val patterns = routes.flatMap { r ->
            r.exactHosts.map { "(?i)^" + it.replace(".", "\\.") + "$" } + listOfNotNull(r.wildcardOf?.let(::wildcardRegex))
        }.distinct()
        if (patterns.isEmpty()) return null
        val matches = (listOf("Host(`$MISDIRECTED_ANCHOR`)") + patterns.map { "HostRegexp(`$it`)" }).joinToString(" || ")
        val except = own.map { it.lowercase() }.distinct()
        return if (except.isEmpty()) matches else "($matches)" + except.joinToString("") { " && !Host(`$it`)" }
    }

    /** The names this node serves itself: its management host, the client host, the shared names. */
    private fun ownNames(desired: NodeDesired): List<String> =
        listOf(desired.managementHost) + listOfNotNull(desired.clientHost) + sharedNames(desired).flatMap { it.second.toList() }

    /** Single-label wildcard match, mirroring Pangolin's wildcard semantics. */
    fun wildcardRegex(domain: String): String = "(?i)^[a-z0-9]([a-z0-9-]*[a-z0-9])?\\." + domain.replace(".", "\\.") + "$"

    fun sniRule(r: SniRoute): String {
        val include = buildList {
            r.exactHosts.forEach { add("HostSNI(`$it`)") }
            r.wildcardOf?.let { add("HostSNIRegexp(`${wildcardRegex(it)}`)") }
        }.joinToString(" || ")
        if (r.excludeHosts.isEmpty()) return include
        val exclude = r.excludeHosts.joinToString(" && ") { "!HostSNI(`$it`)" }
        return "($include) && $exclude"
    }

    fun tlsDynamic(): String = YamlEmitter.emit(buildJsonObject {
        putJsonObject("tls") {
            putJsonArray("certificates") {
                addJsonObject { put("certFile", "/etc/traefik/certs/homegap.crt"); put("keyFile", "/etc/traefik/certs/homegap.key") }
            }
        }
    }, HEADER)
}
