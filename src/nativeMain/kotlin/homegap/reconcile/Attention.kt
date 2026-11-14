package homegap.reconcile

import homegap.acme.renewalIsLate
import homegap.api.AttentionItemDto
import homegap.api.CertProbeRowDto
import homegap.api.CertificatesDto
import homegap.api.CheckDto
import homegap.api.DnsRecordStatusDto
import homegap.api.Health
import homegap.api.IssuedCertificateDto
import homegap.api.LabelErrorDto
import homegap.api.NodeStatusDto
import homegap.api.ServiceNodeHealthDto
import homegap.api.ServiceStatusDto
import homegap.api.StatusSnapshot
import homegap.api.TunnelStatusDto

private const val DETAIL_LENGTH = 160
private const val SECONDS_PER_DAY = 86_400

/** A certificate with fewer days than this gets an item. */
private const val EXPIRY_WARNING_DAYS = 14

/** A certificate with fewer days than this is a severe fault. */
private const val EXPIRY_SEVERE_DAYS = 7

private const val EXPIRY_ID = "certificates:expiry"

private fun renewalIsLate(issued: IssuedCertificateDto, nowSeconds: Long) =
    renewalIsLate(notAfter = issued.notAfter, renewAt = issued.renewAt, nowSeconds = nowSeconds)

private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * What needs attention in [snapshot], worst first, then oldest first. [since] gives the time when
 * each item id was first seen. An item that is not in it is new in the snapshot.
 */
internal fun attentionItems(snapshot: StatusSnapshot, since: Map<String, Long>): List<AttentionItemDto> {
    val list = AttentionList(snapshot, since)
    // Certificate faults first: node and tunnel items that follow from one fold under it.
    val certCause = list.addCertificateItems()
    list.addHomelabIsolation()
    snapshot.nodes.forEach { list.addNodeItems(it, certCause) }
    snapshot.services.forEach { list.addServiceItems(it) }
    snapshot.labelErrors.forEach { list.addLabelError(it) }
    snapshot.dns.forEach { list.addDnsItem(it) }
    snapshot.tunnels.filter { !it.up }.forEach { list.addTunnelDown(it, certCause) }
    list.addClusterAlerts()
    return list.sorted()
}

/** One item, before it gets the time when it was first seen. */
private class Finding(
    val id: String,
    val severity: Health,
    val title: String,
    val detail: String,
    val route: String,
    val selection: String? = null,
    val parent: String? = null,
)

private class AttentionList(private val snapshot: StatusSnapshot, private val since: Map<String, Long>) {
    private val items = mutableListOf<AttentionItemDto>()

    fun sorted(): List<AttentionItemDto> =
        items.sortedWith(compareBy<AttentionItemDto> { if (it.severity == Health.DOWN) 0 else 1 }.thenBy { it.since })

    private fun add(finding: Finding) {
        items += AttentionItemDto(
            id = finding.id,
            severity = finding.severity,
            title = finding.title,
            detail = finding.detail,
            since = since[finding.id] ?: snapshot.generatedAt,
            route = finding.route,
            selection = finding.selection,
            parent = finding.parent,
        )
    }

    // ------------------------------------------------------------------ certificate

