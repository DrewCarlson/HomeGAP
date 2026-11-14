package homegap.discovery

/**
 * The `homegap.*` Docker label schema.
 *
 * Spec labels: expose, wildcard, port, protocol, tls, regions, access, auth, healthcheck.
 *
 * Extensions:
 * - host: a different target host.
 * - public_port: the raw TCP/UDP listen port on the nodes.
 * - roles: the Pangolin roles that have private or SSO access.
 * - pin_env: the environment variable that holds a 6-digit PIN.
 * - pause: HomeGAP keeps the manual overrides on the resources of this service.
 * - proxy_protocol: the node sends the viewer's address to the service with PROXY protocol v2.
 * - private_network: Pangolin clients reach the service at its address on this Docker network,
 *   which only their traffic uses.
 * - private_hosts: the names they reach it by that way. The default is the hostname of the service.
 * - private_middleware: adds each name that a router of this Traefik serves through this
 *   middleware. HomeGAP reads the routers from the API of that Traefik, at traefik_api.
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

    val KNOWN = setOf(
        EXPOSE,
        WILDCARD,
        PORT,
        PROTOCOL,
        TLS,
        REGIONS,
        ACCESS,
        AUTH,
        HEALTHCHECK,
        HOST,
        PUBLIC_PORT,
        ROLES,
        PIN_ENV,
        PAUSE,
        PROXY_PROTOCOL,
        PRIVATE_NETWORK,
        PRIVATE_HOSTS,
        PRIVATE_MIDDLEWARE,
        TRAEFIK_API,
        MAINTENANCE_PAGE,
    )
}

enum class Protocol(val label: String) {
    HTTP("http"), HTTPS("https"), TCP("tcp"), UDP("udp"),

    /** Raw ports, some or all of them on both TCP and UDP (`homegap.protocol: tcp+udp`, or per port). */
    TCP_UDP("tcp+udp");

    /** The nodes publish the service as raw ports and do not route it by name. */
    val isRaw: Boolean get() = this == TCP || this == UDP || this == TCP_UDP
}

/**
 * One raw port of a service: [publicPort] on the nodes to [port] on the container, on each of
 * [protocols] (TCP and/or UDP). From `homegap.port: "7881/tcp, 7882/udp, 3478/tcp+udp, 2222:22"`.
 */
data class PortMapping(val publicPort: Int, val port: Int, val protocols: Set<Protocol>) {
    override fun toString(): String {
        val publicPart = if (publicPort != port) "$publicPort:" else ""
        val protocolNames = protocols.sortedBy { it.ordinal }.joinToString("+") { it.label }
        return "$publicPart$port/$protocolNames"
    }
}

enum class TlsMode(val label: String) { PASSTHROUGH("passthrough"), TERMINATE("terminate") }
enum class Access(val label: String) { PUBLIC("public"), PRIVATE("private") }
enum class AuthMode(val label: String) { NONE("none"), SSO("sso"), PIN("pin") }

sealed interface HealthCheck {
    data class Http(val path: String) : HealthCheck
    data object Tcp : HealthCheck
}

/** A service with valid labels, as one container declares it. */
data class ServiceSpec(
    val containerId: String,
    val containerName: String,
    /** The host that the tunnel connector dials. The default is the container name on the shared Docker network. */
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
    /** The container's address on [privateNetwork]. The planner reads it from Docker. */
    val privateHost: String? = null,
    /** The names Pangolin clients reach it by over [privateNetwork]. Full names, which can have `*` and `?`. */
    val privateHosts: List<String> = emptyList(),
    /** The routers with this Traefik middleware (name without `@provider`) add their names to [privateHosts]. */
    val privateMiddleware: String? = null,
    /** Where this container's Traefik API answers (with [privateMiddleware]). */
    val traefikApi: String? = null,
    /**
     * The nodes show the maintenance page for this service while the homelab is unreachable. Only a
     * service that the node terminates can have the page.
     */
    val maintenancePage: Boolean = false,
    /** Its name under `homegap.services.<name>`. Null for the top-level service of the container. */
    val name: String? = null,
    /** Raw services: every published port (at least one). Empty for http/https. */
    val ports: List<PortMapping> = emptyList(),
    /** The `homegap.*` labels of the service, as the container has them. */
    val labels: Map<String, String> = emptyMap(),
) {
    /**
     * The stable identifier that is the key of each Pangolin and Bunny object of the service. A
     * named service adds its name after `--`, which the slug of a hostname never contains.
     */
    val key: String get() = slug(hostname) + (name?.let { "--" + slug(it) }.orEmpty())

    /** The text in logs and errors: the container, and the name of the service when it has one. */
    val label: String get() = containerName + (name?.let { " ($it)" }.orEmpty())

    fun servesNode(nodeId: String): Boolean = regions == null || nodeId in regions
}

