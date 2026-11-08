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
)

data class RenderedFile(val path: String, val content: String, val mode: String = "0644") {
    /** Restart the named compose service when this file changes (bind-mounted config). */
    var restarts: String? = null
}

/**
 * Renders the regional node stack: Pangolin EE + Gerbil + Traefik (+ optional CrowdSec).
 * Paths are relative to the node's install dir.
 */
object NodeTemplates {
    const val DYNAMIC_ROUTES = "config/traefik/dynamic/homegap-routes.yml"
    const val DYNAMIC_TLS = "config/traefik/dynamic/homegap-tls.yml"
    const val CERT = "config/traefik/certs/homegap.crt"
    const val KEY = "config/traefik/certs/homegap.key"
    const val SECRETS = "secrets/node.json"
    private val PRETTY_JSON = Json { prettyPrint = true }
    /** Rendered into the install dir (so it is hashed and drift-checked), installed into /etc/sysctl.d. */
    const val SYSCTL = "config/homegap-sysctl.conf"

    private val HEADER = "Managed by HomeGAP. Manual edits are reverted on the next reconcile\nunless this node is paused from the dashboard."

    fun adminEmail(config: AppConfig) = config.pangolin.adminEmail ?: "admin@${config.domain}"

    fun staticFiles(config: AppConfig, node: NodeConfig, secrets: NodeSecrets, rawPorts: List<RawPort>, slot: Int): List<RenderedFile> {
        val files = mutableListOf(
            RenderedFile("compose.yml", compose(config, rawPorts)),
            RenderedFile(".env", envFile(secrets, config.pangolin.crowdsec), "0600"),
            RenderedFile("config/config.yml", pangolinConfig(config, node, secrets, slot), "0600").apply { restarts = "pangolin" },
            RenderedFile("config/traefik/traefik_config.yml", traefikStatic(config, rawPorts)).apply { restarts = "traefik" },
            RenderedFile(SECRETS, PRETTY_JSON.encodeToString(NodeSecrets.serializer(), secrets), "0600"),
        )
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

    fun compose(config: AppConfig, rawPorts: List<RawPort>): String {
        val p = config.pangolin
        val root = buildJsonObject {
            put("name", "homegap")
            putJsonObject("services") {
                putJsonObject("pangolin") {
                    put("image", p.pangolinImage)
                    put("container_name", "pangolin")
                    put("restart", "unless-stopped")
                    putJsonArray("env_file") { add(".env") }
                    putJsonArray("volumes") { add("./config:/app/config") }
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
                        add("--reachableAt=http://gerbil:3004")
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

    fun pangolinConfig(config: AppConfig, node: NodeConfig, secrets: NodeSecrets, slot: Int): String {
        val mgmt = node.managementHost(config.domain)
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
                put("dashboard_url", "https://$mgmt")
                put("log_level", "info")
                putJsonObject("telemetry") { put("anonymous_usage", false) }
            }
            putJsonObject("domains") {
                putJsonObject("homegap") {
                    put("base_domain", config.domain)
                    put("cert_resolver", "letsencrypt")
                    put("prefer_wildcard_cert", config.certificates.source != "none")
                }
            }
            putJsonObject("server") {
                put("secret", secrets.serverSecret)
                putJsonObject("cors") {
                    putJsonArray("origins") { add("https://$mgmt") }
                    putJsonArray("methods") { listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { add(it) } }
                    putJsonArray("allowed_headers") { add("X-CSRF-Token"); add("Content-Type") }
                    put("credentials", false)
                }
            }
            putJsonObject("traefik") {
                put("cert_resolver", "letsencrypt")
                put("http_entrypoint", "web")
                put("https_entrypoint", "websecure")
            }
            putJsonObject("flags") {
                put("require_email_verification", false)
                put("disable_signup_without_invite", true)
                put("disable_user_create_org", true)
                put("allow_raw_resources", true)
                put("enable_integration_api", true)
            }
        }
        return YamlEmitter.emit(root, HEADER)
    }

    fun traefikStatic(config: AppConfig, rawPorts: List<RawPort>): String {
        val p = config.pangolin
        val email = p.acmeEmail ?: adminEmail(config)
        val root = buildJsonObject {
            putJsonObject("api") { put("insecure", false); put("dashboard", false) }
            putJsonObject("providers") {
                putJsonObject("http") {
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
    fun dynamicRoutes(desired: NodeDesired, resourceIds: Map<String, Int>): String {
        val node = desired.node
        val mgmt = desired.managementHost
        val root = buildJsonObject {
            putJsonObject("http") {
                putJsonObject("middlewares") {
                    putJsonObject("homegap-redirect-https") { putJsonObject("redirectScheme") { put("scheme", "https"); put("permanent", true) } }
                    putJsonObject("homegap-health-path") { putJsonObject("replacePath") { put("path", desired.healthPath) } }
                    desired.speedtestRedirect?.let { (_, to) ->
                        putJsonObject("homegap-speedtest-which") {
                            putJsonObject("redirectRegex") {
                                put("regex", "^.*$")
                                put("replacement", "https://$to/?via=auto")
                                put("permanent", false)
                            }
                        }
                    }
                }
                putJsonObject("routers") {
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
                    desired.speedtestRedirect?.let { (from, _) ->
                        // Plain-HTTP entry link: whichever node DNS picked answers with its own
                        // per-node hostname, so the test page knows which node it runs through.
                        putJsonObject("homegap-speedtest-which") {
                            put("rule", "Host(`$from`)")
                            putJsonArray("entryPoints") { add("web") }
                            putJsonArray("middlewares") { add("homegap-speedtest-which") }
                            put("service", "noop@internal")
                            put("priority", 20)
                        }
                    }
                    val ipHosts = listOfNotNull(node.ipv4(), node.publicIpv6?.let { "[$it]" })
                    if (ipHosts.isNotEmpty()) {
                        val ipRule = ipHosts.joinToString(" || ") { "Host(`$it`)" }
                        val healthId = desired.healthResourceNiceId?.let { resourceIds[it] }
                        if (healthId != null) {
                            putJsonObject("homegap-health") {
                                put("rule", ipRule)
                                putJsonArray("entryPoints") { add("web") }
                                putJsonArray("middlewares") { add("homegap-health-path") }
                                put("service", "$healthId-${desired.healthResourceNiceId}-service@http")
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
                    putJsonObject("homegap-pangolin-api") { lb("http://pangolin:3000") }
                    putJsonObject("homegap-pangolin-next") { lb("http://pangolin:3002") }
                    putJsonObject("homegap-unavailable") { lb("http://127.0.0.1:9") }
                }
            }
            val sni = desired.sniRoutes.mapNotNull { r -> resourceIds[r.resourceNiceId]?.let { r to it } }
            if (sni.isNotEmpty()) {
                putJsonObject("tcp") {
                    putJsonObject("routers") {
                        for ((route, id) in sni) {
                            putJsonObject(route.name) {
                                putJsonArray("entryPoints") { add("websecure") }
                                put("rule", sniRule(route))
                                put("service", "$id-${route.resourceNiceId}-service@http")
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

    private fun JsonObjectBuilder.lb(url: String) {
        putJsonObject("loadBalancer") { putJsonArray("servers") { addJsonObject { put("url", url) } } }
    }

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
