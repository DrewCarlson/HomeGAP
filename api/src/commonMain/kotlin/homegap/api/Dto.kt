package homegap.api

import kotlinx.serialization.Serializable

/**
 * Wire types shared by the orchestrator REST API and the dashboard SPA.
 * Never carry secret values: secrets are only ever described by [SecretRefDto].
 */

@Serializable
enum class Health { UNKNOWN, OK, DEGRADED, DOWN }

@Serializable
data class StatusSnapshot(
    val generatedAt: Long,
    val version: String,
    val dryRun: Boolean,
    val project: String,
    val domain: String,
    val paused: Boolean,
    val lastReconcile: ReconcileSummaryDto? = null,
    val reconciling: Boolean = false,
    val nodes: List<NodeStatusDto> = emptyList(),
    val services: List<ServiceStatusDto> = emptyList(),
    val dns: List<DnsRecordStatusDto> = emptyList(),
    val tunnels: List<TunnelStatusDto> = emptyList(),
    val labelErrors: List<LabelErrorDto> = emptyList(),
    val pauses: PausesDto = PausesDto(),
    /** The cluster mesh; null when the cluster is not configured. */
    val mesh: MeshStatusDto? = null,
    /** The cluster's database layer; null when the cluster is not configured. */
    val cluster: ClusterStatusDto? = null,
    /** Where the homelab is (`homelab.location`); null when not set. */
    val homelab: PlaceDto? = null,
    /** The last time the homelab could reach none of the nodes; null if it hasn't happened. */
    val homelabIsolation: IsolationDto? = null,
    /** What needs attention, worst first. */
    val attention: List<AttentionItemDto> = emptyList(),
    /** The "test all nodes" run, while it runs and after it finished; null before the first. */
    val speedRun: SpeedRunDto? = null,
    /** Running homelab containers with `homegap.expose`, from the last discovery. */
    val homelabContainers: Int? = null,
    /** The certificate of the nodes and what visitors get; null before the first pass. */
    val certificates: CertificatesDto? = null,
)

/** Homelab ↔ node speed tests of every node, one at a time, run by the orchestrator. */
@Serializable
data class SpeedRunDto(
    val streams: Int,
    val nodes: List<String>,
    /** The node being tested now; null once the run is over. */
    val current: String? = null,
    val done: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val startedAt: Long,
    val finishedAt: Long? = null,
    val cancelled: Boolean = false,
)

/**
 * Something that needs attention: a node down, a failing service, a record out of sync. [route]
 * is the dashboard page that shows it (`nodes`) and [selection] the item there (`sg1`).
 */
@Serializable
data class AttentionItemDto(
    val id: String,
    /** [Health.DOWN] or [Health.DEGRADED]. */
    val severity: Health,
    val title: String,
    val detail: String = "",
    /** When it was first seen (epoch ms). */
    val since: Long,
    val route: String,
    val selection: String? = null,
    /** The id of the item that causes this one; the dashboard folds this item under it. */
    val parent: String? = null,
)

/** One measurement series of one edge, for graphs. */
@Serializable
data class MetricSeriesDto(
    val edge: String,
    /** `api` (Pangolin API probe), `mesh` (mesh round trip), `in` and `out` (tunnel throughput). */
    val series: String,
    /** `ms` or `Mb/s`. */
    val unit: String,
    /** Epoch seconds of each sample. */
    val times: List<Long> = emptyList(),
    /** Null where a measurement failed. */
    val values: List<Double?> = emptyList(),
)

@Serializable
data class MetricsResponse(val series: List<MetricSeriesDto>)

/** One new sample, pushed on the event stream. */
@Serializable
data class MetricPointDto(
    val edge: String,
    val series: String,
    val value: Double?,
)

/** The homelab lost contact with every node from [startedAt] until [endedAt] (null while it lasts). */
@Serializable
data class IsolationDto(val startedAt: Long, val endedAt: Long? = null)

/** A point on the map, with the name shown for it (`Dallas, Texas`); [estimated] when only a region code placed it. */
@Serializable
data class PlaceDto(val latitude: Double, val longitude: Double, val name: String? = null, val estimated: Boolean = false)

