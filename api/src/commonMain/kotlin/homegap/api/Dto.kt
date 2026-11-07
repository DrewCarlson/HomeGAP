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
)

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
    val managementHost: String? = null,
)

@Serializable
data class ConfigResponse(
    val path: String,
    val writable: Boolean,
    val yaml: String,
    val nodes: List<NodeConfigDto>,
)