data class LabelParseResult(val spec: ServiceSpec?, val errors: List<String>, val warnings: List<String> = emptyList())

private const val DNS_LABEL = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
private val HOSTNAME = Regex("^(?=.{1,253}$)($DNS_LABEL)(\\.$DNS_LABEL)+$")

private val PORT_NUMBERS = 1..65535

/**
 * The names served by the Traefik routers that use [middleware] (compared without `@provider`),
 * and the routers whose names cannot be read (a `HostRegexp` rule, or no `Host` at all).
 */
fun routerHosts(routers: List<TraefikRouter>, middleware: String): Pair<List<String>, List<String>> {
    val hosts = mutableListOf<String>()
    val unreadable = mutableListOf<String>()
    for ((name, rule, middlewares, status) in routers) {
        if (status == "disabled" || middlewares.none { it.substringBefore('@') == middleware }) {
            continue
        }
        val found = HOST_RULE.findAll(rule)
            .flatMap { host -> RULE_STRING.findAll(host.groupValues[1]).map { it.groupValues[1].lowercase() } }
            .toList()
        if (found.isEmpty() || "HostRegexp" in rule) {
            unreadable += name
        }
        hosts += found
    }
    return hosts.distinct() to unreadable
}

/** The Traefik API of a front door container: `homegap.traefik_api`, or its name on port 8080. */
fun traefikApiUrl(containerName: String, labels: Map<String, String>): String =
    labels[Labels.TRAEFIK_API]?.trim()?.trimEnd('/')?.takeIf(::isLocalApiUrl) ?: defaultTraefikApiUrl(containerName)

/**
 * The Traefik API of each service of a container that has `homegap.private_middleware`: the
 * top-level service and each named service.
 */
fun traefikApiUrls(containerName: String, labels: Map<String, String>): List<String> {
    val topLabels = labels.filterKeys { !it.startsWith(Labels.SERVICES_PREFIX) }
    return (listOf(topLabels) + namedServiceLabels(labels).values)
        .filter { !it[Labels.PRIVATE_MIDDLEWARE].isNullOrBlank() }
        .map { traefikApiUrl(containerName, it) }
        .distinct()
}

/** True if [labels] declare a service: a top-level `homegap.expose`, or a named service. */
fun declaresService(labels: Map<String, String>): Boolean =
    Labels.EXPOSE in labels || labels.keys.any { it.startsWith(Labels.SERVICES_PREFIX) }

/** The Traefik API of a container on a Docker network of the homelab, where it has no TLS. */
@Suppress("HttpUrlsUsage")
private fun defaultTraefikApiUrl(containerName: String): String = "http://$containerName:8080"

/**
 * True if [url] is `http(s)://host[:port]` with no path, and the host is a name without a dot (a
 * container on a Docker network) or a private IPv4 address. A label must not make the
 * orchestrator send requests to other hosts.
 */
fun isLocalApiUrl(url: String): Boolean {
    val match = API_URL.matchEntire(url) ?: return false
    val host = match.groupValues[1]
    val port = match.groupValues[2]
    if (port.isNotEmpty() && port.toIntOrNull() !in PORT_NUMBERS) {
        return false
    }
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val first = octets[0]!!
        val second = octets[1]!!
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }
    return '.' !in host
}