/**
 * A known location (a data-center city): shipped with HomeGAP ([builtIn]) or added in the
 * dashboard. [edited] marks a shipped one changed locally.
 */
@Serializable
data class LocationDto(
    val id: String,
    /** `Dallas, Texas` */
    val name: String,
    val country: String,
    /** ISO 3166-1 alpha-2, e.g. `US`. */
    val countryCode: String = "",
    /** HomeGAP's region code (NA, SA, EU, AF, ME, AS, OC). */
    val region: String = "",
    val latitude: Double,
    val longitude: Double,
    val providers: List<String> = emptyList(),
    val builtIn: Boolean = false,
    val edited: Boolean = false,
)

@Serializable
data class LocationsResponse(val locations: List<LocationDto>, val homelab: PlaceDto? = null)

@Serializable
data class SetHomelabLocationRequest(val location: PlaceDto?)

/** Where a node is, for geo DNS steering and the maps. */
@Serializable
data class SetNodeLocationRequest(val location: PlaceDto)

@Serializable
data class ReconcileSummaryDto(
    val startedAt: Long,
    val finishedAt: Long,
    val trigger: String,
    val ok: Boolean,
    val changes: Int,
    val errors: Int,
)

/**
 * One check of a node. Measured checks also carry their [value] and the [threshold] above which
 * the check is degraded, in [unit] (`pangolin-api`: 412 ms against 300 ms).
 */
@Serializable
data class CheckDto(
    val name: String,
    val health: Health,
    val detail: String = "",
    val value: Double? = null,
    val threshold: Double? = null,
    val unit: String? = null,
)

@Serializable
data class NodeStatusDto(
    val id: String,
    val region: String,
    val provider: String,
    val host: String,
    val managementHost: String,
    val health: Health,
    val reachable: Boolean,
    val checks: List<CheckDto> = emptyList(),
    val certificate: CertificateDto? = null,
    val tunnelUp: Boolean = false,
    val lastProvisionAt: Long? = null,
    val lastProvisionOk: Boolean? = null,
    val lastProvisionError: String? = null,
    val drift: List<String> = emptyList(),
    val paused: Boolean = false,
    val pendingDecommission: Boolean = false,
    /** The teardown of the removed node started and did not finish. Its stack can still run. */
    val teardownError: String? = null,
    val facts: Map<String, String> = emptyMap(),
    /** Background work in progress: `provisioning` (new or repair) or `updating` (rolling update). */
    val job: String? = null,
    /** Why a pending stack update is waiting (rollout held, another node updating, retry backoff). */
    val updateWaiting: String? = null,
    val update: NodeUpdateDto? = null,
    /** Where the node is: its configured location, or its region's centre ([PlaceDto.estimated]). */
    val location: PlaceDto? = null,
    /** The stack it runs (a short hash of its rendered files), and the one an update brings. */
    val stack: String? = null,
    val targetStack: String? = null,
    /** What a running [job] is doing now: "writing the stack (4 of 7)". */
    val jobStep: String? = null,
    /** The last provisioning and update runs, newest first. */
    val runs: List<NodeRunDto> = emptyList(),
    /**
     * The node is new and its management name is not on the certificate yet. Its tunnel connects
     * once an order with that name completes, so it is not down.
     */
    val waitingForCertificate: Boolean = false,
)

/** One provisioning or update run of a node. */
@Serializable
data class NodeRunDto(
    /** `provision` or `update`. */
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val error: String? = null,
    val stack: String? = null,
    val rolledBack: Boolean = false,
)

/** A stack update that failed (and was rolled back) or that the user skipped on this node. */
@Serializable
data class NodeUpdateDto(
    /** `failed` (holds the rollout) or `skipped`. */
    val status: String,
    val error: String? = null,
    val at: Long,
    val rolledBack: Boolean = false,
)

@Serializable
data class CertificateDto(
    val subject: String,
    val notAfter: String? = null,
    val daysRemaining: Int? = null,
    val source: String = "",
    val health: Health = Health.UNKNOWN,
)

