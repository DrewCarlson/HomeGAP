package homegap.presentation.nodes

import homegap.api.CheckDto
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.presentation.data.Samples
import homegap.presentation.format.ago
import homegap.presentation.format.label
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow
import homegap.presentation.ui.Severity
import homegap.presentation.ui.chartOf

/**
 * [node] in the Inspector: its graphs (Pangolin API probe, mesh round trip, tunnel throughput),
 * its checks, what it runs on, and its actions.
 */
fun nodeInspector(
    node: NodeStatusDto,
    samples: Map<String, Samples>?,
    now: Long,
    actions: List<ActionModel>,
): InspectorModel {
    val nowSeconds = now / 1000
    val api = node.checks.firstOrNull { it.name == "pangolin-api" }
    val charts = buildList {
        add(
            chartOf(
                label = "Pangolin API probe",
                samples = samples?.get("api"),
                nowSeconds = nowSeconds,
                threshold = api?.threshold,
            )
        )
        samples?.get("rps")?.let {
            add(
                chartOf(
                    label = "Requests served",
                    samples = it,
                    nowSeconds = nowSeconds,
                )
            )
        }
        samples?.get("egress")?.let {
            add(
                chartOf(
                    label = "Egress to visitors",
                    samples = it,
                    nowSeconds = nowSeconds,
                )
            )
        }
        samples?.get("mesh")?.let {
            add(
                chartOf(
                    label = "Mesh round trip to the homelab",
                    samples = it,
                    nowSeconds = nowSeconds,
                )
            )
        }
        samples?.get("in")?.let {
            add(
                chartOf(
                    label = "Tunnel in, homelab to edge",
                    samples = it,
                    nowSeconds = nowSeconds,
                )
            )
        }
        samples?.get("out")?.let {
            add(
                chartOf(
                    label = "Tunnel out, edge to homelab",
                    samples = it,
                    nowSeconds = nowSeconds,
                )
            )
        }
    }
    val update = node.update
    return InspectorModel(
        kind = "Node",
        title = node.id,
        status = listOfNotNull(
            node.health.label,
            node.location?.name,
            node.job?.let { job -> node.jobStep ?: "$job…" },
            "paused".takeIf { node.paused },
        ).joinToString(" · "),
        health = node.health,
        sections = buildList {
            node.lastProvisionError?.takeIf { node.lastProvisionOk == false }?.let {
                add(InspectorSection.Note(text = "Provisioning failed: ${it.take(300)}", severity = Severity.Down))
            }
            if (update?.status == "failed") {
                val outcome = if (update.rolledBack) "It was rolled back and runs its previous stack." else "The rollback failed."
                add(InspectorSection.Note(text = "The stack update failed. $outcome", severity = Severity.Down))
                update.error?.let { add(InspectorSection.Code(title = "Update error", text = it.take(800))) }
            }
            node.updateWaiting?.takeIf { node.job == null }?.let {
                add(InspectorSection.Note(text = "Update waiting: $it", severity = Severity.Info))
            }
            add(InspectorSection.Charts(charts))
            add(
                InspectorSection.Marks(
                    title = "Checks",
                    rows = node.checks.map { it.toMark() },
                )
            )
            if (node.drift.isNotEmpty()) {
                add(
                    InspectorSection.Marks(
                        title = "Drift from the config",
                        rows = node.drift.map { MarkRow(health = Health.DEGRADED, name = it) },
                    )
                )
            }
            add(
                InspectorSection.Facts(
                    title = "System",
                    rows = listOfNotNull(
                        FactRow("Address", node.host, mono = true),
                        FactRow("Management", node.managementHost, mono = true),
                        FactRow("Provider", listOf(node.provider, node.region).filter { it.isNotBlank() }.joinToString(" · ")),
                        node.facts["os"]?.let { FactRow("OS", it) },
                        node.facts["vcpu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { FactRow("CPU", "$it vCPU") },
                        node.facts["mem_kb"]?.toLongOrNull()?.let { FactRow("Memory", "${(it + 512 * 1024) / 1024 / 1024} GB") },
                        node.facts["disk_free_gb"]?.toLongOrNull()?.let { FactRow("Disk free", "$it GB, when provisioned") },
                        node.facts["tcp_cc"]?.let { cc -> FactRow("TCP", cc + node.facts["qdisc"]?.let { " / $it" }.orEmpty()) },
                        node.certificate?.let { cert ->
                            FactRow("Certificate", cert.daysRemaining?.let { "$it days left" } ?: cert.subject)
                        },
                        FactRow("Provisioned", node.lastProvisionAt?.let { ago(it, now) } ?: "never"),
                        node.stack?.let { stack -> FactRow("Stack", stack + node.targetStack?.let { " → $it pending" }.orEmpty(), mono = true) },
                    ),
                )
            )
            if (node.runs.isNotEmpty()) {
                add(
                    InspectorSection.Marks(
                        title = "Runs",
                        rows = node.runs.map { run ->
                            MarkRow(
                                health = when {
                                    run.ok -> Health.OK
                                    run.rolledBack -> Health.DEGRADED
                                    else -> Health.DOWN
                                },
                                name = "${run.kind} · ${ago(run.finishedAt, now)}",
                                detail = listOfNotNull(
                                    run.stack,
                                    "rolled back".takeIf { run.rolledBack },
                                    run.error,
                                ).joinToString(" · "),
                                value = "${((run.finishedAt - run.startedAt) / 1000).coerceAtLeast(1)} s",
                            )
                        },
                    )
                )
            }
        },
        actions = actions,
    )
}

/** A check as a row: its dashboard name, its detail, and its measured value against the threshold. */
fun CheckDto.toMark(): MarkRow = MarkRow(
    health = health,
    name = checkTitle(name),
    detail = detail,
    value = value?.let { measured ->
        val limit = threshold?.let { " / ${it.toLong()}" }.orEmpty()
        "${measured.toLong()}$limit ${unit.orEmpty()}".trim()
    }.orEmpty(),
)

/** The dashboard's name for a check. */
fun checkTitle(name: String): String = when (name) {
    "pangolin-api" -> "Pangolin API probe"
    "ssh" -> "SSH"
    "docker" -> "Docker"
    "tunnel" -> "Tunnel"
    "gerbil-udp" -> "Gerbil UDP"
    "tls-mgmt" -> "Management certificate"
    "tls-root" -> "Root name TLS"
    "cluster-proxy" -> "Cluster proxy"
    "ee-license" -> "EE license"
    else -> name.replaceFirstChar { it.uppercase() }
}
