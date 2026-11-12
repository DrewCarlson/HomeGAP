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
 * private_hosts (the names they reach it by that way; the service's own hostname by default) and
 * private_middleware (also every name a router of this Traefik serves through this middleware, read
 * from its API at traefik_api).
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
    const val PRIVATE_MIDDLEWARE = "homegap.private_middleware"
    const val TRAEFIK_API = "homegap.traefik_api"
    const val MAINTENANCE_PAGE = "homegap.maintenance_page"

    /** Named services on one container: `homegap.services.<name>.<label>` (`expose`, `port`, ...). */
    const val SERVICES_PREFIX = "homegap.services."

    /** Top-level labels that every named service takes as its default. */
    val INHERITED = setOf(HOST, REGIONS, ACCESS, ROLES, PAUSE)

    const val COMPOSE_SERVICE = "com.docker.compose.service"
    const val COMPOSE_PROJECT = "com.docker.compose.project"

    val KNOWN = setOf(EXPOSE, WILDCARD, PORT, PROTOCOL, TLS, REGIONS, ACCESS, AUTH, HEALTHCHECK, HOST, PUBLIC_PORT, ROLES, PIN_ENV, PAUSE, PROXY_PROTOCOL, PRIVATE_NETWORK, PRIVATE_HOSTS, PRIVATE_MIDDLEWARE, TRAEFIK_API, MAINTENANCE_PAGE)
}

enum class Protocol(val label: String) {
    HTTP("http"), HTTPS("https"), TCP("tcp"), UDP("udp"),
    /** Raw ports, some or all of them on both TCP and UDP (`homegap.protocol: tcp+udp`, or per port). */
    TCP_UDP("tcp+udp");

    /** Published as raw ports on the nodes rather than routed by name. */
    val isRaw: Boolean get() = this == TCP || this == UDP || this == TCP_UDP
}

/**
 * One raw port of a service: [publicPort] on the nodes to [port] on the container, on each of
 * [protocols] (TCP and/or UDP). From `homegap.port: "7881/tcp, 7882/udp, 3478/tcp+udp, 2222:22"`.
 */
data class PortMapping(val publicPort: Int, val port: Int, val protocols: Set<Protocol>) {
    override fun toString(): String =
        (if (publicPort != port) "$publicPort:" else "") + "$port/" + protocols.sortedBy { it.ordinal }.joinToString("+") { it.label }
}
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
    /** Routers using this Traefik middleware (name without `@provider`) add their names to [privateHosts]. */
    val privateMiddleware: String? = null,
    /** Where this container's Traefik API answers (with [privateMiddleware]). */
    val traefikApi: String? = null,
    /**
     * The nodes show the maintenance page for this service while the homelab is unreachable. Only a
     * service that the node terminates can have the page.
     */
    val maintenancePage: Boolean = false,
    /** Its name under `homegap.services.<name>`; null for the container's top-level service. */
    val name: String? = null,
    /** Raw services: every published port (at least one). Empty for http/https. */
    val ports: List<PortMapping> = emptyList(),
    /** The `homegap.*` labels it was read from, as written on the container. */
    val labels: Map<String, String> = emptyMap(),
) {
    /**
     * Stable identifier used to key every derived Pangolin/Bunny object. A named service adds its
     * name after `--`, which a hostname's slug never contains.
     */
    val key: String get() = slug(hostname) + (name?.let { "--" + slug(it) }.orEmpty())

    /** Shown in logs and errors: the container, and the service's name when it has one. */
    val label: String get() = containerName + (name?.let { " ($it)" }.orEmpty())

    fun servesNode(nodeId: String): Boolean = regions == null || nodeId in regions
}

data class LabelParseResult(val spec: ServiceSpec?, val errors: List<String>, val warnings: List<String> = emptyList())

private val HOSTNAME = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

/**
 * The names served by the Traefik routers that use [middleware] (compared without `@provider`),
 * and the routers whose names cannot be read (a `HostRegexp` rule, or no `Host` at all).
 */
fun routerHosts(routers: List<TraefikRouter>, middleware: String): Pair<List<String>, List<String>> {
    val hosts = mutableListOf<String>()
    val unreadable = mutableListOf<String>()
    for (r in routers) {
        if (r.status == "disabled" || r.middlewares.none { it.substringBefore('@') == middleware }) continue
        val found = HOST_RULE.findAll(r.rule).flatMap { m -> RULE_STRING.findAll(m.groupValues[1]).map { it.groupValues[1].lowercase() } }.toList()
        if (found.isEmpty() || "HostRegexp" in r.rule) unreadable += r.name
        hosts += found
    }
    return hosts.distinct() to unreadable
}

/** The Traefik API of a front door container: `homegap.traefik_api`, or its name on port 8080. */
fun traefikApiUrl(containerName: String, labels: Map<String, String>): String =
    labels[Labels.TRAEFIK_API]?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.trimEnd('/')
        ?: "http://$containerName:8080"

