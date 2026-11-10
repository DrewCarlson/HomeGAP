package homegap.discovery

/**
 * The `homegap.*` Docker label schema.
 *
 * Spec labels: expose, wildcard, port, protocol, tls, regions, access, auth, healthcheck.
 * Extensions: host (target host override), public_port (raw TCP/UDP listen port on nodes),
 * roles (Pangolin roles allowed for private/SSO access), pin_env (env var holding a 6-digit PIN),
 * pause (hold manual overrides on this service's resources), proxy_protocol (send the viewer's
 * address to the service with PROXY protocol v2), private_network (Pangolin clients reach the
 * service at its address on this Docker network, which only their traffic uses) and
 * private_hosts (the names they reach it by that way; the service's own hostname by default).
 */
object Labels {
    const val PREFIX = "homegap."
    const val EXPOSE = "homegap.expose"
    const val WILDCARD = "homegap.wildcard"
    const val PORT = "homegap.port"
    const val PROTOCOL = "homegap.protocol"
    const val TLS = "homegap.tls"
    const val REGIONS = "homegap.regions"
    const val ACCESS = "homegap.access"
    const val AUTH = "homegap.auth"
    const val HEALTHCHECK = "homegap.healthcheck"
    const val HOST = "homegap.host"
    const val PUBLIC_PORT = "homegap.public_port"
    const val ROLES = "homegap.roles"
    const val PIN_ENV = "homegap.pin_env"
    const val PAUSE = "homegap.pause"
    const val PROXY_PROTOCOL = "homegap.proxy_protocol"
    const val PRIVATE_NETWORK = "homegap.private_network"
    const val PRIVATE_HOSTS = "homegap.private_hosts"

    const val COMPOSE_SERVICE = "com.docker.compose.service"
    const val COMPOSE_PROJECT = "com.docker.compose.project"

    val KNOWN = setOf(EXPOSE, WILDCARD, PORT, PROTOCOL, TLS, REGIONS, ACCESS, AUTH, HEALTHCHECK, HOST, PUBLIC_PORT, ROLES, PIN_ENV, PAUSE, PROXY_PROTOCOL, PRIVATE_NETWORK, PRIVATE_HOSTS)
}

enum class Protocol(val label: String) { HTTP("http"), HTTPS("https"), TCP("tcp"), UDP("udp") }
enum class TlsMode(val label: String) { PASSTHROUGH("passthrough"), TERMINATE("terminate") }
enum class Access(val label: String) { PUBLIC("public"), PRIVATE("private") }
enum class AuthMode(val label: String) { NONE("none"), SSO("sso"), PIN("pin") }

sealed interface HealthCheck {
    data class Http(val path: String) : HealthCheck
    data object Tcp : HealthCheck
}

/** A validated, labeled service as declared on one container. */
data class ServiceSpec(
    val containerId: String,
    val containerName: String,
    /** Host the tunnel connector dials (container name on the shared Docker network by default). */
    val targetHost: String,
    val hostname: String,
    val wildcard: Boolean,
    val port: Int,
    val protocol: Protocol,
    val tls: TlsMode,
    /** null means every configured node. */
    val regions: List<String>?,
    val access: Access,
    val auth: AuthMode,
    /** null when the label is absent (Bunny still probes `/` through the front door). */
    val healthcheck: HealthCheck?,
    val publicPort: Int?,
    val roles: List<String>,
    val pinEnv: String?,
    val paused: Boolean,
    /** Its TCP resources (passthrough or raw TCP) send the viewer's address with PROXY protocol v2. */
    val proxyProtocol: Boolean = false,
    /** The Docker network whose address Pangolin clients reach this public service at. */
    val privateNetwork: String? = null,
    /** The container's address on [privateNetwork] (filled in from Docker when planning). */
    val privateHost: String? = null,
    /** The names Pangolin clients reach it by over [privateNetwork] (full names, `*`/`?` allowed). */
    val privateHosts: List<String> = emptyList(),
) {
    /** Stable identifier used to key every derived Pangolin/Bunny object. */
    val key: String get() = slug(hostname)

    fun servesNode(nodeId: String): Boolean = regions == null || nodeId in regions
}

data class LabelParseResult(val spec: ServiceSpec?, val errors: List<String>, val warnings: List<String> = emptyList())

