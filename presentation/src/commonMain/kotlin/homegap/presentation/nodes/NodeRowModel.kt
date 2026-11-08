package homegap.presentation.nodes

import androidx.compose.runtime.Immutable
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.presentation.common.Badge
import homegap.presentation.common.ChecksModel
import homegap.presentation.format.ago

/** One node as shown in the nodes tables. */
@Immutable
data class NodeRowModel(
    val id: String,
    val host: String,
    /** "AS · vultr · ubuntu-26.10", or null when nothing is known. */
    val meta: String?,
    val tcp: String?,
    val edgeTcp: String?,
    val tags: List<String>,
    val health: Badge,
    val checks: ChecksModel,
    val certificate: CertificateModel?,
    val tunnel: Badge,
    val provisioning: ProvisioningModel,
    val drift: List<String>,
    val actions: NodeActionsModel,
)

@Immutable
data class CertificateModel(val badge: Badge, val subject: String)

/** Last provision time plus the most relevant state: running job, update outcome or result. */
@Immutable
data class ProvisioningModel(val lastAt: String, val badge: Badge?, val pending: String?, val errors: List<String>)

/** Which per-node actions apply. */
@Immutable
data class NodeActionsModel(
    val pendingDecommission: Boolean,
    val canRetryUpdate: Boolean,
    val canSkipUpdate: Boolean,
    val canReprovision: Boolean,
    val paused: Boolean,
)

fun NodeStatusDto.toRowModel(now: Long): NodeRowModel {
    val update = update
    val updateFailed = update?.status == "failed"
    return NodeRowModel(
        id = id,
        host = host,
        meta = listOf(region, provider, facts["os"]).filterNot { it.isNullOrBlank() }.joinToString(" · ").ifEmpty { null },
        tcp = facts["tcp_cc"]?.let { cc -> "tcp: $cc" + facts["qdisc"]?.let { " / $it" }.orEmpty() },
        edgeTcp = facts["edge_tcp"]?.takeIf { it != "unknown" }?.let { "edge tcp: $it" },
        tags = buildList {
            if (paused) add("paused")
            if (pendingDecommission) add("pending decommission")
        },
        health = Badge.of(health),
        checks = ChecksModel.from(checks),
        certificate = certificate?.let { cert ->
            CertificateModel(Badge(cert.health, cert.daysRemaining?.let { "$it days left" } ?: "unknown"), cert.subject)
        },
        tunnel = Badge.status(tunnelUp, "up", "down"),
        provisioning = ProvisioningModel(
            lastAt = lastProvisionAt?.let { ago(it, now) } ?: "never",
            badge = when {
                job != null -> Badge(Health.DEGRADED, "$job…")
                updateFailed -> Badge(Health.DOWN, if (update.rolledBack) "update rolled back" else "update failed")
                update?.status == "skipped" -> Badge(Health.DEGRADED, "update skipped")
                else -> lastProvisionOk?.let { Badge.status(it, "ok", "failed") }
            },
            pending = updateWaiting?.takeIf { job == null }?.let { "Pending: $it" },
            errors = listOfNotNull(update?.error?.takeIf { updateFailed }, lastProvisionError).map { it.take(300) },
        ),
        drift = drift,
        actions = NodeActionsModel(
            pendingDecommission = pendingDecommission,
            canRetryUpdate = update != null,
            canSkipUpdate = updateFailed,
            canReprovision = job == null,
            paused = paused,
        ),
    )
}
