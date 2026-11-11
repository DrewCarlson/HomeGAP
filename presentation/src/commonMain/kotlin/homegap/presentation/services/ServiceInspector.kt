package homegap.presentation.services

import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.api.ServiceNodeHealthDto
import homegap.api.ServiceStatusDto
import homegap.presentation.format.plural
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.MarkRow

/** [service] in the Inspector: how each edge serves it, how it is published, and its actions. */
fun serviceInspector(
    service: ServiceStatusDto,
    nodes: List<NodeStatusDto>,
    actions: List<ActionModel>,
): InspectorModel {
    val failing = service.perNode.count { it.health == Health.DOWN }
    val slow = service.perNode.count { it.health == Health.DEGRADED }
    val health = when {
        failing > 0 -> Health.DOWN
        slow > 0 -> Health.DEGRADED
        service.perNode.all { it.health == Health.OK } && service.perNode.isNotEmpty() -> Health.OK
        else -> Health.UNKNOWN
    }
    return InspectorModel(
        kind = "Service",
        title = service.hostname,
        status = when {
            service.paused -> "paused"
            failing > 0 -> "failing on ${plural(failing, "edge")}"
            slow > 0 -> "slow on ${plural(slow, "edge")}"
            else -> "healthy on ${plural(service.perNode.size, "edge")}"
        },
        health = health,
        sections = buildList {
            add(
                InspectorSection.Marks(
                    title = "Through each edge",
                    rows = service.perNode.map { edge ->
                        MarkRow(
                            health = edge.health,
                            name = edge.nodeId + nodes.firstOrNull { it.id == edge.nodeId }?.location?.name?.let { " · $it" }.orEmpty(),
                            detail = edge.describe(),
                            value = edge.ms?.let { "${it.toLong()} ms" } ?: edge.code?.takeIf { it >= 400 }?.toString().orEmpty(),
                        )
                    },
                )
            )
            add(
                InspectorSection.Facts(
                    title = "Published as",
                    rows = listOfNotNull(
                        FactRow("Kind", service.kind),
                        FactRow("Protocol", if (service.ports.isNotEmpty()) service.ports.joinToString(", ") else "${service.protocol} · ${service.tls}"),
                        FactRow("Access", service.access + if (service.auth != "none") " · ${service.auth}" else ""),
                        FactRow("Regions", service.regions.joinToString(", ")),
                        FactRow("Container", service.container + service.name?.let { " · $it" }.orEmpty(), mono = true),
                        if (service.wildcard) FactRow("Wildcard", "*.${service.hostname}", mono = true) else null,
                    ),
                )
            )
            if (service.labels.isNotEmpty()) {
                add(
                    InspectorSection.Code(
                        title = "Source labels",
                        text = service.labels.entries.joinToString("\n") { (key, value) -> "$key=$value" },
                    )
                )
            }
        },
        actions = actions,
    )
}

/** What a request through this edge found: its status code, or the reason it isn't served. */
fun ServiceNodeHealthDto.describe(): String {
    val code = code
    return when {
        code == 0 -> "no answer"
        code != null && code >= 500 -> "HTTP $code"
        code != null -> "HTTP $code" + if (detail.isNotEmpty() && detail != "online") " · $detail" else ""
        else -> detail
    }
}