    /**
     * Adds the items of the certificate of the nodes and returns the id of the one that causes node
     * faults (a failed order, or no certificate), if any.
     */
    fun addCertificateItems(): String? {
        val certs = snapshot.certificates ?: return null
        val nowSeconds = snapshot.generatedAt / 1000
        val days = certs.issued?.let { ((it.notAfter - nowSeconds) / SECONDS_PER_DAY).toInt() }
        var cause: String? = null
        if (certs.names.size > certs.maxNames) {
            cause = "certificates:names"
            add(
                Finding(
                    id = cause,
                    severity = Health.DOWN,
                    title = "The certificate cannot be ordered: ${certs.names.size} names",
                    detail = "one certificate holds at most ${certs.maxNames} names",
                    route = "certificates",
                ),
            )
        }
        if (certs.order.state == "failed") {
            cause = cause ?: "certificates:order"
            addFailedOrder(certs, days)
        } else if (certs.issued == null && certs.names.isNotEmpty() && certs.order.state != "running") {
            cause = cause ?: "certificates:none"
            add(
                Finding(
                    id = "certificates:none",
                    severity = Health.DOWN,
                    title = "There is no certificate",
                    detail = "the nodes answer their names with a default certificate",
                    route = "certificates",
                ),
            )
        }
        val issued = certs.issued
        val failed = certs.order.state == "failed"
        if (issued != null && days != null && days < EXPIRY_WARNING_DAYS && (failed || renewalIsLate(issued, nowSeconds))) {
            add(
                Finding(
                    id = EXPIRY_ID,
                    severity = if (days < EXPIRY_SEVERE_DAYS) Health.DOWN else Health.DEGRADED,
                    title = if (days < 0) "The certificate expired" else "The certificate expires in ${plural(days, "day")}",
                    detail = if (failed) "renewal does not work: see the last order" else "the renewal is late",
                    route = "certificates",
                    selection = "certificate",
                    parent = cause.takeIf { failed },
                ),
            )
        }
        addCaaDifference(certs)
        if (certs.staging) {
            add(
                Finding(
                    id = "certificates:staging",
                    severity = Health.DEGRADED,
                    title = "The certificate is from the staging directory",
                    detail = "browsers do not trust it: acme.staging is on",
                    route = "certificates",
                ),
            )
        }
        certs.probes.filter { it.kind == "homelab" }.forEach { addHomelabCertificate(it) }
        return cause
    }

    private fun addFailedOrder(certs: CertificatesDto, days: Int?) {
        val issued = certs.issued
        val nodeFaults = snapshot.nodes.any { it.waitingForCertificate } || certs.delivery.any { it.state == "default" }
        val severe = issued == null || nodeFaults || (days != null && days < EXPIRY_SEVERE_DAYS)
        val keep = if (issued == null) "there is no certificate yet" else "the nodes keep the certificate they have"
        add(
            Finding(
                id = "certificates:order",
                severity = if (severe) Health.DOWN else Health.DEGRADED,
                title = "The certificate order failed",
                detail = "${certs.order.causeText.orEmpty()} · $keep",
                route = "certificates",
            ),
        )
    }

    private fun addCaaDifference(certs: CertificatesDto) {
        val caa = certs.caa
        if (!caa.enabled || !caa.known) return
        if (caa.foreign.isEmpty() && caa.missing.isEmpty()) return
        add(
            Finding(
                id = "certificates:caa",
                severity = Health.DEGRADED,
                title = "The CAA records differ",
                detail = caa.foreign.firstOrNull()?.let { "a CAA record that HomeGAP did not write: $it" }
                    ?: "HomeGAP's CAA record is not in DNS yet",
                route = "certificates",
            ),
        )
    }

    private fun addHomelabCertificate(row: CertProbeRowDto) {
        val soonest = row.cells
            .filter { !it.stale && (it.state == "soon" || it.state == "expiring") }
            .minByOrNull { it.daysRemaining ?: 0 }
            ?: return
        add(
            Finding(
                id = "certificates:homelab:${row.name}",
                severity = Health.DEGRADED,
                title = "${row.name}: the homelab's certificate expires in ${soonest.daysRemaining} days",
                detail = "the homelab's Traefik renews it, not HomeGAP",
                route = "certificates",
            ),
        )
    }

    // ------------------------------------------------------------------ homelab and nodes

    fun addHomelabIsolation() {
        val isolation = snapshot.homelabIsolation ?: return
        if (isolation.endedAt != null) return
        add(
            Finding(
                id = "homelab:isolated",
                severity = Health.DOWN,
                title = "The homelab can't reach any node",
                detail = "check the homelab's internet connection",
                route = "nodes",
            ),
        )
    }