@Serializable
data class ServiceStatusDto(
    val key: String,
    val container: String,
    val hostname: String,
    val wildcard: Boolean,
    val protocol: String,
    val tls: String,
    val access: String,
    val auth: String,
    val regions: List<String>,
    val kind: String,
    val paused: Boolean,
    val perNode: List<ServiceNodeHealthDto> = emptyList(),
    /** Its name under `homegap.services.<name>`, or null for a container's top-level service. */
    val name: String? = null,
    /** Raw services: each published port, e.g. `7881/tcp`, `2222:22/tcp`, `3478/tcp+udp`. */
    val ports: List<String> = emptyList(),
    /** The `homegap.*` labels on the container that publish it. */
    val labels: Map<String, String> = emptyMap(),
)

/**
 * A service as one edge serves it. [ms] and [code] come from a request to the service through
 * that edge's proxy (null before the first one, or for services it can't request, such as raw TCP).
 */
@Serializable
data class ServiceNodeHealthDto(
    val nodeId: String,
    val health: Health,
    val detail: String = "",
    val ms: Double? = null,
    val code: Int? = null,
)

@Serializable
data class DnsRecordStatusDto(
    val name: String,
    val type: String,
    val value: String,
    val nodeId: String?,
    val geo: String? = null,
    val monitor: String,
    val monitorStatus: String,
    val inSync: Boolean,
    val diff: String? = null,
    /** When HomeGAP last created or changed this record (epoch ms); null when not since it started logging. */
    val changedAt: Long? = null,
)

@Serializable
data class TunnelStatusDto(
    val nodeId: String,
    val siteNiceId: String,
    val endpoint: String,
    val up: Boolean,
    val processRunning: Boolean,
    val siteOnline: Boolean?,
    val restarts: Int = 0,
    val lastExit: String? = null,
    val bytesIn: Long? = null,
    val bytesOut: Long? = null,
    val updatedAt: Long? = null,
    /** Why the connector stopped, newest first. */
    val exits: List<TunnelExitDto> = emptyList(),
    /** The Newt version the node's Pangolin reports for the site. */
    val newtVersion: String? = null,
)

@Serializable
data class TunnelExitDto(val at: Long, val reason: String)

@Serializable
data class LabelErrorDto(val container: String, val message: String)

@Serializable
data class PausesDto(
    val global: Boolean = false,
    val nodes: List<String> = emptyList(),
    val services: List<String> = emptyList(),
)

@Serializable
enum class LogLevel { INFO, CHANGE, WARN, ERROR }

@Serializable
data class LogEntryDto(
    val time: Long,
    val domain: String,
    val level: LogLevel,
    val message: String,
    val target: String? = null,
    /** Increasing sequence number (lets streaming clients resume after the last entry seen). */
    val seq: Long = 0,
    /** The reconcile pass it was written in; null outside a pass. */
    val pass: Long? = null,
    /** Structured details, such as a pass's `duration_ms`, `changes` and `trigger`. */
    val fields: Map<String, String> = emptyMap(),
)

@Serializable
data class LogResponse(val entries: List<LogEntryDto>)

@Serializable
data class DiffItemDto(
    val domain: String,
    val target: String,
    val key: String,
    val action: String,
    val desired: String? = null,
    val actual: String? = null,
)

@Serializable
data class DiffResponse(
    val computedAt: Long,
    val items: List<DiffItemDto>,
    val desiredState: String,
)

@Serializable
data class SecretRefDto(
    val name: String,
    val usedBy: String,
    val source: String,
    val present: Boolean,
)

@Serializable
data class SecretsResponse(val secrets: List<SecretRefDto>)

@Serializable
data class PauseRequest(val scope: String, val id: String? = null, val paused: Boolean)

/** The outcome of an action. [undo] is set when it can be undone for a while (`POST /api/undo/{undo}`). */
@Serializable
data class ActionResponse(
    val ok: Boolean,
    val message: String,
    val undo: String? = null,
)

@Serializable
data class NodeConfigDto(
    val id: String,
    val region: String = "",
    val provider: String = "",
    val host: String,
    val os: String = "",
    val sshUser: String = "root",
    val sshPort: Int = 22,
    val sshKeyFile: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Shown on maps, e.g. `Dallas, Texas`. */
    val locationName: String? = null,
    val managementHost: String? = null,
    /** Sources allowed to reach SSH through the node firewall; empty allows any source. */
    val sshAllowFrom: List<String> = emptyList(),
)

