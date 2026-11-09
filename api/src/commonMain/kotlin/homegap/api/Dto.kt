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

@Serializable
data class ReconcileSummaryDto(
    val startedAt: Long,
    val finishedAt: Long,
    val trigger: String,
    val ok: Boolean,
    val changes: Int,
    val errors: Int,
)

@Serializable
data class CheckDto(val name: String, val health: Health, val detail: String = "")

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
    val facts: Map<String, String> = emptyMap(),
    /** Background work in progress: `provisioning` (new or repair) or `updating` (rolling update). */
    val job: String? = null,
    /** Why a pending stack update is waiting (rollout held, another node updating, retry backoff). */
    val updateWaiting: String? = null,
    val update: NodeUpdateDto? = null,
    /** Where the node is: its configured location, or its region's centre ([PlaceDto.estimated]). */
    val location: PlaceDto? = null,
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
)

@Serializable
data class ServiceNodeHealthDto(val nodeId: String, val health: Health, val detail: String = "")

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
)

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

@Serializable
data class ActionResponse(val ok: Boolean, val message: String)

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
)

@Serializable
data class SpeedTestOverview(
    val enabled: Boolean,
    val autoUrl: String? = null,
    val nodes: List<String> = emptyList(),
    val results: List<SpeedTestResultDto> = emptyList(),
    /** Default stream count for homelab <-> node tests. */
    val streams: Int = 4,
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
    /** Where the primary belongs (`homelab`). */
    val preferred: String = "homelab",
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
