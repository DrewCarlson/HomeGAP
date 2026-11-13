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
        parent: String? = null,
    ) {
        items += AttentionItemDto(
            id = id,
            severity = severity,
            title = title,
            detail = detail,
            since = since[id] ?: snapshot.generatedAt,
            route = route,
            selection = selection,
            parent = parent,
        )
    }

    // Certificate faults first: node and tunnel items that follow from one fold under it.
    val certCause = certificateItems(snapshot, ::add)

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
        val deliveryState = snapshot.certificates?.delivery?.firstOrNull { it.node == node.id }?.state
        when {
            node.waitingForCertificate && node.lastProvisionOk != false -> add(
                id = "node:${node.id}:waiting",
                severity = Health.DEGRADED,
                title = "${node.id} waits for its certificate",
                detail = "its tunnel connects after the order with ${node.managementHost}",
                route = "certificates",
                selection = "node:${node.id}",
                parent = certCause,
            )

            deliveryState == "default" && node.lastProvisionOk != false -> add(
                id = "node:${node.id}:default-certificate",
                severity = Health.DOWN,
                title = "${node.id} answers with the default certificate",
                detail = firstProblem.orEmpty(),
                route = "certificates",
                selection = "node:${node.id}",
                parent = certCause,
            )

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
        node.certificate?.daysRemaining?.takeIf { it < 14 && snapshot.certificates == null }?.let { days ->
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
        val waiting = snapshot.nodes.any { it.id == tunnel.nodeId && it.waitingForCertificate }
        add(
            id = "tunnel:${tunnel.nodeId}",
            severity = if (waiting) Health.DEGRADED else Health.DOWN,
            title = "Tunnel to ${tunnel.nodeId} is down",
            detail = if (waiting) "${tunnel.nodeId} serves the default certificate until its order completes" else tunnel.lastExit.orEmpty(),
            route = "tunnels",
            selection = tunnel.nodeId,
            parent = if (waiting) certCause ?: "node:${tunnel.nodeId}:waiting" else null,
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

/**
 * Adds the items of the certificate of the nodes and returns the id of the one that causes node
 * faults (a failed order, or no certificate), if any.
 */
private fun certificateItems(
    snapshot: StatusSnapshot,
    add: (id: String, severity: Health, title: String, detail: String, route: String, selection: String?, parent: String?) -> Unit,
): String? {
    val certs = snapshot.certificates ?: return null
    val nowSeconds = snapshot.generatedAt / 1000
    val issued = certs.issued
    val days = issued?.let { ((it.notAfter - nowSeconds) / 86_400).toInt() }
    val nodeFaults = snapshot.nodes.any { it.waitingForCertificate } || certs.delivery.any { it.state == "default" }
    var cause: String? = null
    if (certs.names.size > certs.maxNames) {
        cause = "certificates:names"
        add(cause, Health.DOWN, "The certificate cannot be ordered: ${certs.names.size} names", "one certificate holds at most ${certs.maxNames} names", "certificates", null, null)
    }
    if (certs.order.state == "failed") {
        cause = cause ?: "certificates:order"
        val severe = issued == null || nodeFaults || (days != null && days < 7)
        val keep = if (issued == null) "there is no certificate yet" else "the nodes keep the certificate they have"
        add("certificates:order", if (severe) Health.DOWN else Health.DEGRADED, "The certificate order failed", "${certs.order.causeText.orEmpty()} · $keep", "certificates", null, null)
    } else if (issued == null && certs.names.isNotEmpty() && certs.order.state != "running") {
        cause = cause ?: "certificates:none"
        add("certificates:none", Health.DOWN, "There is no certificate", "the nodes answer their names with a default certificate", "certificates", null, null)
    }
    if (days != null && days < 14) {
        add("certificates:expiry", if (days < 7) Health.DOWN else Health.DEGRADED, if (days < 0) "The certificate expired" else "The certificate expires in $days days", "renewal does not work: see the last order", "certificates", "certificate", null)
    }
    if (certs.caa.enabled && certs.caa.known && (certs.caa.foreign.isNotEmpty() || certs.caa.missing.isNotEmpty())) {
        val detail = certs.caa.foreign.firstOrNull()?.let { "a CAA record that HomeGAP did not write: $it" } ?: "HomeGAP's CAA record is not in DNS yet"
        add("certificates:caa", Health.DEGRADED, "The CAA records differ", detail, "certificates", null, null)
    }
    if (certs.staging) {
        add("certificates:staging", Health.DEGRADED, "The certificate is from the staging directory", "browsers do not trust it: acme.staging is on", "certificates", null, null)
    }
    for (row in certs.probes.filter { it.kind == "homelab" }) {
        val soonest = row.cells.filter { !it.stale && (it.state == "soon" || it.state == "expiring") }.minByOrNull { it.daysRemaining ?: 0 } ?: continue
        add("certificates:homelab:${row.name}", Health.DEGRADED, "${row.name}: the homelab's certificate expires in ${soonest.daysRemaining} days", "the homelab's Traefik renews it, not HomeGAP", "certificates", null, null)
    }
    return cause
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
    "tls-root" -> "Root name TLS"
    "cluster-proxy" -> "Cluster proxy"
    "ee-license" -> "EE license"
    else -> name
}