    fun addNodeItems(node: NodeStatusDto, certCause: String?) {
        addNodeHealth(node, certCause)
        addFailedUpdate(node)
        addNodeCertificate(node)
        if (node.drift.isNotEmpty()) {
            add(
                Finding(
                    id = "node:${node.id}:drift",
                    severity = Health.DEGRADED,
                    title = "${node.id} has drifted from its config",
                    detail = node.drift.joinToString("; ").take(DETAIL_LENGTH),
                    route = "nodes",
                    selection = node.id,
                ),
            )
        }
    }

    /** Adds one item for the state of the node. The first cause that applies gives the item. */
    private fun addNodeHealth(node: NodeStatusDto, certCause: String?) {
        val firstProblem = node.checks
            .firstOrNull { it.health == Health.DOWN || it.health == Health.DEGRADED }
            ?.let { problemText(it) }
        val deliveryState = snapshot.certificates?.delivery?.firstOrNull { it.node == node.id }?.state
        val finding = when {
            node.waitingForCertificate && node.lastProvisionOk != false -> Finding(
                id = "node:${node.id}:waiting",
                severity = Health.DEGRADED,
                title = "${node.id} waits for its certificate",
                detail = "its tunnel connects after the order with ${node.managementHost}",
                route = "certificates",
                selection = "node:${node.id}",
                parent = certCause,
            )

            deliveryState == "default" && node.lastProvisionOk != false -> Finding(
                id = "node:${node.id}:default-certificate",
                severity = Health.DOWN,
                title = "${node.id} answers with the default certificate",
                detail = firstProblem.orEmpty(),
                route = "certificates",
                selection = "node:${node.id}",
                parent = certCause,
            )

            node.lastProvisionOk == false -> Finding(
                id = "node:${node.id}:provision",
                severity = Health.DOWN,
                title = "${node.id}: provisioning failed",
                detail = node.lastProvisionError.orEmpty().take(DETAIL_LENGTH),
                route = "nodes",
                selection = node.id,
            )

            node.health == Health.DOWN -> Finding(
                id = "node:${node.id}:down",
                severity = Health.DOWN,
                title = "${node.id} is failing",
                detail = firstProblem.orEmpty(),
                route = "nodes",
                selection = node.id,
            )

            node.health == Health.DEGRADED -> Finding(
                id = "node:${node.id}:degraded",
                severity = Health.DEGRADED,
                title = "${node.id} has a warning",
                detail = firstProblem.orEmpty(),
                route = "nodes",
                selection = node.id,
                parent = expiryCause.takeIf { onlyTheCertificate(node) },
            )

            else -> return
        }
        add(finding)
    }

    /** The item that a node with only a certificate warning folds under, if the expiry is listed. */
    private val expiryCause: String? get() = items.firstOrNull { it.id == EXPIRY_ID }?.let { it.parent ?: it.id }

    private fun onlyTheCertificate(node: NodeStatusDto): Boolean = node.checks
        .filter { it.health == Health.DOWN || it.health == Health.DEGRADED }
        .all { it.name == "tls-mgmt" }

    private fun problemText(check: CheckDto): String {
        val measured = check.value?.let { value -> "${value.toLong()} ${check.unit.orEmpty()}".trim() }
        return listOfNotNull(checkName(check.name), measured ?: check.detail.ifEmpty { null }).joinToString(" ")
    }

    private fun addFailedUpdate(node: NodeStatusDto) {
        val update = node.update?.takeIf { it.status == "failed" } ?: return
        add(
            Finding(
                id = "node:${node.id}:update",
                severity = Health.DOWN,
                title = "${node.id}: stack update failed",
                detail = if (update.rolledBack) "rolled back; the rollout is held" else "the rollback failed",
                route = "nodes",
                selection = node.id,
            ),
        )
    }

    /** The certificate of one node counts only if the snapshot has no certificate status. */
    private fun addNodeCertificate(node: NodeStatusDto) {
        if (snapshot.certificates != null) return
        val days = node.certificate?.daysRemaining?.takeIf { it < EXPIRY_WARNING_DAYS } ?: return
        val expired = days < 0
        add(
            Finding(
                id = "node:${node.id}:certificate",
                severity = if (expired) Health.DOWN else Health.DEGRADED,
                title = when {
                    expired -> "${node.id}: certificate expired"
                    else -> "${node.id}: certificate expires in $days days"
                },
                detail = node.certificate?.subject.orEmpty(),
                route = "nodes",
                selection = node.id,
            ),
        )
    }