@Serializable
data class ConfigResponse(
    val path: String,
    val writable: Boolean,
    val yaml: String,
    val nodes: List<NodeConfigDto>,
)

/** One speed test measurement. Rates in megabits per second, times in milliseconds. */
@Serializable
data class SpeedTestResultDto(
    val id: String,
    val at: Long,
    /** `tunnel` (node -> homelab through the Newt tunnel) or `device` (browser -> node -> homelab). */
    val kind: String,
    val nodeId: String? = null,
    /** Device test entered through the auto (geo DNS) hostname. */
    val auto: Boolean = false,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val pingSamples: Int = 0,
    val error: String? = null,
    val client: String? = null,
    /** Parallel TCP streams (tunnel tests); rates are the sum over all streams. */
    val streams: Int? = null,
    /** Per-stream rates, in stream order. */
    val downStreamsMbps: List<Double> = emptyList(),
    val upStreamsMbps: List<Double> = emptyList(),
    /** The CPU of the node in the download and the upload phase (tunnel tests). */
    val downCpu: NodeCpuDto? = null,
    val upCpu: NodeCpuDto? = null,
)

/**
 * How busy the CPU of a node was in one phase of a test, in percent of all its cores. [kernelPct]
 * is the part in the kernel, which holds the network stack and WireGuard. [stealPct] is the time
 * that the host of the server gave to other machines.
 */
@Serializable
data class NodeCpuDto(val cores: Int, val busyPct: Double, val kernelPct: Double, val stealPct: Double)

@Serializable
data class SpeedTestOverview(
    val enabled: Boolean,
    val autoUrl: String? = null,
    val nodes: List<String> = emptyList(),
    val results: List<SpeedTestResultDto> = emptyList(),
    /** Default stream count for homelab <-> node tests. */
    val streams: Int = 4,
)

/**
 * A homelab ↔ node test while it runs, as the speed test origin sees it: the pings arriving one
 * after another (each gap is about one round trip) and the bytes it sends and receives.
 */
@Serializable
data class SpeedTestLiveDto(
    val nodeId: String,
    /** ping, download, upload */
    val phase: String,
    /** Each ping's round trip so far (ms). */
    val pingSamples: List<Double> = emptyList(),
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    /** The current phase's rate (Mbps), over the last second. */
    val mbps: Double? = null,
    /** The current phase's rate, four samples a second. */
    val mbpsSamples: List<Double> = emptyList(),
    val downMbps: Double? = null,
)

@Serializable
data class SpeedTestLinkDto(
    /** Plain-HTTP entry link: the node DNS picks redirects to its own hostname. */
    val autoUrl: String,
    val nodeUrls: Map<String, String>,
    val expiresAt: Long,
)

/** Posted by the device test page. */
@Serializable
data class DeviceResultRequest(
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val pingSamples: Int = 0,
    val auto: Boolean = false,
    val error: String? = null,
)

/** The cluster's WireGuard mesh, as each member sees it. */
@Serializable
data class MeshStatusDto(
    val subnet: String,
    val members: List<MeshMemberDto> = emptyList(),
)

@Serializable
data class MeshMemberDto(
    /** `homelab` or a node id. */
    val id: String,
    val address: String,
    /** Why this member's mesh could not be configured or read. */
    val error: String? = null,
    val peers: List<MeshPeerDto> = emptyList(),
)

@Serializable
data class MeshPeerDto(
    /** The peer's member id (`homelab` or a node id), or `unknown` for a key HomeGAP does not know. */
    val id: String,
    /** Where this member last heard the peer from (null before the first handshake). */
    val endpoint: String? = null,
    /** Epoch milliseconds of the latest WireGuard handshake; null if there has been none. */
    val lastHandshakeAt: Long? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    /** The member's continuous probe of this peer's mesh address from the host; null without one. */
    val probe: MeshProbeDto? = null,
    /**
     * The same probe from inside the member's Docker network (HAProxy's checks), the path Pangolin's
     * database traffic takes; null without one.
     */
    val containerProbe: MeshProbeDto? = null,
)