private val HOST_RULE = Regex("""\bHost\(([^)]*)\)""")
private val RULE_STRING = Regex("""[`"']([^`"']+)[`"']""")

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

    labels.keys.filter { it.startsWith(Labels.PREFIX) && it !in Labels.KNOWN && !it.startsWith(Labels.SERVICES_PREFIX) }
        .forEach { warnings += "unknown label '$it' ignored" }

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
    // "udp+tcp" reads the same as "tcp+udp".
    val protocolRaw = labels[Labels.PROTOCOL]?.trim()?.lowercase()?.let { if (it == "udp+tcp") "tcp+udp" else it }
    val portEntries = labels[Labels.PORT]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    // A port list with protocols ("7881/tcp,7882/udp") is raw even without homegap.protocol.
    val inferredRaw = protocolRaw == null && portEntries.any { '/' in it }
    var protocol = when {
        inferredRaw || protocolRaw == "tcp+udp" -> Protocol.TCP_UDP
        else -> enumLabel(Labels.PROTOCOL, Protocol.entries.toTypedArray(), { it.label }, Protocol.HTTP)
    }
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

    val publicPort = port(Labels.PUBLIC_PORT)
    var ports = emptyList<PortMapping>()
    val port: Int
    if (!protocol.isRaw) {
        port = if (portEntries.size > 1 || portEntries.any { !it.all(Char::isDigit) }) {
            errors += "${Labels.PORT} must be one port number for ${protocol.label} services (got '${labels[Labels.PORT]?.trim()}')"
            0
        } else port(Labels.PORT) ?: if (protocol == Protocol.HTTPS) 443 else 80
    } else {
        // The protocols a port without its own "/tcp" or "/udp" gets.
        val defaults = when {
            inferredRaw -> null
            protocol == Protocol.TCP_UDP -> setOf(Protocol.TCP, Protocol.UDP)
            else -> setOf(protocol)
        }
        ports = portEntries.mapNotNull { e ->
            val m = PORT_ENTRY.matchEntire(e.lowercase())
            val pub = m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val target = m?.groupValues?.get(2)?.toIntOrNull()
            val protos = m?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.let { p ->
                p.split('+').map { if (it == "tcp") Protocol.TCP else Protocol.UDP }.toSet()
            } ?: defaults
            when {
                m == null || target == null || target !in 1..65535 || (pub != null && pub !in 1..65535) -> {
                    errors += "${Labels.PORT}: '$e' is not a port (examples: 7881, 7881/tcp, 3478/tcp+udp, 2222:22/tcp)"; null
                }
                protos == null -> { errors += "${Labels.PORT}: '$e' needs a protocol (/tcp, /udp or /tcp+udp), or set ${Labels.PROTOCOL}"; null }
                else -> PortMapping(pub ?: target, target, protos)
            }
        }
        if (portEntries.isEmpty()) errors += "${Labels.PORT} is required for ${protocol.label} services"
        if (publicPort != null) {
            if (ports.size == 1 && portEntries.single().substringBefore('/').all(Char::isDigit)) ports = listOf(ports.single().copy(publicPort = publicPort))
            else if (ports.isNotEmpty()) errors += "${Labels.PUBLIC_PORT} only works with a single port; write public:port in ${Labels.PORT} instead"
        }
        ports.flatMap { m -> m.protocols.map { m.publicPort to it } }.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { (p, proto) ->
            errors += "${Labels.PORT}: public port $p/${proto.label} is listed twice"
        }
        // What the ports add up to: tcp, udp, or both.
        val used = ports.flatMap { it.protocols }.toSet()
        if (used.isNotEmpty()) protocol = when (used) { setOf(Protocol.TCP) -> Protocol.TCP; setOf(Protocol.UDP) -> Protocol.UDP; else -> Protocol.TCP_UDP }
        port = ports.firstOrNull()?.port ?: 0
    }

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
    if (protocol.isRaw) {
        if (wildcard) errors += "${Labels.WILDCARD} is only valid for http/https services"
        if (labels[Labels.TLS] != null) warnings += "${Labels.TLS} has no effect on raw ${protocol.label} services"
    }
    if (auth != AuthMode.NONE && tls != TlsMode.TERMINATE) {
        errors += "${Labels.AUTH}=${auth.label} requires ${Labels.TLS}=terminate (the node cannot authenticate encrypted traffic)"
    }
    val terminated = (protocol == Protocol.HTTP || protocol == Protocol.HTTPS) && tls == TlsMode.TERMINATE
    val maintenanceLabel = bool(Labels.MAINTENANCE_PAGE, true)
    val maintenancePage = maintenanceLabel && terminated
    if (!terminated && maintenanceLabel && labels[Labels.MAINTENANCE_PAGE] != null) {
        if (protocol.isRaw) warnings += "${Labels.MAINTENANCE_PAGE} has no effect on raw ${protocol.label} services"
        else errors += "${Labels.MAINTENANCE_PAGE} needs ${Labels.TLS}=terminate (a node holds no certificate for a name that it passes through)"
    }
    if (auth == AuthMode.PIN && labels[Labels.PIN_ENV].isNullOrBlank()) errors += "${Labels.AUTH}=pin requires ${Labels.PIN_ENV}"
    if (wildcard && expose != domain) errors += "${Labels.WILDCARD}=true is only valid when ${Labels.EXPOSE} is the root domain"
    if (publicPort != null && !protocol.isRaw) warnings += "${Labels.PUBLIC_PORT} only applies to tcp/udp"
    if (access == Access.PUBLIC) {
        ports.map { it.publicPort }.filter { it in RESERVED_NODE_PORTS }.distinct().forEach {
            errors += "public port $it is reserved on nodes; publish it on another port (public:port in ${Labels.PORT}, or ${Labels.PUBLIC_PORT})"
        }
    }

    val proxyProtocol = bool(Labels.PROXY_PROTOCOL, false)
    if (proxyProtocol) {
        val carried = protocol == Protocol.TCP || protocol == Protocol.TCP_UDP || (protocol == Protocol.HTTPS && tls == TlsMode.PASSTHROUGH)
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
    val privateMiddleware = labels[Labels.PRIVATE_MIDDLEWARE]?.trim()?.substringBefore('@')?.takeIf { it.isNotEmpty() }
    if (privateMiddleware != null && privateNetwork == null) errors += "${Labels.PRIVATE_MIDDLEWARE} needs ${Labels.PRIVATE_NETWORK}"

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
            publicPort = ports.firstOrNull()?.publicPort,
            roles = roles,
            pinEnv = labels[Labels.PIN_ENV]?.trim(),
            paused = paused,
            proxyProtocol = proxyProtocol,
            privateNetwork = privateNetwork,
            // Without a list, the hostname itself, unless the middleware's routers name them.
            privateHosts = if (privateNetwork == null) emptyList() else privateHosts ?: if (privateMiddleware != null) emptyList() else listOf(expose),
            privateMiddleware = privateMiddleware,
            traefikApi = privateMiddleware?.let { traefikApiUrl(containerName, labels) },
            maintenancePage = maintenancePage,
            ports = ports,
            labels = labels.filterKeys { it.startsWith(Labels.PREFIX) }.entries.sortedBy { it.key }.associate { it.key to it.value },
        ),
        emptyList(),
        warnings,
    )
}