    // ------------------------------------------------------------------ services, DNS, tunnels, cluster

    /** Adds one item for the edges where the service fails and one for those where it is slow. */
    fun addServiceItems(service: ServiceStatusDto) {
        for (health in listOf(Health.DOWN, Health.DEGRADED)) {
            val edges = service.perNode.filter { it.health == health }
            if (edges.isNotEmpty()) addServiceEdges(service, health, edges)
        }
    }

    private fun addServiceEdges(service: ServiceStatusDto, health: Health, edges: List<ServiceNodeHealthDto>) {
        val state = if (health == Health.DOWN) "failing" else "slow"
        val where = when {
            edges.size > 1 && edges.size == service.perNode.size -> "every edge"
            else -> edges.joinToString(", ") { it.nodeId }
        }
        val first = edges.first()
        val details = listOfNotNull(
            first.code?.takeIf { it > 0 }?.toString(),
            first.ms?.let { "${it.toLong()} ms" },
            first.detail.ifEmpty { null },
        )
        add(
            Finding(
                id = "service:${service.key}:$state",
                severity = health,
                title = "${service.hostname} $state on $where",
                detail = details.joinToString(" · "),
                route = "services",
                selection = service.hostname,
            ),
        )
    }

    fun addLabelError(error: LabelErrorDto) {
        add(
            Finding(
                id = "label:${error.container}:${error.message.hashCode()}",
                severity = Health.DEGRADED,
                title = "Label error on ${error.container}",
                detail = error.message,
                route = "services",
            ),
        )
    }

    fun addDnsItem(record: DnsRecordStatusDto) {
        val finding = when {
            record.monitorStatus == "offline" -> Finding(
                id = "dns:${record.name}:${record.type}:${record.nodeId}:offline",
                severity = Health.DEGRADED,
                title = "${record.name}: ${record.nodeId ?: "a member"} withdrawn",
                detail = "its health monitor is offline",
                route = "dns",
                selection = record.name,
            )

            !record.inSync -> Finding(
                id = "dns:${record.name}:${record.type}:${record.nodeId}:sync",
                severity = Health.DEGRADED,
                title = "${record.name} is not in sync",
                detail = record.diff.orEmpty().take(DETAIL_LENGTH),
                route = "dns",
                selection = record.name,
            )

            else -> return
        }
        add(finding)
    }

    fun addTunnelDown(tunnel: TunnelStatusDto, certCause: String?) {
        val waiting = snapshot.nodes.any { it.id == tunnel.nodeId && it.waitingForCertificate }
        add(
            Finding(
                id = "tunnel:${tunnel.nodeId}",
                severity = if (waiting) Health.DEGRADED else Health.DOWN,
                title = "Tunnel to ${tunnel.nodeId} is down",
                detail = when {
                    waiting -> "${tunnel.nodeId} serves the default certificate until its order completes"
                    else -> tunnel.lastExit.orEmpty()
                },
                route = "tunnels",
                selection = tunnel.nodeId,
                parent = if (waiting) certCause ?: "node:${tunnel.nodeId}:waiting" else null,
            ),
        )
    }

    fun addClusterAlerts() {
        snapshot.cluster?.alerts?.forEachIndexed { index, alert ->
            if (alert.health != Health.DOWN && alert.health != Health.DEGRADED) return@forEachIndexed
            add(
                Finding(
                    id = "cluster:$index:${alert.message.hashCode()}",
                    severity = alert.health,
                    title = alert.message,
                    detail = "",
                    route = "nodes",
                ),
            )
        }
    }
}

/**
 * Returns the time when each of [items] was first seen. An item that is in [previous] keeps its
 * time from there.
 */
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