private val API_URL = Regex("^https?://([A-Za-z0-9][A-Za-z0-9_.-]{0,252})(?::(\\d{1,5}))?$")

private val HOST_RULE = Regex("""\bHost\(([^)]*)\)""")
private val RULE_STRING = Regex("""[`"']([^`"']+)[`"']""")

private const val ALIAS_LABEL = "[a-z0-9*?](?:[a-z0-9*?-]{0,61}[a-z0-9*?])?"
private const val ALIAS_LAST_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"

/** A Pangolin alias: a full name whose labels can use `*` and `?`. */
private val PRIVATE_ALIAS = Regex("^(?:$ALIAS_LABEL\\.)+$ALIAS_LAST_LABEL$")

/** Whether the Pangolin alias [pattern] (`*` any run of characters, `?` one) covers [host]. */
fun aliasCovers(pattern: String, host: String): Boolean {
    val expression = pattern.split('*').joinToString(".*") { run ->
        run.split('?').joinToString(".") { Regex.escape(it) }
    }
    return Regex("^$expression$").matches(host)
}

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
    domains: List<String>,
): LabelParseResult = LabelParser(containerName, labels, domains).parse(containerId)

/**
 * Reads the labels of one service. The order of the steps in [parse] is the order of the errors
 * and warnings in the result.
 */