/**
 * One member's view of a peer from its mesh probe (a ping every second over the mesh). Loss and
 * outages cover [windowMs]; an outage is three or more probes in a row without a reply.
 */
@Serializable
data class MeshProbeDto(
    /** The latest reply's round trip, in milliseconds; null when the last probe was lost. */
    val rttMs: Double? = null,
    val lossPercent: Double = 0.0,
    val sent: Long = 0,
    val windowMs: Long = 0,
    /** Outages in the window, newest first. */
    val outages: List<MeshOutageDto> = emptyList(),
    /** When the probe last reported (epoch ms). */
    val at: Long = 0,
)

@Serializable
data class MeshOutageDto(val startAt: Long, val endAt: Long? = null)

/** A text report from a node: its logs or the diagnostics bundle. */
@Serializable
data class NodeTextDto(val node: String, val title: String, val text: String, val at: Long)

/** The containers whose logs the dashboard can show, by the id the logs API takes. */
object NodeLogServices {
    /** Id to label, in display order. */
    val ALL: List<Pair<String, String>> = listOf(
        "pangolin" to "Pangolin",
        "gerbil" to "Gerbil",
        "traefik" to "Traefik",
        "haproxy" to "Cluster proxy (HAProxy)",
        "db" to "Database (Patroni)",
        "valkey" to "Valkey",
        "sentinel" to "Sentinel",
        "etcd" to "etcd",
        "probe" to "Mesh probe",
        "newt" to "Exit connector (Newt)",
    )
}

/** The cluster's database layer: where the primaries are and each member's part. */
@Serializable
data class ClusterStatusDto(
    /** The member holding the PostgreSQL primary (null while there is none). */
    val primary: String? = null,
    /** The member holding the Valkey primary. */
    val valkeyPrimary: String? = null,
    /** Where the primary belongs (`homelab`); null when the homelab only votes and holds no database. */
    val preferred: String? = "homelab",
    /** What the switch-back is waiting for, when the primary is away from [preferred]. */
    val switchBack: String? = null,
    /** When the switch-back is due (epoch ms), once [preferred] is caught up and the hold runs. */
    val switchBackAt: Long? = null,
    /** A majority of the vote (etcd) is healthy, so a primary can be elected. Null when unknown. */
    val quorum: Boolean? = null,
    val members: List<ClusterMemberDto> = emptyList(),
    /** Changes of the PostgreSQL primary, newest first. */
    val history: List<PrimaryChangeDto> = emptyList(),
    /** Problems worth acting on, most severe first. */
    val alerts: List<ClusterAlertDto> = emptyList(),
)

@Serializable
data class PrimaryChangeDto(
    val at: Long,
    /** The previous primary (null for the first one seen). */
    val from: String? = null,
    val to: String,
    /** `initial`, `failover` (elected by the cluster), `switch-back` (HomeGAP, after the hold) or `manual`. */
    val cause: String,
)

@Serializable
data class ClusterAlertDto(val health: Health, val message: String)

@Serializable
data class SwitchoverRequest(
    /** The member to make primary (a caught-up replica). */
    val to: String,
)

@Serializable
data class ClusterMemberDto(
    val id: String,
    val address: String,
    /** etcd: `voter`, `joining` (learner), `not joined` or `unknown`. */
    val vote: String,
    /** Patroni role: `leader`, `replica`, … (null when the member's database is not running). */
    val role: String? = null,
    /** Patroni state: `running`, `streaming`, … */
    val state: String? = null,
    /** Replication lag in bytes (replicas). */
    val lagBytes: Long? = null,
    val timeline: Int? = null,
    /** Valkey: `master` or `slave` (replica); null when it does not answer. */
    val valkeyRole: String? = null,
    /** The member's etcd answers its health check (it is part of a working majority). */
    val etcdHealthy: Boolean? = null,
    /** The member only votes (the homelab with `cluster.homelab_role: vote`): no database, no Valkey. */
    val voteOnly: Boolean = false,
    /**
     * How far behind the primary this replica's replay is, in milliseconds: the time since it
     * replayed the last transaction. Only set while [lagBytes] is above zero, since an idle primary
     * writes nothing to replay.
     */
    val lagMs: Long? = null,
)

