package homegap.provision

import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.model.MeshTarget
import homegap.model.NodeDesired
import homegap.model.PublicResource
import homegap.model.SniRoute
import homegap.node.AgentSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The secrets of a node that HomeGAP makes. The orchestrator keeps them and writes them to the node. */
@Serializable
data class NodeSecrets(
    /** The key of the CrowdSec bouncer in the Traefik of the node. */
    val crowdsecBouncerKey: String,
)

data class RenderedFile(val path: String, val content: String, val mode: String = "0644") {
    /** The compose service to restart after a change of this file (a bind-mounted config). */
    var restarts: String? = null
}

/**
 * Renders the stack of a node: Traefik, the maintenance page, the agent of HomeGAP, and CrowdSec
 * if the config enables it. The paths are relative to the install directory of the node.
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

    /** The PROXY protocol version that a node sends to the homelab. */
    const val PROXY_PROTOCOL_VERSION = 2

    private const val HTTPS = "https"
    private const val WILDCARD = "*."

    /** The transport of the streams over the mesh that carry a PROXY protocol header. */
    private const val MESH_PROXY_TRANSPORT = "homegap-mesh-proxy"
    const val DYNAMIC_TLS = "config/traefik/dynamic/homegap-tls.yml"
    const val DYNAMIC_HEADERS = "config/traefik/dynamic/homegap-headers.yml"
    const val HSTS = "homegap-hsts"
    private const val HSTS_SECONDS = 31_536_000

    /** Requests in a second from one address to the public status page of a node, and the burst. */
    private const val STATUS_RATE = 30
    private const val STATUS_BURST = 150
    const val CERT = "config/traefik/certs/homegap.crt"
    const val KEY = "config/traefik/certs/homegap.key"

    /**
     * HomeGAP renders this file into the install directory, so the file has a hash and a drift
     * check. The node installs it into /etc/sysctl.d.
     */
    const val SYSCTL = "config/homegap-sysctl.conf"

    private const val HEADER = "Managed by HomeGAP. Manual edits are reverted on the next reconcile\n" +
        "unless this node is paused from the dashboard."

    // Names in the routes file of HomeGAP.
    private const val WEB = "web"
    private const val WEBSECURE = "websecure"
    private const val NOOP_SERVICE = "noop@internal"
    private const val REDIRECT_HTTPS = "homegap-redirect-https"
    private const val HEALTH_PATH = "homegap-health-path"
    private const val STATUS_RATE_LIMIT = "homegap-status-rate"
    private const val UNAVAILABLE = "homegap-unavailable"

    // Router priorities. A higher number wins.
    private const val TOP_PRIORITY = 10000
    private const val RESOURCE_PRIORITY = 100
    private const val WILDCARD_PRIORITY = 90
    private const val AGENT_MOUNT = "/agent"
    private const val NODE_CONTROL = "homegap-node-control"
    private const val NODE_HEADER = "homegap-node"

    /** The request header with the id of the node that a request came through. */
    const val NODE_HEADER_NAME = "X-HomeGAP-Node"
    private const val SIGN_IN_CHECK_INTERVAL = "5s"
    private const val SIGN_IN_CHECK_TIMEOUT = "3s"
    private const val AGENT_STATE_DIR = "agent-state"
    private const val AGENT_STATE_MOUNT = "/agent-state"
    private const val HEALTH_PRIORITY = 60
    private const val HEALTH_FALLBACK_PRIORITY = 50
    private const val HTTPS_REDIRECT_PRIORITY = 10
    private const val MISDIRECTED_PRIORITY = 1

    /** The contact address of the ACME account when the config gives none. */
    fun adminEmail(config: AppConfig) = "admin@${config.domain}"

    fun staticFiles(config: AppConfig, node: NodeConfig, secrets: NodeSecrets): List<RenderedFile> {
        val stack = config.nodeStack
        val files = mutableListOf(
            RenderedFile("compose.yml", compose(config)),
            RenderedFile(".env", envFile(secrets, stack.crowdsec), "0600"),
            RenderedFile("config/traefik/traefik_config.yml", traefikStatic(config)).restarting("traefik"),
        )
        files += RenderedFile(DYNAMIC_HEADERS, headersDynamic())
        files += RenderedFile("${MaintenancePage.DIR}/server.py", MaintenancePage.SCRIPT)
            .restarting(MaintenancePage.SERVICE)
        files += RenderedFile("${MaintenancePage.DIR}/index.html", MaintenancePage.page(config.domain, node.id))
        if (stack.crowdsec) {
            files += RenderedFile("config/crowdsec/acquis.d/traefik.yaml", crowdsecAcquisition())
                .restarting("crowdsec")
            files += RenderedFile(
                path = "config/traefik/dynamic/homegap-crowdsec.yml",
                content = crowdsecDynamic(secrets, stack.crowdsecFailClosed),
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

    fun compose(config: AppConfig): String {
        val root = buildJsonObject {
            put("name", "homegap")
            putJsonObject("services") {
                putJsonObject("traefik") { traefikContainer(config) }
                putJsonObject(MaintenancePage.SERVICE) { maintenanceContainer(config) }
                putJsonObject(AgentSpec.SERVICE) { agentContainer(config) }
                if (config.nodeStack.crowdsec) {
                    putJsonObject("crowdsec") { crowdsecContainer(config) }
                }
            }
            putJsonObject("networks") {
                putJsonObject("default") {
                    put("driver", "bridge")
                    put("name", "homegap_node")
                }
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    /** Traefik has the web ports of the node. The kernel and the agent of the node serve each other port. */
    private fun JsonObjectBuilder.traefikContainer(config: AppConfig) {
        put("image", config.nodeStack.traefikImage)
        put("container_name", "traefik")
        put("restart", "unless-stopped")
        putJsonArray("command") { add("--configFile=/etc/traefik/traefik_config.yml") }
        // The TCP settings belong to a network namespace: the sysctl.d of the host does not
        // reach containers. Each node -> homelab and node -> visitor connection starts here. The
        // namespace gets the congestion control of the host when Docker makes it.
        if (config.nodeStack.tcpTuning) {
            putJsonObject("sysctls") { CONTAINER_SYSCTLS.forEach { (name, value) -> put(name, value) } }
        }
        putJsonArray("ports") {
            add("443:443")
            add("443:443/udp")
            add("80:80")
        }
        putJsonArray("volumes") {
            add("./config/traefik:/etc/traefik:ro")
            add("./config/traefik/logs:/var/log/traefik")
        }
    }

    private fun JsonObjectBuilder.maintenanceContainer(config: AppConfig) {
        put("image", config.nodeStack.maintenanceImage)
        put("container_name", "homegap-maintenance")
        put("restart", "unless-stopped")
        put("stop_grace_period", "2s")
        // On the network of the stack, with no published port: only Traefik reaches the page.
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

    /**
     * The agent is in the network of the host: it opens and closes public ports while it runs, and
     * a published port of a container needs a new container. It can bind a port, and it can change
     * the network of the host for the exit of the node (a WireGuard interface and its firewall).
     */
    private fun JsonObjectBuilder.agentContainer(config: AppConfig) {
        put("image", config.agent.image)
        put("container_name", AgentSpec.SERVICE)
        put("restart", "unless-stopped")
        put("network_mode", "host")
        putJsonArray("command") {
            add("node-agent")
            add("--dir")
            add(AGENT_MOUNT)
        }
        put("read_only", true)
        // iptables keeps its lock there.
        putJsonArray("tmpfs") { add("/run") }
        putJsonArray("cap_drop") { add("ALL") }
        putJsonArray("cap_add") {
            add("NET_BIND_SERVICE")
            add("NET_ADMIN")
            add("NET_RAW")
            // The agent is root, and the folder of its key belongs to the user of the node stack.
            add("DAC_OVERRIDE")
        }
        putJsonArray("security_opt") { add("no-new-privileges:true") }
        putJsonArray("volumes") {
            add("./${AgentSpec.DIR}:$AGENT_MOUNT:ro")
            // The key of the exit of the node.
            add("./$AGENT_STATE_DIR:$AGENT_STATE_MOUNT")
        }
    }

    private fun JsonObjectBuilder.crowdsecContainer(config: AppConfig) {
        put("image", config.nodeStack.crowdsecImage)
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
        |# Managed by HomeGAP (node_stack.tcp_tuning). Removed when tuning is disabled.
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
        if (crowdsec) appendLine("CROWDSEC_BOUNCER_KEY=${secrets.crowdsecBouncerKey}")
    }

    // ------------------------------------------------------------------ Traefik static config

    fun traefikStatic(config: AppConfig): String {
        val stack = config.nodeStack
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
                putJsonObject("file") {
                    put("directory", "/etc/traefik/dynamic")
                    put("watch", true)
                }
            }
            if (stack.crowdsec) {
                putJsonObject("experimental") {
                    putJsonObject("plugins") {
                        putJsonObject("crowdsec") {
                            put("moduleName", "github.com/maxlerebourg/crowdsec-bouncer-traefik-plugin")
                            put("version", stack.crowdsecBouncerVersion)
                        }
                    }
                }
            }
            putJsonObject("log") {
                put("level", "INFO")
                put("format", if (stack.crowdsec) "json" else "common")
            }
            if (stack.crowdsec) {
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
                            if (stack.crowdsec) add("crowdsec@file")
                        }
                        putJsonObject("encodedCharacters") {
                            put("allowEncodedSlash", true)
                            put("allowEncodedQuestionMark", true)
                        }
                    }
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
            // The hop to the origin runs in the mesh to the homelab.
            // A short dial timeout: a dead mesh answers 502 fast, and the health probes do not hang.
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
     * The routing file, which Traefik loads again after each change: the routers of the names
     * that the node ends TLS for, the SNI passthrough routers, the HTTP->HTTPS redirects and the
     * health endpoint on port 80 that the monitor of Bunny probes. Each service is a port of the
     * mesh address of the homelab.
     */
    fun dynamicRoutes(desired: NodeDesired): String {
        val sniRoutes = desired.sniRoutes.mapNotNull { route ->
            desired.meshTarget(route)?.let { Stream(route, meshService(route), it) }
        }
        val misdirected = misdirectedRule(sniRoutes.map { it.route }, ownNames(desired))
        val sharedNames = sharedNamesOf(desired)
        val overMesh = desired.httpResources.filter { desired.meshUrl(it) != null }
        val root = buildJsonObject {
            if (misdirected != null) {
                putJsonObject("tls") {
                    putJsonObject("options") {
                        putJsonObject(ELSEWHERE_TLS_OPTIONS) { put("minVersion", "VersionTLS12") }
                    }
                }
            }
            putJsonObject("http") {
                putJsonObject("middlewares") {
                    httpMiddlewares(desired, sharedNames)
                    overMesh.forEach { hostHeaderMiddleware(it) }
                }
                putJsonObject("routers") {
                    misdirected?.let { misdirectedRouter(it) }
                    sharedNames.forEach { sharedNameRouters(it) }
                    overMesh.forEach { resourceRouter(it) }
                    redirectRouter("homegap-mgmt-redirect", "Host(`${desired.managementHost}`)", TOP_PRIORITY)
                    httpsRedirectRouter(desired)
                    healthRouters(desired, overMesh)
                }
                putJsonObject("services") {
                    for (shared in sharedNames) {
                        putJsonObject(shared.key) { selfLoadBalancer(shared.key) }
                    }
                    for (resource in overMesh) {
                        val control = desired.exit?.control?.takeIf { it.name == resource.fullDomain }
                        if (control == null) {
                            putJsonObject(meshService(resource.niceId)) { meshLoadBalancer(desired, resource) }
                        } else {
                            signInServices(desired, resource, control)
                        }
                    }
                    putJsonObject(UNAVAILABLE) { loadBalancer("http://127.0.0.1:9") }
                }
                val encrypted = overMesh.filter { it.target.method == HTTPS }
                if (sharedNames.isNotEmpty() || encrypted.isNotEmpty()) {
                    putJsonObject("serversTransports") {
                        encrypted.forEach { meshTransport(it) }
                        for (shared in sharedNames) {
                            putJsonObject(shared.key) {
                                put("serverName", shared.own)
                                // The connection goes to the :443 of this node. From there it goes,
                                // still encrypted, through the front door and the mesh to the
                                // Traefik of the homelab. The node already ends the TLS of the
                                // visitor for this name, and there is no other hop here.
                                put("insecureSkipVerify", true)
                            }
                        }
                    }
                }
            }
            if (sniRoutes.isNotEmpty()) {
                putJsonObject("tcp") { tcpRoutes(sniRoutes) }
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
        // A service of the homelab learns which node a request came through. A client cannot set the header.
        putJsonObject(NODE_HEADER) {
            putJsonObject("headers") { putJsonObject("customRequestHeaders") { put(NODE_HEADER_NAME, desired.id) } }
        }
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
            desired.wildcardRedirectDomains.forEach { add("HostRegexp(`${wildcardRegex(it)}`)") }
        }
        if (hosts.isNotEmpty()) {
            redirectRouter("homegap-https-redirect", hosts.joinToString(" || "), HTTPS_REDIRECT_PRIORITY)
        }
    }

    /**
     * The router of an HTTP resource. The node ends TLS for the name and sends the request to the
     * port of the resource on the mesh. A wildcard has a lower priority, so the service of an exact
     * name under it answers that name.
     */
    private fun JsonObjectBuilder.resourceRouter(resource: PublicResource) {
        val domain = resource.fullDomain?.takeIf { resource.ssl } ?: return
        val wildcard = domain.startsWith(WILDCARD)
        val route = HttpRoute(
            rule = if (wildcard) "HostRegexp(`${wildcardRegex(domain.removePrefix(WILDCARD))}`)" else "Host(`$domain`)",
            service = meshService(resource.niceId),
            priority = if (wildcard) WILDCARD_PRIORITY else RESOURCE_PRIORITY,
            middlewares = listOfNotNull(NODE_HEADER, hostHeaderName(resource)),
        )
        httpsRouter("homegap-http-${resource.niceId}", route)
    }

    private fun hostHeaderName(resource: PublicResource): String? =
        resource.hostHeader?.let { "homegap-host-${resource.niceId}" }

    /** The service of the homelab gets the request with the Host header that it expects. */
    private fun JsonObjectBuilder.hostHeaderMiddleware(resource: PublicResource) {
        val name = hostHeaderName(resource) ?: return
        putJsonObject(name) {
            putJsonObject("headers") { putJsonObject("customRequestHeaders") { put("Host", resource.hostHeader) } }
        }
    }

    /**
     * The services for the name of the sign-in. The node sends the apps to the homelab over the
     * mesh. While the homelab does not answer, it sends them to its own agent, which then signs
     * devices in for the exit of the node.
     */
    @Suppress("HttpUrlsUsage") // The agent is on the node itself.
    private fun JsonObjectBuilder.signInServices(
        desired: NodeDesired,
        resource: PublicResource,
        control: Mesh.NodeControl,
    ) {
        val home = meshService(resource.niceId) + "-home"
        putJsonObject(meshService(resource.niceId)) {
            putJsonObject("failover") {
                put("service", home)
                put("fallback", NODE_CONTROL)
                putJsonObject("healthCheck") {}
            }
        }
        // Traefik wants a health check on each of the two services of a failover.
        putJsonObject(home) { checkedLoadBalancer(desired.meshUrl(resource).orEmpty()) }
        putJsonObject(NODE_CONTROL) { checkedLoadBalancer("http://${control.listen}") }
    }

    private fun JsonObjectBuilder.checkedLoadBalancer(url: String) {
        putJsonObject("loadBalancer") {
            putJsonArray("servers") { addJsonObject { put("url", url) } }
            putJsonObject("healthCheck") {
                put("path", "/api/v1/")
                put("interval", SIGN_IN_CHECK_INTERVAL)
                put("timeout", SIGN_IN_CHECK_TIMEOUT)
            }
        }
    }

    private fun JsonObjectBuilder.meshLoadBalancer(desired: NodeDesired, resource: PublicResource) {
        putJsonObject("loadBalancer") {
            putJsonArray("servers") { addJsonObject { put("url", desired.meshUrl(resource)) } }
            if (resource.target.method == HTTPS) put("serversTransport", meshService(resource.niceId))
        }
    }

    /**
     * The transport to a service of the homelab that speaks HTTPS. The hop is inside the mesh, and
     * the service often has a certificate for a different name, so the node does not check it.
     */
    private fun JsonObjectBuilder.meshTransport(resource: PublicResource) {
        putJsonObject(meshService(resource.niceId)) {
            val name = resource.tlsServerName ?: resource.fullDomain?.takeIf { !it.startsWith(WILDCARD) }
            name?.let { put("serverName", it) }
            put("insecureSkipVerify", true)
        }
    }

    /** The health endpoint on the IP addresses of the node, on port 80. */
    private fun JsonObjectBuilder.healthRouters(desired: NodeDesired, overMesh: List<PublicResource>) {
        val node = desired.node
        val ipHosts = listOfNotNull(node.ipv4(), node.publicIpv6?.let { "[$it]" })
        if (ipHosts.isEmpty()) return
        val ipRule = ipHosts.joinToString(" || ") { "Host(`$it`)" }
        val target = overMesh.firstOrNull { it.niceId == desired.healthResourceNiceId }
        if (target != null) {
            val health = HttpRoute(
                rule = ipRule,
                service = meshService(target.niceId),
                priority = HEALTH_PRIORITY,
                middlewares = listOfNotNull(HEALTH_PATH, hostHeaderName(target)),
            )
            httpRouter("homegap-health", health)
        }
        // The fallback answers 502, so a health path that does not work shows as "offline".
        val fallback = HttpRoute(rule = ipRule, service = UNAVAILABLE, priority = HEALTH_FALLBACK_PRIORITY)
        httpRouter("homegap-health-fallback", fallback)
    }

    /** A passthrough route and its service on the mesh. */
    private class Stream(val route: SniRoute, val service: String, val mesh: MeshTarget)

    private fun meshService(route: SniRoute) = meshService(route.resourceNiceId)

    private fun meshService(niceId: String) = "homegap-mesh-$niceId"

    private fun JsonObjectBuilder.tcpRoutes(streams: List<Stream>) {
        val overMesh = streams.distinctBy { it.service }
        if (overMesh.any { it.mesh.proxyProtocol }) {
            putJsonObject("serversTransports") {
                putJsonObject(MESH_PROXY_TRANSPORT) {
                    putJsonObject("proxyProtocol") { put("version", PROXY_PROTOCOL_VERSION) }
                }
            }
        }
        if (streams.isNotEmpty()) {
            putJsonObject("routers") {
                for (stream in streams) {
                    putJsonObject(stream.route.name) {
                        putJsonArray("entryPoints") { add(WEBSECURE) }
                        put("rule", sniRule(stream.route))
                        put("service", stream.service)
                        put("priority", stream.route.priority)
                        putJsonObject("tls") { put("passthrough", true) }
                    }
                }
            }
        }
        if (overMesh.isNotEmpty()) {
            putJsonObject("services") {
                for (stream in overMesh) {
                    putJsonObject(stream.service) { meshLoadBalancer(stream.mesh) }
                }
            }
        }
    }

    /** The stream goes to a port of the mesh address of the homelab, which sends it to the front door. */
    private fun JsonObjectBuilder.meshLoadBalancer(target: MeshTarget) {
        putJsonObject("loadBalancer") {
            putJsonArray("servers") { add(buildJsonObject { put("address", target.address) }) }
            if (target.proxyProtocol) put("serversTransport", MESH_PROXY_TRANSPORT)
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

    /** The names that this node serves itself: its own name and the shared names. */
    private fun ownNames(desired: NodeDesired): List<String> = buildList {
        add(desired.managementHost)
        for (shared in sharedNamesOf(desired)) {
            add(shared.shared)
            add(shared.own)
        }
    }

    /** A pattern that matches one label before [domain]. */
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