private class LabelParser(
    private val containerName: String,
    private val labels: Map<String, String>,
    /** The domains that a service can use. The first one is the domain of the platform. */
    private val domains: List<String>,
) {
    private val errors = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    /** The hostname of the service, from `homegap.expose`. */
    private val expose = text(Labels.EXPOSE)?.lowercase().orEmpty()

    private val portEntries = labels[Labels.PORT]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    // "udp+tcp" reads the same as "tcp+udp".
    private val protocolText = text(Labels.PROTOCOL)?.lowercase()?.let { if (it == "udp+tcp") "tcp+udp" else it }

    // A port list with protocols ("7881/tcp,7882/udp") is raw even without homegap.protocol.
    private val inferredRaw = protocolText == null && portEntries.any { '/' in it }

    fun parse(containerId: String): LabelParseResult {
        if (Labels.EXPOSE !in labels) {
            return LabelParseResult(null, emptyList())
        }
        warnOfUnknownLabels()
        checkHostname()

        val wildcard = bool(Labels.WILDCARD, default = false)
        val declaredProtocol = declaredProtocol()
        val declaredTls = enumLabel(Labels.TLS, TlsMode.entries, TlsMode.PASSTHROUGH) { it.label }
        val access = enumLabel(Labels.ACCESS, Access.entries, Access.PUBLIC) { it.label }
        val auth = enumLabel(Labels.AUTH, AuthMode.entries, AuthMode.NONE) { it.label }
        val paused = bool(Labels.PAUSE, default = false)

        val publicPort = port(Labels.PUBLIC_PORT)
        val ports = if (declaredProtocol.isRaw) rawPorts(declaredProtocol, publicPort) else emptyList()
        val port = if (declaredProtocol.isRaw) ports.firstOrNull()?.port ?: 0 else httpPort(declaredProtocol)
        val protocol = protocolOf(ports) ?: declaredProtocol

        val regions = regions()
        val healthcheck = healthcheck()

        val tls = effectiveTls(protocol, declaredTls)
        checkTlsRules(protocol, tls, auth, wildcard)
        val maintenancePage = maintenancePage(protocol, tls)
        checkServiceRules(protocol, auth, wildcard, publicPort)
        checkReservedPorts(access, ports)

        val proxyProtocol = proxyProtocol(protocol, tls, access)
        val privateNetwork = privateNetwork(protocol, access)
        val declaredPrivateHosts = declaredPrivateHosts(privateNetwork)
        val privateMiddleware = privateMiddleware(privateNetwork)

        if (errors.isNotEmpty()) {
            return LabelParseResult(null, errors, warnings)
        }
        val spec = ServiceSpec(
            containerId = containerId,
            containerName = containerName,
            targetHost = text(Labels.HOST)?.takeIf { it.isNotEmpty() } ?: containerName,
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
            roles = labels[Labels.ROLES]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            pinEnv = text(Labels.PIN_ENV),
            paused = paused,
            proxyProtocol = proxyProtocol,
            privateNetwork = privateNetwork,
            privateHosts = when {
                privateNetwork == null -> emptyList()
                declaredPrivateHosts != null -> declaredPrivateHosts
                // Without a list, the hostname itself, unless the middleware's routers name them.
                privateMiddleware != null -> emptyList()
                else -> listOf(expose)
            },
            privateMiddleware = privateMiddleware,
            traefikApi = privateMiddleware?.let { traefikApiUrl(containerName, labels) },
            maintenancePage = maintenancePage,
            ports = ports,
            labels = labels.filterKeys { it.startsWith(Labels.PREFIX) }
                .entries
                .sortedBy { it.key }
                .associate { it.key to it.value },
        )
        return LabelParseResult(spec, emptyList(), warnings)
    }

    // ------------------------------------------------------------------ label values

    private fun text(key: String): String? = labels[key]?.trim()

    private fun bool(key: String, default: Boolean): Boolean = when (text(key)?.lowercase()) {
        null, "" -> default
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> {
            errors += "$key must be true/false"
            default
        }
    }

    private fun <E : Enum<E>> enumLabel(key: String, values: List<E>, default: E, label: (E) -> String): E {
        val raw = text(key)?.lowercase() ?: return default
        val value = values.firstOrNull { label(it) == raw }
        if (value == null) {
            errors += "$key must be one of ${values.joinToString("/") { label(it) }} (got '$raw')"
            return default
        }
        return value
    }

    private fun port(key: String): Int? {
        val raw = text(key) ?: return null
        val port = raw.toIntOrNull()
        if (port == null || port !in PORT_NUMBERS) {
            errors += "$key must be a port number (got '$raw')"
            return null
        }
        return port
    }

    // ------------------------------------------------------------------ hostname and protocol

    private fun warnOfUnknownLabels() {
        labels.keys
            .filter { it.startsWith(Labels.PREFIX) && it !in Labels.KNOWN && !it.startsWith(Labels.SERVICES_PREFIX) }
            .forEach { warnings += "unknown label '$it' ignored" }
    }

    private fun checkHostname() {
        if (!HOSTNAME.matches(expose)) {
            errors += "${Labels.EXPOSE} '$expose' is not a valid hostname"
        }
        if (domains.none { expose == it || expose.endsWith(".$it") }) {
            val managed = when (domains.size) {
                1 -> "the managed domain '${domains.single()}'"
                else -> "the managed domains " + domains.joinToString { "'$it'" }
            }
            errors += "${Labels.EXPOSE} '$expose' is outside $managed"
        }
    }

    /** The protocol that the labels give. For a raw service, the ports can make it narrower. */
    private fun declaredProtocol(): Protocol = when {
        inferredRaw || protocolText == "tcp+udp" -> Protocol.TCP_UDP
        else -> enumLabel(Labels.PROTOCOL, Protocol.entries, Protocol.HTTP) { it.label }
    }

    // ------------------------------------------------------------------ ports

    private fun httpPort(protocol: Protocol): Int {
        if (portEntries.size > 1 || portEntries.any { !it.all(Char::isDigit) }) {
            val got = labels[Labels.PORT]?.trim()
            errors += "${Labels.PORT} must be one port number for ${protocol.label} services (got '$got')"
            return 0
        }
        return port(Labels.PORT) ?: if (protocol == Protocol.HTTPS) 443 else 80
    }

    private fun rawPorts(protocol: Protocol, publicPort: Int?): List<PortMapping> {
        // The protocols a port without its own "/tcp" or "/udp" gets.
        val defaults = when {
            inferredRaw -> null
            protocol == Protocol.TCP_UDP -> setOf(Protocol.TCP, Protocol.UDP)
            else -> setOf(protocol)
        }
        var ports = portEntries.mapNotNull { portMapping(it, defaults) }
        if (portEntries.isEmpty()) {
            errors += "${Labels.PORT} is required for ${protocol.label} services"
        }
        if (publicPort != null) {
            val entry = portEntries.singleOrNull()
            if (ports.size == 1 && entry != null && entry.substringBefore('/').all(Char::isDigit)) {
                ports = listOf(ports.single().copy(publicPort = publicPort))
            } else if (ports.isNotEmpty()) {
                errors += "${Labels.PUBLIC_PORT} only works with a single port; " +
                    "write public:port in ${Labels.PORT} instead"
            }
        }
        ports.flatMap { mapping -> mapping.protocols.map { mapping.publicPort to it } }
            .groupBy { it }
            .filter { it.value.size > 1 }
            .keys
            .forEach { (port, portProtocol) ->
                errors += "${Labels.PORT}: public port $port/${portProtocol.label} is listed twice"
            }
        return ports
    }

    /** Reads one entry of `homegap.port` of a raw service: `[public:]port[/tcp|/udp|/tcp+udp]`. */
    private fun portMapping(entry: String, defaults: Set<Protocol>?): PortMapping? {
        val match = PORT_ENTRY.matchEntire(entry.lowercase())
        val publicPort = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        val target = match?.groupValues?.get(2)?.toIntOrNull()
        val protocols = match?.groupValues?.get(3)
            ?.takeIf { it.isNotEmpty() }
            ?.split('+')
            ?.map { if (it == "tcp") Protocol.TCP else Protocol.UDP }
            ?.toSet()
            ?: defaults
        val validPorts = target != null && target in PORT_NUMBERS && (publicPort == null || publicPort in PORT_NUMBERS)
        return when {
            match == null || target == null || !validPorts -> {
                errors += "${Labels.PORT}: '$entry' is not a port " +
                    "(examples: 7881, 7881/tcp, 3478/tcp+udp, 2222:22/tcp)"
                null
            }

            protocols == null -> {
                errors += "${Labels.PORT}: '$entry' needs a protocol (/tcp, /udp or /tcp+udp), " +
                    "or set ${Labels.PROTOCOL}"
                null
            }

            else -> PortMapping(publicPort ?: target, target, protocols)
        }
    }

    /** What the ports add up to: tcp, udp, or both. Null without ports. */
    private fun protocolOf(ports: List<PortMapping>): Protocol? {
        val used = ports.flatMap { it.protocols }.toSet()
        return when {
            used.isEmpty() -> null
            used == setOf(Protocol.TCP) -> Protocol.TCP
            used == setOf(Protocol.UDP) -> Protocol.UDP
            else -> Protocol.TCP_UDP
        }
    }

    // ------------------------------------------------------------------ regions and health check

    private fun regions(): List<String>? {
        val raw = text(Labels.REGIONS)?.lowercase()
        if (raw == null || raw == "" || raw == "all") {
            return null
        }
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun healthcheck(): HealthCheck? {
        val raw = text(Labels.HEALTHCHECK)
        return when {
            raw == null || raw == "" -> null
            raw == "tcp" -> HealthCheck.Tcp
            raw.startsWith("/") -> HealthCheck.Http(raw)
            else -> {
                errors += "${Labels.HEALTHCHECK} must be an HTTP path starting with '/' or 'tcp'"
                HealthCheck.Http("/")
            }
        }
    }

    // ------------------------------------------------------------------ rules between the labels

    private fun effectiveTls(protocol: Protocol, declaredTls: TlsMode): TlsMode {
        if (protocol != Protocol.HTTP) {
            return declaredTls
        }
        if (declaredTls == TlsMode.PASSTHROUGH && labels[Labels.TLS] != null) {
            warnings += "plain-http services cannot be passed through; the node terminates TLS (tls=terminate)"
        }
        return TlsMode.TERMINATE
    }

    private fun checkTlsRules(protocol: Protocol, tls: TlsMode, auth: AuthMode, wildcard: Boolean) {
        if (protocol.isRaw) {
            if (wildcard) {
                errors += "${Labels.WILDCARD} is only valid for http/https services"
            }
            if (labels[Labels.TLS] != null) {
                warnings += "${Labels.TLS} has no effect on raw ${protocol.label} services"
            }
        }
        if (auth != AuthMode.NONE && tls != TlsMode.TERMINATE) {
            errors += "${Labels.AUTH}=${auth.label} requires ${Labels.TLS}=terminate " +
                "(the node cannot authenticate encrypted traffic)"
        }
    }

    private fun maintenancePage(protocol: Protocol, tls: TlsMode): Boolean {
        val terminated = (protocol == Protocol.HTTP || protocol == Protocol.HTTPS) && tls == TlsMode.TERMINATE
        val wanted = bool(Labels.MAINTENANCE_PAGE, default = true)
        if (!terminated && wanted && labels[Labels.MAINTENANCE_PAGE] != null) {
            if (protocol.isRaw) {
                warnings += "${Labels.MAINTENANCE_PAGE} has no effect on raw ${protocol.label} services"
            } else {
                errors += "${Labels.MAINTENANCE_PAGE} needs ${Labels.TLS}=terminate " +
                    "(a node holds no certificate for a name that it passes through)"
            }
        }
        return wanted && terminated
    }

    private fun checkServiceRules(protocol: Protocol, auth: AuthMode, wildcard: Boolean, publicPort: Int?) {
        if (auth == AuthMode.PIN && labels[Labels.PIN_ENV].isNullOrBlank()) {
            errors += "${Labels.AUTH}=pin requires ${Labels.PIN_ENV}"
        }
        if (wildcard && expose !in domains) {
            errors += "${Labels.WILDCARD}=true is only valid when ${Labels.EXPOSE} is the root domain"
        }
        if (publicPort != null && !protocol.isRaw) {
            warnings += "${Labels.PUBLIC_PORT} only applies to tcp/udp"
        }
    }

    private fun checkReservedPorts(access: Access, ports: List<PortMapping>) {
        if (access != Access.PUBLIC) {
            return
        }
        ports.map { it.publicPort }.filter { it in RESERVED_NODE_PORTS }.distinct().forEach {
            errors += "public port $it is reserved on nodes; publish it on another port " +
                "(public:port in ${Labels.PORT}, or ${Labels.PUBLIC_PORT})"
        }
    }

    // ------------------------------------------------------------------ PROXY protocol and private path

    private fun proxyProtocol(protocol: Protocol, tls: TlsMode, access: Access): Boolean {
        val proxyProtocol = bool(Labels.PROXY_PROTOCOL, default = false)
        if (!proxyProtocol) {
            return false
        }
        val carried = protocol == Protocol.TCP ||
            protocol == Protocol.TCP_UDP ||
            (protocol == Protocol.HTTPS && tls == TlsMode.PASSTHROUGH)
        if (!carried) {
            errors += "${Labels.PROXY_PROTOCOL} needs an https passthrough or tcp service " +
                "(the node only adds it to TCP streams)"
        }
        if (access == Access.PRIVATE) {
            errors += "${Labels.PROXY_PROTOCOL} only applies to public services"
        }
        return true
    }

    private fun privateNetwork(protocol: Protocol, access: Access): String? {
        val privateNetwork = text(Labels.PRIVATE_NETWORK)?.takeIf { it.isNotEmpty() } ?: return null
        if (access == Access.PRIVATE) {
            errors += "${Labels.PRIVATE_NETWORK} is for public services " +
                "(a private service already goes to ${Labels.HOST})"
        }
        if (protocol != Protocol.HTTP && protocol != Protocol.HTTPS) {
            errors += "${Labels.PRIVATE_NETWORK} only applies to http/https services"
        }
        return privateNetwork
    }

    /** The names in `homegap.private_hosts`, or null without the label. */
    private fun declaredPrivateHosts(privateNetwork: String?): List<String>? {
        // Names relative to the service's hostname ("jellyfin", "*.home") or full names under it.
        val privateHosts = labels[Labels.PRIVATE_HOSTS]
            ?.split(',')
            ?.map { it.trim().lowercase().trimEnd('.') }
            ?.filter { it.isNotEmpty() }
            ?.map { if (it == expose || it.endsWith(".$expose")) it else "$it.$expose" }
            ?.distinct()
            ?: return null
        if (privateNetwork == null) {
            errors += "${Labels.PRIVATE_HOSTS} needs ${Labels.PRIVATE_NETWORK}"
        }
        privateHosts.filter { !PRIVATE_ALIAS.matches(it) }.forEach {
            errors += "${Labels.PRIVATE_HOSTS}: '$it' is not a valid name"
        }
        return privateHosts
    }

    private fun privateMiddleware(privateNetwork: String?): String? {
        val privateMiddleware = text(Labels.PRIVATE_MIDDLEWARE)?.substringBefore('@')?.takeIf { it.isNotEmpty() }
        val traefikApi = text(Labels.TRAEFIK_API)?.trimEnd('/')
        if (traefikApi != null && !isLocalApiUrl(traefikApi)) {
            warnings += "${Labels.TRAEFIK_API} must be http(s)://<container name or private address>[:port] " +
                "with no path; HomeGAP uses ${defaultTraefikApiUrl(containerName)}"
        }
        if (privateMiddleware != null && privateNetwork == null) {
            errors += "${Labels.PRIVATE_MIDDLEWARE} needs ${Labels.PRIVATE_NETWORK}"
        }
        return privateMiddleware
    }
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
fun parseContainerLabels(
    containerId: String,
    containerName: String,
    labels: Map<String, String>,
    domains: List<String>,
): ContainerParseResult {
    val specs = mutableListOf<ServiceSpec>()
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val topLabels = labels.filterKeys { !it.startsWith(Labels.SERVICES_PREFIX) }
    val topResult = parseLabels(containerId, containerName, topLabels, domains)
    topResult.spec?.let { specs += it }
    errors += topResult.errors
    warnings += topResult.warnings

    for ((name, ownLabels) in namedServiceLabels(labels).entries.sortedBy { it.key }) {
        val where = "services.$name"
        if (!SERVICE_NAME.matches(name)) {
            errors += "$where: the name must be lowercase letters, digits and dashes"
            continue
        }
        if (Labels.EXPOSE !in ownLabels) {
            errors += "$where: ${Labels.EXPOSE} is required (as ${Labels.SERVICES_PREFIX}$name.expose)"
            continue
        }
        val merged = topLabels.filterKeys { it in Labels.INHERITED } + ownLabels
        val result = parseLabels(containerId, containerName, merged, domains)
        result.errors.forEach { errors += "$where: $it" }
        result.warnings.forEach { warnings += "$where: ${it.replace("'homegap.", "'${Labels.SERVICES_PREFIX}$name.")}" }
        result.spec?.let { specs += it.copy(name = name) }
    }
    return ContainerParseResult(specs, errors, warnings)
}

/** The labels of each named service, by its name, with the keys of top-level labels (`homegap.<label>`). */
private fun namedServiceLabels(labels: Map<String, String>): Map<String, Map<String, String>> =
    labels.keys
        .filter { it.startsWith(Labels.SERVICES_PREFIX) }
        .groupBy { it.removePrefix(Labels.SERVICES_PREFIX).substringBefore('.') }
        .mapValues { (name, keys) ->
            val prefix = Labels.SERVICES_PREFIX + name + "."
            keys.filter { '.' in it.removePrefix(Labels.SERVICES_PREFIX) }
                .associate { Labels.PREFIX + it.removePrefix(prefix) to labels.getValue(it) }
        }

/** Ports a regional node already uses for itself (SSH, web, tunnels, Pangolin internals). */
val RESERVED_NODE_PORTS = setOf(
    22, 80, 443, 3000, 3001, 3002, 3003, 3004, 3005, 8080, 21820, 51820,
    // The cluster services, the Traefik metrics and the maintenance page of the node.
    2379, 2380, 5432, 5433, 6379, 6380, 8008, 8082, 8090, 26379,
)