/** `[public:]port[/tcp|/udp|/tcp+udp]` */
private val PORT_ENTRY = Regex("""^(?:(\d+):)?(\d+)(?:/(tcp|udp|tcp\+udp|udp\+tcp))?$""")

private val SERVICE_NAME = Regex("^[a-z0-9]([a-z0-9-]{0,29}[a-z0-9])?$")

/** Every service one container declares: its top-level one and each named one. */
data class ContainerParseResult(val specs: List<ServiceSpec>, val errors: List<String>, val warnings: List<String>)

/**
 * Parses all of a container's services: the top-level labels (`homegap.expose`, ...) and each
 * named group `homegap.services.<name>.<label>`. A named service takes [Labels.INHERITED] from
 * the top-level labels unless it sets its own. Errors of a named service start with its name and
 * leave the container's other services alone.
 */
fun parseContainerLabels(containerId: String, containerName: String, labels: Map<String, String>, domain: String): ContainerParseResult {
    val specs = mutableListOf<ServiceSpec>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val top = labels.filterKeys { !it.startsWith(Labels.SERVICES_PREFIX) }
    val top0 = parseLabels(containerId, containerName, top, domain)
    top0.spec?.let { specs += it }
    errors += top0.errors
    warnings += top0.warnings

    val groups = labels.keys.filter { it.startsWith(Labels.SERVICES_PREFIX) }
        .groupBy({ it.removePrefix(Labels.SERVICES_PREFIX).substringBefore('.') }, { it })
    for ((name, keys) in groups.entries.sortedBy { it.key }) {
        val where = "services.$name"
        if (!SERVICE_NAME.matches(name)) {
            errors += "$where: the name must be lowercase letters, digits and dashes"
            continue
        }
        val own = keys.filter { '.' in it.removePrefix(Labels.SERVICES_PREFIX) }
            .associate { Labels.PREFIX + it.removePrefix(Labels.SERVICES_PREFIX + name + ".") to labels.getValue(it) }
        if (Labels.EXPOSE !in own) {
            errors += "$where: ${Labels.EXPOSE} is required (as ${Labels.SERVICES_PREFIX}$name.expose)"
            continue
        }
        val merged = top.filterKeys { it in Labels.INHERITED } + own
        val r = parseLabels(containerId, containerName, merged, domain)
        r.errors.forEach { errors += "$where: $it" }
        r.warnings.forEach { warnings += "$where: ${it.replace("'homegap.", "'${Labels.SERVICES_PREFIX}$name.")}" }
        r.spec?.let { specs += it.copy(name = name) }
    }
    return ContainerParseResult(specs, errors, warnings)
}

/** Ports a regional node already uses for itself (SSH, web, tunnels, Pangolin internals). */
val RESERVED_NODE_PORTS = setOf(
    22, 80, 443, 3000, 3001, 3002, 3003, 3004, 3005, 8080, 21820, 51820,
    // The cluster services, the Traefik metrics and the maintenance page of the node.
    2379, 2380, 5432, 5433, 6379, 6380, 8008, 8082, 8090, 26379,
)