/**
 * The cluster's users and devices (one Pangolin across every node). [available] is false without
 * a cluster or while no node's Pangolin can be reached; [note] says why.
 */
@Serializable
data class AccessOverviewDto(
    val available: Boolean,
    val note: String? = null,
    /** What to put into the Pangolin apps: `https://<client_hostname>.<domain>`. */
    val clientAddress: String? = null,
    /** The node whose Pangolin answered. */
    val via: String? = null,
    val roles: List<AccessRoleDto> = emptyList(),
    val users: List<AccessUserDto> = emptyList(),
    val invitations: List<AccessInvitationDto> = emptyList(),
    val devices: List<AccessDeviceDto> = emptyList(),
)

@Serializable
data class AccessRoleDto(val id: Int, val name: String, val admin: Boolean = false)

@Serializable
data class AccessUserDto(
    val id: String,
    val email: String? = null,
    val username: String,
    val name: String? = null,
    val roleIds: List<Int> = emptyList(),
    val roles: List<String> = emptyList(),
    /** The server admin HomeGAP created: never removed from the dashboard. */
    val owner: Boolean = false,
    val twoFactor: Boolean = false,
    /** `internal` (password) or an identity provider. */
    val type: String? = null,
)

@Serializable
data class AccessInvitationDto(val id: String, val email: String, val roles: List<String> = emptyList(), val expiresAt: Long? = null)

@Serializable
data class AccessDeviceDto(
    val id: Int,
    val name: String,
    /** `user` (a person's app) or `machine` (a client with its own credentials). */
    val kind: String,
    /** The owning user's email (user devices). */
    val user: String? = null,
    /** App and version, e.g. `Olm CLI 1.10.1`. */
    val agent: String? = null,
    val online: Boolean = false,
    val archived: Boolean = false,
    val blocked: Boolean = false,
    /** The node the device relays through (online devices). */
    val viaNode: String? = null,
    /** When the device last checked in (epoch ms), as Pangolin saw it. */
    val lastSeen: Long? = null,
)

/**
 * A new user. With [password] HomeGAP completes the invite and the user can sign in at once;
 * without it, the response's invite link is handed to the person, who picks their own.
 */
@Serializable
data class CreateAccessUserRequest(val email: String, val roleIds: List<Int>, val password: String? = null)

@Serializable
data class CreateAccessUserResponse(val ok: Boolean, val message: String, val inviteLink: String? = null)

@Serializable
data class SetAccessRolesRequest(val roleIds: List<Int>)

/** A search result the server finds: config keys, secrets and older log entries. */
@Serializable
data class SearchHitDto(
    /** `setting` or `log`. */
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val route: String,
    val selection: String? = null,
)

@Serializable
data class SearchResponse(val hits: List<SearchHitDto>)

/**
 * The certificate of the nodes, its orders, its delivery and what a visitor gets on each name.
 * It never holds a key, a certificate file, the ACME account key or a challenge value.
 */
@Serializable
data class CertificatesDto(
    /** The names that the config and the service labels put on the certificate, with the reason. */
    val names: List<CertNameDto> = emptyList(),
    /** The certificate that the homelab holds now; null when there is none. */
    val issued: IssuedCertificateDto? = null,
    /** Names in the config that the certificate lacks, and names on it that left the config. */
    val pendingAdd: List<String> = emptyList(),
    val pendingRemove: List<String> = emptyList(),
    val directory: String,
    /** The directory is the Let's Encrypt staging one: browsers do not trust the certificate. */
    val staging: Boolean = false,
    val contact: String = "",
    val order: CertOrderDto = CertOrderDto(),
    /** Past orders and revocations, newest first, at most 20. */
    val history: List<CertOrderRecordDto> = emptyList(),
    val delivery: List<CertDeliveryDto> = emptyList(),
    /** One row for each public name, a cell for each node. */
    val probes: List<CertProbeRowDto> = emptyList(),
    val caa: CaaStatusDto = CaaStatusDto(),
    /** At most this many names fit on one certificate. */
    val maxNames: Int = 100,
)

