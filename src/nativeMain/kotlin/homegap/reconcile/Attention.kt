package homegap.reconcile

import homegap.api.AttentionItemDto
import homegap.api.Health
import homegap.api.StatusSnapshot

/**
 * What needs attention in [snapshot], worst first, then oldest first. [since] holds when each item
 * id was first seen; an item not in it yet is new as of the snapshot.
 */
internal fun attentionItems(snapshot: StatusSnapshot, since: Map<String, Long>): List<AttentionItemDto> {
    val items = mutableListOf<AttentionItemDto>()
    fun add(
        id: String,
        severity: Health,
        title: String,
        detail: String,
        route: String,
        selection: String?,
    ) {
        items += AttentionItemDto(
            id = id,
            severity = severity,
            title = title,
            detail = detail,
            since = since[id] ?: snapshot.generatedAt,
            route = route,
            selection = selection,
        )
    }

    snapshot.homelabIsolation?.takeIf { it.endedAt == null }?.let {
        add(
            id = "homelab:isolated",
            severity = Health.DOWN,
            title = "The homelab can't reach any node",
            detail = "check the homelab's internet connection",
            route = "nodes",
            selection = null,
        )
    }
    for (node in snapshot.nodes) {
        val failing = node.checks.filter { it.health == Health.DOWN || it.health == Health.DEGRADED }
        val firstProblem = failing.firstOrNull()?.let { check ->
            val measured = check.value?.let { value -> "${value.toLong()} ${check.unit.orEmpty()}".trim() }
            listOfNotNull(checkName(check.name), measured ?: check.detail.ifEmpty { null }).joinToString(" ")
        }
        when {
            node.lastProvisionOk == false -> add(
                id = "node:${node.id}:provision",
                severity = Health.DOWN,
                title = "${node.id}: provisioning failed",
                detail = node.lastProvisionError.orEmpty().take(160),
                route = "nodes",
                selection = node.id,
            )

            node.health == Health.DOWN -> add(
                id = "node:${node.id}:down",
                severity = Health.DOWN,
                title = "${node.id} is down",
                detail = firstProblem.orEmpty(),
                route = "nodes",
                selection = node.id,
            )

            node.health == Health.DEGRADED -> add(
                id = "node:${node.id}:degraded",
                severity = Health.DEGRADED,
                title = "${node.id} is degraded",
                detail = firstProblem.orEmpty(),
                route = "nodes",
                selection = node.id,
            )
        }
        node.update?.takeIf { it.status == "failed" }?.let { update ->
            add(
                id = "node:${node.id}:update",
                severity = Health.DOWN,
                title = "${node.id}: stack update failed",
                detail = if (update.rolledBack) "rolled back; the rollout is held" else "the rollback failed",
                route = "nodes",
                selection = node.id,
            )
        }
        node.certificate?.daysRemaining?.takeIf { it < 14 }?.let { days ->
            add(
                id = "node:${node.id}:certificate",
                severity = if (days < 0) Health.DOWN else Health.DEGRADED,
                title = if (days < 0) "${node.id}: certificate expired" else "${node.id}: certificate expires in $days days",
                detail = node.certificate?.subject.orEmpty(),
                route = "nodes",
                selection = node.id,
            )
        }
        if (node.drift.isNotEmpty()) {
            add(
                id = "node:${node.id}:drift",
                severity = Health.DEGRADED,
                title = "${node.id} has drifted from its config",
                detail = node.drift.joinToString("; ").take(160),
                route = "nodes",
                selection = node.id,
            )
        }
    }
    for (service in snapshot.services) {
        for (edge in service.perNode) {
            if (edge.health != Health.DOWN && edge.health != Health.DEGRADED) continue
            val failing = edge.health == Health.DOWN
            add(
                id = "service:${service.key}:${edge.nodeId}",
                severity = edge.health,
                title = "${service.hostname} ${if (failing) "failing" else "slow"} on ${edge.nodeId}",
                detail = listOfNotNull(edge.code?.toString(), edge.ms?.let { "${it.toLong()} ms" }, edge.detail.ifEmpty { null })
                    .joinToString(" · "),
                route = "services",
                selection = service.hostname,
            )
        }
    }
    for (error in snapshot.labelErrors) {
        add(
            id = "label:${error.container}:${error.message.hashCode()}",
            severity = Health.DEGRADED,
            title = "Label error on ${error.container}",
            detail = error.message,
            route = "services",
            selection = null,
        )
    }
    for (record in snapshot.dns) {
        when {
            record.monitorStatus == "offline" -> add(
                id = "dns:${record.name}:${record.type}:${record.nodeId}:offline",
                severity = Health.DEGRADED,
                title = "${record.name}: ${record.nodeId ?: "a member"} withdrawn",
                detail = "its health monitor is offline",
                route = "dns",
                selection = record.name,
            )

            !record.inSync -> add(
                id = "dns:${record.name}:${record.type}:${record.nodeId}:sync",
                severity = Health.DEGRADED,
                title = "${record.name} is not in sync",
                detail = record.diff.orEmpty().take(160),
                route = "dns",
                selection = record.name,
            )
        }
    }
    for (tunnel in snapshot.tunnels) {
        if (tunnel.up) continue
        add(
            id = "tunnel:${tunnel.nodeId}",
            severity = Health.DOWN,
            title = "Tunnel to ${tunnel.nodeId} is down",
            detail = tunnel.lastExit.orEmpty(),
            route = "tunnels",
            selection = tunnel.nodeId,
        )
    }
    snapshot.cluster?.alerts?.forEachIndexed { index, alert ->
        if (alert.health != Health.DOWN && alert.health != Health.DEGRADED) return@forEachIndexed
        add(
            id = "cluster:$index:${alert.message.hashCode()}",
            severity = alert.health,
            title = alert.message,
            detail = "",
            route = "nodes",
            selection = null,
        )
    }
    return items.sortedWith(compareBy<AttentionItemDto> { if (it.severity == Health.DOWN) 0 else 1 }.thenBy { it.since })
}

/** [previous] first-seen times, kept for items still present, with [items]' new ones added. */
internal fun attentionSince(items: List<AttentionItemDto>, previous: Map<String, Long>): Map<String, Long> =
    items.associate { it.id to (previous[it.id] ?: it.since) }

/** The dashboard's name for a check. */
internal fun checkName(name: String): String = when (name) {
    "pangolin-api" -> "Pangolin API probe"
    "ssh" -> "SSH"
    "tunnel" -> "Tunnel"
    "gerbil-udp" -> "Gerbil UDP"
    "tls-mgmt" -> "Management certificate"
    "tls-root" -> "Root certificate"
    "cluster-proxy" -> "Cluster proxy"
    "ee-license" -> "EE license"
    else -> name
}
