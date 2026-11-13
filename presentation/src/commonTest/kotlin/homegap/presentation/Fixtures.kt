package homegap.presentation

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import homegap.api.*
import homegap.presentation.core.Presenter
import homegap.presentation.data.*
import homegap.presentation.format.TimeFormatter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

const val NOW = 1_800_000_000_000L

/**
 * Composes this presenter once, for the rest of the test, and exposes its latest model. Events
 * sent from a model act on this same composition, so state changes show up in later models.
 */
fun <P : Any, M> TestScope.present(presenter: Presenter<P, M>, props: P): StateFlow<M> {
    // Molecule's JS build reads window.performance; Node has performance but no window.
    js("if (typeof window === 'undefined') globalThis.window = globalThis")
    return backgroundScope.launchMolecule(RecompositionMode.Immediate) { presenter.model(props) }
}

object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
}

object FakeTimeFormatter : TimeFormatter {
    override fun time(epochMillis: Long): String = "t$epochMillis"
    override fun dateTime(epochMillis: Long): String = "d$epochMillis"
    override fun date(epochMillis: Long): String = "day$epochMillis"
}

class FakeTokenStore(
    override var token: String? = null,
) : TokenStore

class FakePreferences : Preferences {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Records calls; reads return the configured values, the event stream never ends. */
class FakeControllerApi(
    var statusValue: StatusSnapshot = snapshot(),
    var speedtestValue: SpeedTestOverview = SpeedTestOverview(enabled = false),
) : ControllerApi {
    val calls = mutableListOf<String>()

    private fun ok(call: String): ActionResponse {
        calls += call
        return ActionResponse(true, "ok: $call")
    }

    override suspend fun status() = statusValue
    override suspend fun diff() = DiffResponse(0, listOf(DiffItemDto("dns", "x", "k", "none"), DiffItemDto("dns", "y", "k2", "create")), "{}")
    override suspend fun secrets() = SecretsResponse(listOf(SecretRefDto("bunny", "DNS", "env:B", present = false)))
    override suspend fun config() = ConfigResponse("/config.yml", false, "domain: x", emptyList())
    override suspend fun speedtest() = speedtestValue
    override suspend fun metrics(rangeSeconds: Long, edge: String?) = MetricsResponse(emptyList())
    override suspend fun search(query: String) = SearchResponse(emptyList())
    override suspend fun log(beforeSeq: Long?, limit: Int, level: LogLevel?, domain: String?, query: String?) = LogResponse(emptyList())
    override suspend fun events(afterSeq: Long): Flow<ServerEvent> = flow { awaitCancellation() }
    override suspend fun resync() = ok("resync")
    override suspend fun applyOnce() = ok("apply once")
    override suspend fun restartTunnel(node: String) = ok("restart tunnel $node")
    override suspend fun orderCertificate(replaceKey: Boolean) = ok(if (replaceKey) "replace key" else "order certificate")
    override suspend fun resendCertificate(node: String) = ok("resend certificate $node")
    override suspend fun checkCertificates() = ok("check certificates")
    override suspend fun undo(id: String) = ok("undo $id")
    override suspend fun reissueInvitation(id: String) = CreateAccessUserResponse(true, "new link", "https://pangolin.x/invite?token=new").also { calls += "reissue $id" }
    override suspend fun provision(node: String) = ok("provision $node")
    override suspend fun startSpeedRun(streams: Int) = ok("speed run $streams")
    override suspend fun cancelSpeedRun() = ok("cancel speed run")
    override suspend fun retryUpdate(node: String) = ok("retry $node")
    override suspend fun skipUpdate(node: String) = ok("skip $node")
    override suspend fun pause(scope: PauseScope, id: String?, paused: Boolean) = ok("pause ${scope.wire} $id $paused")
    override suspend fun addNode(node: NodeConfigDto) = ok("add ${node.id} ${node.host} ${node.sshKeyFile}")
    override suspend fun removeNode(id: String) = ok("remove $id")
    override suspend fun decommission(id: String, confirm: String, force: Boolean) = ok("decommission $id $confirm${if (force) " force" else ""}")
    override suspend fun speedLink() = SpeedTestLinkDto("http://speedtest.x/", mapOf("a1" to "https://speedtest-a1.x/#t=1"), NOW)
    override suspend fun runTunnelTest(node: String, streams: Int): SpeedTestResultDto {
        calls += "test $node $streams"
        return SpeedTestResultDto("r", NOW, "tunnel", node, downMbps = 250.0, upMbps = 40.0, pingMs = 170.0, streams = streams)
    }


    var accessValue = AccessOverviewDto(available = false, note = "no cluster")
    var lastCreate: CreateAccessUserRequest? = null
    override suspend fun access() = accessValue
    override suspend fun createUser(request: CreateAccessUserRequest): CreateAccessUserResponse {
        lastCreate = request
        calls += "create ${request.email} ${request.roleIds} ${if (request.password != null) "password" else "invite"}"
        return CreateAccessUserResponse(true, "created", if (request.password == null) "https://pangolin.x/invite?token=a-b" else null)
    }
    override suspend fun switchPrimary(to: String) = ok("switch $to")
    var locationsValue = LocationsResponse(listOf(
        LocationDto("us-dallas", "Dallas, Texas", "United States", "US", "NA", 32.78, -96.8, listOf("Vultr", "AWS"), builtIn = true),
        LocationDto("in-mumbai", "Mumbai", "India", "IN", "AS", 19.08, 72.88, listOf("AWS", "Vultr"), builtIn = true),
        LocationDto("sg-singapore", "Singapore", "Singapore", "SG", "AS", 1.35, 103.82, listOf("Vultr"), builtIn = true),
    ))
    override suspend fun locations() = locationsValue
    override suspend fun saveLocation(location: LocationDto) = ok("save-location ${location.id} ${location.name}")
    override suspend fun deleteLocation(id: String, reset: Boolean) = ok("delete-location $id $reset")
    override suspend fun setHomelabLocation(place: PlaceDto?) = ok("homelab-location ${place?.name}")
    override suspend fun setNodeLocation(id: String, place: PlaceDto) = ok("node-location $id ${place.name}")
    override suspend fun nodeLogs(node: String, service: String, since: String, tail: Int): NodeTextDto {
        calls += "logs $node $service $since $tail"
        return NodeTextDto(node, "$service logs", "line 1\nline 2", NOW)
    }
    override suspend fun nodeDiagnostics(node: String): NodeTextDto {
        calls += "diagnostics $node"
        return NodeTextDto(node, "Diagnostics", "=== System", NOW)
    }
    override suspend fun removeUser(id: String) = ok("remove-user $id")
    override suspend fun setUserRoles(id: String, roleIds: List<Int>) = ok("roles $id $roleIds")
    override suspend fun removeInvitation(id: String) = ok("remove-invite $id")
    override suspend fun deviceAction(id: Int, action: String) = ok("device $id $action")

    var terminalsValue = TerminalOverviewDto(enabled = false)

    /** Terminals that the ticket call no longer knows. */
    val goneTerminals = mutableSetOf<String>()
    private var terminalCount = 0
    private var ticketCount = 0

    override suspend fun terminals() = terminalsValue

    override suspend fun openTerminal(node: String, rows: Int, columns: Int): TerminalSessionDto {
        calls += "terminal $node $rows $columns"
        return TerminalSessionDto(id = "t${++terminalCount}", nodeId = node, startedAt = NOW)
    }

    override suspend fun terminalTicket(id: String): TerminalTicketDto {
        calls += "ticket $id"
        if (id in goneTerminals) throw ApiException(400, "unknown terminal $id")
        return TerminalTicketDto("$id-ticket-${++ticketCount}")
    }

    override suspend fun closeTerminal(id: String) = ok("close terminal $id")

    var connection = ConnectionTestDto(
        ok = true, hostKeyFingerprint = "SHA256:abc", os = "ubuntu", osVersion = "24.04", arch = "x86_64",
        supported = true, privilege = "root", clientIp = "198.51.100.7",
    )
    var lastSetup: NodeSetupRequestDto? = null
    var setupStates = ArrayDeque(listOf("running", "succeeded"))

    /** When set, the server's host key is not recorded: a password login needs this fingerprint. */
    var unrecordedHostKey: String? = null
    val logins = mutableListOf<SshLoginDto>()

    override suspend fun testConnection(login: SshLoginDto): ConnectionTestDto {
        calls += "connect ${login.user}@${login.host}:${login.port} ${if (login.password != null) "password" else "key"}"
        logins += login
        val hostKey = unrecordedHostKey
        return if (hostKey != null && login.password != null && login.hostKeyFingerprint != hostKey) {
            ConnectionTestDto(ok = false, hostKeyUnconfirmed = true, hostKeyFingerprint = hostKey)
        } else {
            connection
        }
    }

    override suspend fun createKey(request: SshKeyRequestDto): SshKeyDto {
        calls += "key ${request.nodeId} ${request.mode}"
        return SshKeyDto("/data/ssh/${request.nodeId}_ed25519", "ssh-ed25519 AAAA homegap-${request.nodeId}", "SHA256:key")
    }

    override suspend fun startSetup(request: NodeSetupRequestDto): SetupJobDto {
        calls += "setup ${request.node.id}"
        lastSetup = request
        return job(setupStates.removeFirstOrNull() ?: "running")
    }

    override suspend fun setupJob(id: String): SetupJobDto = job(setupStates.removeFirstOrNull() ?: "succeeded")

    private fun job(state: String) = SetupJobDto(
        id = "job1", nodeId = "eu1", state = state, startedAt = NOW,
        steps = listOf(SetupStepDto("connect", "Connect as root", if (state == "running") "running" else "ok")),
    )
}

fun node(
    id: String,
    health: Health = Health.OK,
    update: NodeUpdateDto? = null,
    job: String? = null,
    waiting: String? = null,
) = NodeStatusDto(
    id = id, region = "AS", provider = "vps", host = "10.0.0.1", managementHost = "pangolin-$id.x", health = health,
    reachable = true, tunnelUp = health == Health.OK, lastProvisionAt = NOW - 120_000, lastProvisionOk = true,
    facts = mapOf("os" to "ubuntu-26.10", "tcp_cc" to "bbr", "qdisc" to "fq", "edge_tcp" to "unknown"),
    job = job, updateWaiting = waiting, update = update,
)

fun snapshot(
    nodes: List<NodeStatusDto> = listOf(node("a1")),
    tunnels: List<TunnelStatusDto> = listOf(TunnelStatusDto("a1", "homelab", "https://p", up = true, processRunning = true, siteOnline = true)),
    paused: Boolean = false,
) = StatusSnapshot(
    generatedAt = NOW, version = "0.1.0", dryRun = false, project = "homegap", domain = "example.net", paused = paused,
    lastReconcile = ReconcileSummaryDto(NOW - 30_000, NOW - 12_000, "periodic", ok = true, changes = 1, errors = 0),
    nodes = nodes, tunnels = tunnels,
)