/**
 * A name on the certificate. [reason] is `management`, `client`, `status`, `speedtest`, `service`
 * or `frontdoor`; [subject] is the node id or the service key it belongs to.
 */
@Serializable
data class CertNameDto(
    val name: String,
    val reason: String,
    val subject: String? = null,
)

/** The public facts of the certificate. Times are epoch seconds. */
@Serializable
data class IssuedCertificateDto(
    val names: List<String>,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    /** When HomeGAP orders the next one: one third of the lifetime before [notAfter]. */
    val renewAt: Long,
    val keyType: String,
    val serial: String,
    /** The SHA-256 fingerprint of the certificate, never of its key. */
    val fingerprint: String,
    /** A short id of the certificate, to compare the homelab with a node. */
    val version: String,
)

/**
 * The order of the certificate. [state] is `idle`, `running` or `failed`. While it runs, [step] is
 * `records`, `dns`, `validated` or `issued`. [due] says why an order is due, when one is.
 */
@Serializable
data class CertOrderDto(
    val state: String = "idle",
    val step: String? = null,
    val startedAt: Long? = null,
    val reason: String? = null,
    val names: Int = 0,
    val cause: String? = null,
    val causeText: String? = null,
    val failedNames: List<String> = emptyList(),
    val failedAt: Long? = null,
    val retryAt: Long? = null,
    /** Failed orders in the last hour. Let's Encrypt allows 5 for each name. */
    val failuresLastHour: Int = 0,
    val due: String? = null,
)

/** A past order (`order`) or revocation (`revoke`). Times are epoch ms. */
@Serializable
data class CertOrderRecordDto(
    val id: String,
    val kind: String,
    val at: Long,
    val reason: String,
    val names: List<String> = emptyList(),
    val ok: Boolean,
    val durationMs: Long? = null,
    val cause: String? = null,
    val causeText: String? = null,
    /** The version of the certificate the order made. */
    val version: String? = null,
)

/**
 * The certificate on one node. [state] is `ok`, `behind`, `none`, `default`, `waiting` (a new
 * node whose name is not on the certificate yet) or `unknown` (the node did not answer).
 */
@Serializable
data class CertDeliveryDto(
    val node: String,
    val state: String,
    val installed: String? = null,
    /** The issue time (epoch seconds) of the certificate the node has, when HomeGAP knows it. */
    val installedIssuedAt: Long? = null,
    val installedAt: Long? = null,
    /** Names of the current certificate that the node's certificate lacks. */
    val missing: List<String> = emptyList(),
    /** What the node serves on its own management name. */
    val management: CertProbeDto? = null,
)

/**
 * A public name and the result of a TLS probe through each node. [kind] is `node` (the nodes end
 * TLS) or `homelab` (passthrough). [group] is `management`, `shared`, `service` or `homelab`.
 */
@Serializable
data class CertProbeRowDto(
    val name: String,
    val kind: String,
    val group: String,
    val cells: List<CertProbeDto> = emptyList(),
)

/**
 * A TLS probe of a name through a node. [state] is `ok`, `soon` (fewer than 14 days), `expiring`
 * (fewer than 7), `none` (no certificate served), `default` (the self-signed default), `mismatch`
 * (does not cover the name) or `unknown` (no answer yet). [stale] marks a value from an earlier
 * probe, kept while the node does not answer. Times are epoch seconds.
 */
@Serializable
data class CertProbeDto(
    val node: String,
    val state: String,
    val daysRemaining: Int? = null,
    val subject: String? = null,
    val issuer: String? = null,
    val notAfter: Long? = null,
    val probedAt: Long? = null,
    val stale: Boolean = false,
)

/** The CAA records of the domain: what HomeGAP publishes, and what DNS has now. */
@Serializable
data class CaaStatusDto(
    val enabled: Boolean = true,
    val desired: List<String> = emptyList(),
    val live: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    /** CAA records that HomeGAP did not write. */
    val foreign: List<String> = emptyList(),
    /** False until a DNS pass has read the zone. */
    val known: Boolean = false,
)