private val HOSTNAME = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

/** A Pangolin alias: a full name whose labels may use `*` and `?`. */
private val PRIVATE_ALIAS = Regex("^(?:[a-z0-9*?](?:[a-z0-9*?-]{0,61}[a-z0-9*?])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

/** Whether the Pangolin alias [pattern] (`*` any run of characters, `?` one) covers [host]. */
fun aliasCovers(pattern: String, host: String): Boolean =
    Regex("^" + pattern.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } } + "$").matches(host)

fun slug(hostname: String): String =
    hostname.lowercase().replace("*", "wildcard").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

/**
 * Parses one container's labels. Returns `spec == null` with no errors when the container is
 * not exposed at all, and `spec == null` with errors when it is exposed but misconfigured.
 */
fun parseLabels(
    containerId: String,
    containerName: String,
    labels: Map<String, String>,
    domain: String,
): LabelParseResult {
    val expose = labels[Labels.EXPOSE]?.trim()?.lowercase() ?: return LabelParseResult(null, emptyList())
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    labels.keys.filter { it.startsWith(Labels.PREFIX) && it !in Labels.KNOWN }.forEach { warnings += "unknown label '$it' ignored" }

    if (!HOSTNAME.matches(expose)) errors += "${Labels.EXPOSE} '$expose' is not a valid hostname"
    if (!(expose == domain || expose.endsWith(".$domain"))) errors += "${Labels.EXPOSE} '$expose' is outside the managed domain '$domain'"

    fun bool(key: String, default: Boolean): Boolean = when (labels[key]?.trim()?.lowercase()) {
        null, "" -> default
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> { errors += "$key must be true/false"; default }
    }

    fun <E : Enum<E>> enumLabel(key: String, values: Array<E>, label: (E) -> String, default: E): E {
        val raw = labels[key]?.trim()?.lowercase() ?: return default
        return values.firstOrNull { label(it) == raw } ?: run {
            errors += "$key must be one of ${values.joinToString("/") { label(it) }} (got '$raw')"
            default
        }
    }

    val wildcard = bool(Labels.WILDCARD, false)
    val protocol = enumLabel(Labels.PROTOCOL, Protocol.entries.toTypedArray(), { it.label }, Protocol.HTTP)
    var tls = enumLabel(Labels.TLS, TlsMode.entries.toTypedArray(), { it.label }, TlsMode.PASSTHROUGH)
    val access = enumLabel(Labels.ACCESS, Access.entries.toTypedArray(), { it.label }, Access.PUBLIC)
    val auth = enumLabel(Labels.AUTH, AuthMode.entries.toTypedArray(), { it.label }, AuthMode.NONE)
    val paused = bool(Labels.PAUSE, false)

    fun port(key: String): Int? {
        val raw = labels[key]?.trim() ?: return null
        val p = raw.toIntOrNull()
        if (p == null || p !in 1..65535) { errors += "$key must be a port number (got '$raw')"; return null }
        return p
    }

    val port = port(Labels.PORT) ?: when (protocol) {
        Protocol.HTTPS -> 443
        Protocol.HTTP -> 80
        else -> { errors += "${Labels.PORT} is required for ${protocol.label} services"; 0 }
    }
    val publicPort = port(Labels.PUBLIC_PORT)

    val regions = labels[Labels.REGIONS]?.trim()?.lowercase()?.let { raw ->
        if (raw == "" || raw == "all") null else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    val healthcheck = when (val raw = labels[Labels.HEALTHCHECK]?.trim()) {
        null, "" -> null
        "tcp" -> HealthCheck.Tcp
        else -> if (raw.startsWith("/")) HealthCheck.Http(raw) else {
            errors += "${Labels.HEALTHCHECK} must be an HTTP path starting with '/' or 'tcp'"
            HealthCheck.Http("/")
        }
    }

    // Semantic rules
    if (protocol == Protocol.HTTP && tls == TlsMode.PASSTHROUGH && labels[Labels.TLS] != null) {
        warnings += "plain-http services cannot be passed through; the node terminates TLS (tls=terminate)"
    }
    if (protocol == Protocol.HTTP) tls = TlsMode.TERMINATE
    if (protocol == Protocol.TCP || protocol == Protocol.UDP) {
        if (wildcard) errors += "${Labels.WILDCARD} is only valid for http/https services"
        if (labels[Labels.TLS] != null) warnings += "${Labels.TLS} has no effect on raw ${protocol.label} services"
    }
    if (auth != AuthMode.NONE && tls != TlsMode.TERMINATE) {
        errors += "${Labels.AUTH}=${auth.label} requires ${Labels.TLS}=terminate (the node cannot authenticate encrypted traffic)"
    }
    if (auth == AuthMode.PIN && labels[Labels.PIN_ENV].isNullOrBlank()) errors += "${Labels.AUTH}=pin requires ${Labels.PIN_ENV}"
    if (wildcard && expose != domain) errors += "${Labels.WILDCARD}=true is only valid when ${Labels.EXPOSE} is the root domain"
    if (publicPort != null && protocol != Protocol.TCP && protocol != Protocol.UDP) warnings += "${Labels.PUBLIC_PORT} only applies to tcp/udp"
    val effectivePublic = publicPort ?: port
    if ((protocol == Protocol.TCP || protocol == Protocol.UDP) && access == Access.PUBLIC && effectivePublic in RESERVED_NODE_PORTS) {
        errors += "public port $effectivePublic is reserved on nodes; set ${Labels.PUBLIC_PORT}"
    }

    val proxyProtocol = bool(Labels.PROXY_PROTOCOL, false)
    if (proxyProtocol) {
        val carried = protocol == Protocol.TCP || (protocol == Protocol.HTTPS && tls == TlsMode.PASSTHROUGH)
        if (!carried) errors += "${Labels.PROXY_PROTOCOL} needs an https passthrough or tcp service (the node only adds it to TCP streams)"
        if (access == Access.PRIVATE) errors += "${Labels.PROXY_PROTOCOL} only applies to public services"
    }
    val privateNetwork = labels[Labels.PRIVATE_NETWORK]?.trim()?.takeIf { it.isNotEmpty() }
    if (privateNetwork != null) {
        if (access == Access.PRIVATE) errors += "${Labels.PRIVATE_NETWORK} is for public services (a private service already goes to ${Labels.HOST})"
        if (protocol != Protocol.HTTP && protocol != Protocol.HTTPS) errors += "${Labels.PRIVATE_NETWORK} only applies to http/https services"
    }
    // Names relative to the service's hostname ("jellyfin", "*.home") or full names under it.
    val privateHosts = labels[Labels.PRIVATE_HOSTS]?.split(',')?.map { it.trim().lowercase().trimEnd('.') }?.filter { it.isNotEmpty() }
        ?.map { if (it == expose || it.endsWith(".$expose")) it else "$it.$expose" }?.distinct()
    if (privateHosts != null) {
        if (privateNetwork == null) errors += "${Labels.PRIVATE_HOSTS} needs ${Labels.PRIVATE_NETWORK}"
        privateHosts.filter { !PRIVATE_ALIAS.matches(it) }.forEach { errors += "${Labels.PRIVATE_HOSTS}: '$it' is not a valid name" }
    }

    val host = labels[Labels.HOST]?.trim()?.takeIf { it.isNotEmpty() } ?: containerName
    val roles = labels[Labels.ROLES]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    if (errors.isNotEmpty()) return LabelParseResult(null, errors, warnings)
    return LabelParseResult(
        ServiceSpec(
            containerId = containerId,
            containerName = containerName,
            targetHost = host,
            hostname = expose,
            wildcard = wildcard,
            port = port,
            protocol = protocol,
            tls = tls,
            regions = regions,
            access = access,
            auth = auth,
            healthcheck = healthcheck,
            publicPort = if (protocol == Protocol.TCP || protocol == Protocol.UDP) effectivePublic else null,
            roles = roles,
            pinEnv = labels[Labels.PIN_ENV]?.trim(),
            paused = paused,
            proxyProtocol = proxyProtocol,
            privateNetwork = privateNetwork,
            privateHosts = if (privateNetwork == null) emptyList() else privateHosts ?: listOf(expose),
        ),
        emptyList(),
        warnings,
    )
}

/** Ports a regional node already uses for itself (SSH, web, tunnels, Pangolin internals). */
val RESERVED_NODE_PORTS = setOf(22, 80, 443, 3000, 3001, 3002, 3003, 3004, 3005, 8080, 21820, 51820)
