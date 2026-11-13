package homegap.presentation.certificates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.CertDeliveryDto
import homegap.api.CertNameDto
import homegap.api.CertOrderRecordDto
import homegap.api.CertProbeDto
import homegap.api.CertProbeRowDto
import homegap.api.CertificatesDto
import homegap.api.Health
import homegap.api.IssuedCertificateDto
import homegap.api.StatusSnapshot
import homegap.presentation.core.Presenter
import homegap.presentation.data.ActionRunner
import homegap.presentation.data.Confirmations
import homegap.presentation.data.ControllerApi
import homegap.presentation.dns.dnsTabs
import homegap.presentation.format.TimeFormatter
import homegap.presentation.format.ago
import homegap.presentation.format.plural
import homegap.presentation.routing.Location
import homegap.presentation.routing.Route
import homegap.presentation.ui.ActionModel
import homegap.presentation.ui.ActionStyle
import homegap.presentation.ui.ConfirmModel
import homegap.presentation.ui.EmptyStateModel
import homegap.presentation.ui.FactRow
import homegap.presentation.ui.InspectorModel
import homegap.presentation.ui.InspectorSection
import homegap.presentation.ui.PageHeadModel
import homegap.presentation.ui.Severity
import homegap.presentation.ui.on
import homegap.presentation.ui.statusLineOf

data class CertificatesScreenProps(
    val status: StatusSnapshot,
    val now: Long,
    /** `certificate`, `node:<id>`, `order:<id>` or `probe:<name>@<node>`. */
    val selection: String? = null,
)

@SingleIn(AppScope::class)
@Inject
class CertificatesScreenPresenter(
    private val api: ControllerApi,
    private val actions: ActionRunner,
    private val confirmations: Confirmations,
    private val timeFormatter: TimeFormatter,
) : Presenter<CertificatesScreenProps, CertificatesScreenModel> {
    @Composable
    override fun model(props: CertificatesScreenProps): CertificatesScreenModel {
        var faultsOnly by remember { mutableStateOf(false) }
        val status = props.status
        val certs = status.certificates
        val tabs = dnsTabs(Route.Certificates, status)
        if (certs == null || status.nodes.isEmpty()) {
            return CertificatesScreenModel(
                head = PageHeadModel(
                    caption = "DNS · certificates",
                    headline = "No nodes yet.",
                    tabs = tabs,
                ),
                empty = EmptyStateModel(
                    title = "Nothing to certify yet",
                    text = "Once a node is set up, this screen shows the one certificate that the nodes share, which node has it, " +
                        "and what a visitor gets on each name through each node. HomeGAP orders the certificate on its own.",
                    action = ActionModel(
                        label = "Add a node",
                        style = ActionStyle.Primary,
                        location = Location(Route.AddNode),
                    ),
                ),
            )
        }
        val blocked = blockedReason(status)
        val view = CertificatesView(certs, status, props.now, props.selection, timeFormatter)
        return CertificatesScreenModel(
            head = PageHeadModel(
                caption = "DNS · certificates · ${plural(certs.names.size, "name")}",
                headline = view.headline(),
                statusLine = statusLineOf(status.attention.on(Route.Certificates)),
                actions = listOfNotNull(orderNow(certs, blocked)),
                tabs = tabs,
            ),
            banner = view.banner(),
            progress = view.progress(),
            failure = view.failure(),
            certificate = view.certificate(replaceKey(certs, blocked)),
            delivery = view.delivery { node -> resend(node, blocked) },
            deliverySummary = view.deliverySummary(),
            visitor = view.visitor(
                faultsOnly = faultsOnly,
                onFaultsOnly = { faultsOnly = it },
                check = check(status),
            ),
            orders = view.orders(),
            policy = view.policy(),
            inspector = view.inspector(
                resend = { node -> resend(node, blocked) },
                check = check(status),
            ),
        )
    }

    /** Why a change cannot run now, or null. */
    private fun blockedReason(status: StatusSnapshot): String? = when {
        status.dryRun -> "Dry run: HomeGAP changes nothing"
        status.paused -> "Changes are paused"
        else -> null
    }

    private fun orderNow(certs: CertificatesDto, blocked: String?): ActionModel? {
        val running = certs.order.state == "running"
        val wanted = certs.order.state == "failed" || certs.order.due != null || certs.issued == null
        if (!wanted || running) return null
        val failures = certs.order.failuresLastHour
        return ActionModel(
            label = "Order now",
            style = ActionStyle.Primary,
            enabled = blocked == null && failures < RATE_LIMIT,
            tooltip = blocked ?: if (failures >= RATE_LIMIT) "$failures orders failed in the last hour. Wait for the next hour." else "Start the order now, without the hourly retry",
            onClick = {
                confirmations.ask(
                    ConfirmModel(
                        title = "Order now?",
                        text = "HomeGAP starts the order now and does not wait for the hourly retry.",
                        confirmLabel = "Order now",
                        consequences = listOf("Failed orders in the last hour: $failures of $RATE_LIMIT for each name. That is the Let's Encrypt limit."),
                    ),
                ) {
                    actions.run("Order now") { api.orderCertificate(replaceKey = false) }
                }
            },
        )
    }

    private fun replaceKey(certs: CertificatesDto, blocked: String?): ActionModel {
        val running = certs.order.state == "running"
        return ActionModel(
            label = "Replace the key…",
            style = ActionStyle.Danger,
            enabled = blocked == null && !running,
            tooltip = blocked ?: if (running) "An order runs" else "A new key and certificate, and the old certificate is revoked",
            onClick = {
                confirmations.ask(
                    ConfirmModel(
                        title = "Replace the key?",
                        text = "HomeGAP orders a new certificate with a new key for the same ${plural(certs.names.size, "name")}. " +
                            "When it is issued and sent to the nodes, the old certificate is revoked.",
                        confirmLabel = "Replace the key",
                        consequences = listOf("Use it when a node may be compromised."),
                        danger = true,
                    ),
                ) {
                    actions.run("Replace the key") { api.orderCertificate(replaceKey = true) }
                }
            },
        )
    }

    private fun resend(node: String, blocked: String?): ActionModel = ActionModel(
        label = "Send again",
        enabled = blocked == null,
        tooltip = blocked ?: "Send the current certificate to $node in the next pass",
        onClick = { actions.run("Send again") { api.resendCertificate(node) } },
    )

    private fun check(status: StatusSnapshot): ActionModel = ActionModel(
        label = "Check now",
        enabled = !status.dryRun,
        tooltip = if (status.dryRun) "Dry run: HomeGAP does not reach the nodes" else "Run the TLS probes again",
        onClick = { actions.run("Check now") { api.checkCertificates() } },
    )

    private companion object {
        const val RATE_LIMIT = 5
    }
}

/** Builds the parts of the screen from the `certificates` part of the status. */
internal class CertificatesView(
    private val certs: CertificatesDto,
    private val status: StatusSnapshot,
    private val now: Long,
    private val selection: String?,
    private val time: TimeFormatter,
) {
    private val issued = certs.issued
    private val nowSeconds = now / 1000
    private val daysLeft = issued?.let { ((it.notAfter - nowSeconds) / DAY).toInt() }
    private val nodes = status.nodes.map { it.id }

    private fun date(seconds: Long) = time.date(seconds * 1000)

    private fun minutesUntil(ms: Long) = ((ms - now + 59_999) / 60_000).coerceAtLeast(1)

    fun headline(): String {
        val order = certs.order
        val total = certs.delivery.size
        val current = certs.delivery.count { it.state == "ok" }
        return when {
            order.state == "running" -> "An order runs for ${plural(order.names, "name")}: ${order.reason.orEmpty()}. Started ${order.startedAt?.let { ago(it, now) } ?: "now"}."
            order.state == "failed" -> {
                val keep = issued?.let { "The nodes keep the certificate of ${date(it.notBefore)}." } ?: "There is no certificate yet."
                val next = order.retryAt?.let { " Next try in ${minutesUntil(it)} min." }.orEmpty()
                "The last order failed ${order.failedAt?.let { ago(it, now) } ?: "recently"}. $keep$next"
            }
            issued == null -> "There is no certificate. The nodes answer their names with a default certificate."
            nowSeconds >= issued.renewAt -> "${plural(daysLeft ?: 0, "day")} left. A new order starts in the next pass."
            else -> {
                val who = if (total == 1 && current == 1) "The node has it." else if (current == total) "All ${plural(total, "node")} have it." else "$current of ${plural(total, "node")} have it."
                "Valid for ${plural(daysLeft ?: 0, "day")}. HomeGAP orders a new one on ${date(issued.renewAt)}. $who"
            }
        }
    }

    fun banner(): String? = when {
        certs.names.size > certs.maxNames -> {
            val by = certs.names.groupBy { it.reason }
            val services = by["service"].orEmpty().size + by["frontdoor"].orEmpty().size
            val shared = by["client"].orEmpty().size + by["status"].orEmpty().size + by["speedtest"].orEmpty().size
            "The order cannot run: ${certs.names.size} names, and one certificate holds at most ${certs.maxNames}. " +
                "${by["management"].orEmpty().size} management names, $shared shared pages, $services services in terminate mode. A wildcard front door needs one name."
        }
        certs.staging -> "This certificate is from the Let's Encrypt staging directory. Browsers do not trust it. acme.staging is on in the config."
        else -> null
    }

    fun progress(): OrderProgressModel? {
        val order = certs.order
        if (order.state != "running") return null
        val current = STEPS.indexOfFirst { it.first == order.step }.coerceAtLeast(0)
        return OrderProgressModel(
            caption = "Order · ${plural(order.names, "name")} · ${order.reason.orEmpty()}",
            started = order.startedAt?.let { "started ${ago(it, now)}" }.orEmpty(),
            steps = STEPS.mapIndexed { index, (_, label, note) ->
                OrderStepModel(
                    label = label,
                    note = note,
                    state = when {
                        index < current -> StepState.Done
                        index == current -> StepState.Now
                        else -> StepState.Next
                    },
                )
            },
        )
    }

    fun failure(): OrderFailureModel? {
        val order = certs.order
        if (order.state != "failed") return null
        val waiting = certs.delivery.filter { it.state == "waiting" }.map { it.node }
        val keep = issued?.let { " The nodes keep the certificate of ${date(it.notBefore)}." }
            ?: " There is no certificate yet, so the nodes answer with a default certificate."
        val wait = waiting.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString()} ${if (it.size == 1) "waits" else "wait"} for an order with ${if (it.size == 1) "its name" else "their names"}, so the tunnel waits." }.orEmpty()
        return OrderFailureModel(
            title = "Order failed · ${order.causeText ?: "the ACME client failed"}",
            text = (CAUSES[order.cause] ?: CAUSES.getValue("other")) + keep + wait,
            names = (certs.pendingAdd.ifEmpty { order.failedNames }).take(SHOWN_NAMES),
            retry = order.retryAt?.let { if (it > now) "next try in ${minutesUntil(it)} min" else "next try in the next pass" }.orEmpty(),
            failures = "failures in the last hour: ${order.failuresLastHour} of $RATE_LIMIT",
            activity = Location(Route.Activity),
        )
    }

    fun certificate(replaceKey: ActionModel): CertificatePanelModel? {
        val cert = issued ?: return null
        val span = (cert.notAfter - cert.notBefore).coerceAtLeast(1).toDouble()
        val days = daysLeft ?: 0
        return CertificatePanelModel(
            location = Location(Route.Certificates, CERTIFICATE),
            selected = selection == CERTIFICATE,
            lifetime = LifetimeModel(
                issued = date(cert.notBefore),
                renews = date(cert.renewAt),
                expires = date(cert.notAfter),
                left = if (days < 0) "expired" else "${plural(days, "day")} left",
                todayPercent = ((nowSeconds - cert.notBefore) / span * 100).coerceIn(0.0, 100.0),
                renewPercent = ((cert.renewAt - cert.notBefore) / span * 100).coerceIn(0.0, 100.0),
                tone = lifetimeTone(cert),
            ),
            facts = listOf(
                FactRow("Issuer", cert.issuer, mono = true),
                FactRow("Issued", date(cert.notBefore), mono = true),
                FactRow("Expires", date(cert.notAfter), mono = true),
                FactRow("Directory", directoryName(), mono = true),
            ),
            names = certs.names.filter { it.name in cert.names }.map(::nameModel),
            pending = if (certs.pendingAdd.isEmpty() && certs.pendingRemove.isEmpty()) {
                null
            } else {
                PendingChangeModel(
                    add = certs.names.filter { it.name in certs.pendingAdd }.map(::nameModel),
                    remove = certs.pendingRemove,
                    note = if (certs.pendingRemove.isNotEmpty()) {
                        "A name leaves the certificate, so HomeGAP revokes the old certificate after the new one is issued."
                    } else {
                        "The next order adds these names."
                    },
                )
            },
            replaceKey = replaceKey,
        )
    }

    fun delivery(resend: (String) -> ActionModel): List<DeliveryRowModel> = certs.delivery.map { row ->
        DeliveryRowModel(
            node = row.node,
            place = place(row.node),
            installed = installedText(row),
            installedTone = when (row.state) {
                "ok" -> Tone.Ok
                "behind" -> Tone.Warn
                "none", "default" -> Tone.Down
                "waiting" -> Tone.Accent
                else -> Tone.Muted
            },
            installedAt = row.installedAt?.let { "${date(it)} ${time.time(it * 1000)}" } ?: "—",
            management = row.management?.let(::probeText) ?: "—",
            managementTone = row.management?.let(::probeTone) ?: Tone.Muted,
            state = STATES[row.state] ?: row.state,
            stateTone = stateTone(row.state),
            selected = selection == "$NODE${row.node}",
            location = Location(Route.Certificates, "$NODE${row.node}"),
            resend = resend(row.node).takeIf { issued != null && row.state in setOf("behind", "none", "default") },
        )
    }

    fun deliverySummary(): String {
        val total = certs.delivery.size
        val current = certs.delivery.count { it.state == "ok" }
        val behind = certs.delivery.count { it.state == "behind" }
        return when {
            issued == null -> "no certificate to deliver"
            behind > 0 -> "$current of $total current · $behind behind"
            else -> "$current of $total current"
        }
    }

    fun visitor(faultsOnly: Boolean, onFaultsOnly: (Boolean) -> Unit, check: ActionModel): VisitorMatrixModel {
        val rows = certs.probes
        val faulty = rows.filter { row -> row.cells.any { probeTone(it) == Tone.Warn || probeTone(it) == Tone.Down } }
        val shown = if (faultsOnly) faulty else rows
        val newest = rows.flatMap { it.cells }.mapNotNull { it.probedAt }.maxOrNull()
        return VisitorMatrixModel(
            nodes = nodes,
            groups = GROUPS.mapNotNull { (group, title) ->
                shown.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { matching ->
                    VisitorGroupModel(
                        title = title,
                        rows = matching.map(::visitorRow),
                    )
                }
            },
            faultsOnly = faultsOnly,
            faultRows = faulty.size,
            probed = newest?.let { "probed ${ago(it * 1000, now)}" } ?: "not probed yet",
            onFaultsOnly = onFaultsOnly,
            check = check,
        )
    }

    private fun visitorRow(row: CertProbeRowDto): VisitorRowModel = VisitorRowModel(
        name = row.name,
        tag = if (row.kind == "homelab") "Homelab" else "Node",
        passthrough = row.kind == "homelab",
        cells = row.cells.map { cell ->
            val key = "$PROBE${row.name}@${cell.node}"
            VisitorCellModel(
                text = cellText(cell),
                label = "${row.name} through ${cell.node}: ${cellText(cell)}",
                tone = probeTone(cell),
                selected = selection == key,
                location = Location(Route.Certificates, key),
            )
        },
    )

    fun orders(): List<OrderRowModel> = certs.history.map { record ->
        OrderRowModel(
            at = time.dateTime(record.at),
            what = if (record.kind == "revoke") "Revocation · old certificate" else "Order · ${plural(record.names.size, "name")}",
            note = if (record.ok) record.reason else record.causeText ?: record.reason,
            noteTone = if (record.ok) Tone.Muted else Tone.Down,
            result = orderResult(record),
            tone = if (record.ok) Tone.Ok else Tone.Down,
            selected = selection == "$ORDER${record.id}",
            location = Location(Route.Certificates, "$ORDER${record.id}"),
        )
    }

    fun policy(): PolicyModel {
        val caa = certs.caa
        val records = caa.desired.map { value ->
            CaaRowModel(
                value = value,
                state = when {
                    !caa.known -> "not read yet"
                    value in caa.missing -> "missing"
                    else -> "in DNS"
                },
                tone = when {
                    !caa.known -> Tone.Muted
                    value in caa.missing -> Tone.Warn
                    else -> Tone.Ok
                },
            )
        } + caa.foreign.map { CaaRowModel(value = it, state = "not HomeGAP's", tone = Tone.Warn) }
        return PolicyModel(
            caption = if (caa.enabled) "acme.caa on" else "acme.caa off",
            records = records,
            warning = when {
                !caa.enabled -> "acme.caa is off: HomeGAP publishes no CAA record, so any certificate authority can issue for the domain."
                caa.foreign.isNotEmpty() -> "A CAA record that HomeGAP did not write: ${caa.foreign.first()}. Each authority it names can issue for the domain. Remove it in Bunny, or keep it on purpose."
                caa.missing.isNotEmpty() -> "DNS lacks a CAA record that HomeGAP publishes. The next DNS pass writes it again."
                else -> null
            },
            facts = listOf(
                FactRow("contact", certs.contact, mono = true),
                FactRow("directory", directoryName(), mono = true),
            ),
            note = "A node has no DNS key, so it cannot order a certificate.",
        )
    }

    fun inspector(resend: (String) -> ActionModel, check: ActionModel): InspectorModel? {
        val key = selection ?: return null
        return when {
            key == CERTIFICATE -> certificateInspector()
            key.startsWith(NODE) -> certs.delivery.firstOrNull { it.node == key.removePrefix(NODE) }?.let { nodeInspector(it, resend(it.node)) }
            key.startsWith(ORDER) -> certs.history.firstOrNull { it.id == key.removePrefix(ORDER) }?.let(::orderInspector)
            key.startsWith(PROBE) -> {
                val name = key.removePrefix(PROBE).substringBeforeLast('@')
                val node = key.substringAfterLast('@')
                val row = certs.probes.firstOrNull { it.name == name }
                row?.cells?.firstOrNull { it.node == node }?.let { probeInspector(row, it, check) }
            }
            else -> null
        }
    }

    private fun certificateInspector(): InspectorModel? {
        val cert = issued ?: return null
        return InspectorModel(
            kind = "Certificate",
            title = "Node certificate",
            status = "${daysLeft?.let { plural(it, "day") + " left" } ?: ""} · ${plural(cert.names.size, "name")}",
            health = toneHealth(lifetimeTone(cert)),
            sections = listOf(
                InspectorSection.Facts(
                    title = null,
                    rows = listOf(
                        FactRow("key type", cert.keyType, mono = true),
                        FactRow("serial", cert.serial, mono = true, copy = true),
                        FactRow("SHA-256", cert.fingerprint, mono = true, copy = true),
                        FactRow("version", cert.version, mono = true),
                        FactRow("issuer", cert.issuer, mono = true),
                        FactRow("renews", date(cert.renewAt), mono = true),
                        FactRow("directory", certs.directory.removePrefix("https://"), mono = true),
                    ),
                ),
                InspectorSection.Note(
                    "The certificate is public: Let's Encrypt publishes each one in the certificate transparency logs. " +
                        "The key never leaves the homelab and the nodes.",
                ),
            ),
        )
    }

    private fun nodeInspector(row: CertDeliveryDto, resend: ActionModel): InspectorModel = InspectorModel(
        kind = "Delivery",
        title = row.node,
        status = STATES[row.state] ?: row.state,
        health = toneHealth(stateTone(row.state)),
        sections = listOfNotNull(
            InspectorSection.Facts(
                title = null,
                rows = listOf(
                    FactRow("installed", installedText(row), mono = true),
                    FactRow("delivered", row.installedAt?.let { time.dateTime(it * 1000) } ?: "—", mono = true),
                    FactRow("serves", row.management?.let(::probeText) ?: "—", mono = true),
                ),
            ),
            row.missing.takeIf { it.isNotEmpty() }?.let { InspectorSection.Code("Names it lacks", it.joinToString("\n")) },
            InspectorSection.Note(
                when (row.state) {
                    "waiting" -> "Not broken. Its tunnel connects once an order with its management name completes."
                    "unknown" -> "The node did not answer the last probe. The values above are from before."
                    "default" -> "The node answers its names with the default certificate. Send again, or see the last order."
                    else -> "The next reconcile pass sends the current certificate. The node stack does not restart."
                },
                severity = if (row.state == "default") Severity.Down else Severity.Info,
            ),
        ),
        actions = listOfNotNull(resend.takeIf { issued != null && row.state != "waiting" }),
    )

    private fun orderInspector(record: CertOrderRecordDto): InspectorModel = InspectorModel(
        kind = if (record.kind == "revoke") "Revocation" else "Order",
        title = if (record.kind == "revoke") "Revocation · old certificate" else "Order · ${plural(record.names.size, "name")}",
        status = orderResult(record),
        health = if (record.ok) Health.OK else Health.DOWN,
        sections = listOfNotNull(
            InspectorSection.Facts(
                title = null,
                rows = listOfNotNull(
                    FactRow("when", time.dateTime(record.at), mono = true),
                    FactRow("reason", record.reason),
                    record.causeText?.let { FactRow("cause", it) },
                    record.version?.let { FactRow("version", it, mono = true) },
                ),
            ),
            record.names.takeIf { it.isNotEmpty() }?.let { InspectorSection.Code("Names", it.joinToString("\n")) },
            InspectorSection.Note("The full output of the ACME client is in the activity log."),
        ),
        actions = listOf(
            ActionModel(
                label = "Open in Activity",
                location = Location(Route.Activity),
            ),
        ),
    )

    private fun probeInspector(row: CertProbeRowDto, cell: CertProbeDto, check: ActionModel): InspectorModel {
        val homelab = row.kind == "homelab"
        return InspectorModel(
            kind = "What a visitor gets",
            title = row.name,
            status = "through ${cell.node} · ${cellText(cell)}",
            health = toneHealth(probeTone(cell)),
            sections = listOf(
                InspectorSection.Facts(
                    title = null,
                    rows = listOfNotNull(
                        FactRow("subject", cell.subject ?: "—", mono = true),
                        FactRow("covers name", if (cell.state == "mismatch" || cell.state == "default" || cell.state == "none") "no" else "yes"),
                        FactRow("issuer", cell.issuer ?: "—", mono = true),
                        cell.notAfter?.let { FactRow("expires", date(it), mono = true) },
                        FactRow("held by", if (homelab) "the homelab (passthrough)" else "the nodes (terminate)"),
                        FactRow("probed", cell.probedAt?.let { ago(it * 1000, now) + if (cell.stale) " · the node did not answer since" else "" } ?: "not yet"),
                    ),
                ),
                InspectorSection.Note(
                    when {
                        homelab -> "The Traefik of the homelab renews this certificate, not HomeGAP. The nodes forward this name and hold no certificate for it."
                        cell.state == "default" || cell.state == "none" -> "The node has no certificate for this name. Once an order with this name completes, the next pass sends it."
                        cell.state == "mismatch" -> "The node serves a certificate that does not cover this name."
                        else -> "Probed from the node itself: a TLS handshake to 127.0.0.1:443 with this name."
                    },
                ),
            ),
            actions = listOf(check),
        )
    }

    private fun nameModel(name: CertNameDto): CertNameModel = CertNameModel(
        name = name.name,
        reason = when (name.reason) {
            "management" -> "management name of ${name.subject.orEmpty()}"
            "client" -> "client name"
            "status" -> "status page"
            "speedtest" -> "speed test"
            "frontdoor" -> "front door, terminate"
            else -> "service ${name.subject.orEmpty()}"
        },
        location = when (name.reason) {
            "management" -> name.subject?.let { Location(Route.Nodes, it) }
            "service" -> Location(Route.Services, name.name)
            else -> null
        },
    )

    private fun place(node: String): String =
        status.nodes.firstOrNull { it.id == node }?.let { it.location?.name ?: it.region }.orEmpty()

    private fun installedText(row: CertDeliveryDto): String = when (row.state) {
        "ok" -> "Current · ${row.installed.orEmpty()}"
        "behind" -> row.installedIssuedAt?.let { "Behind · ${date(it)}" } ?: "Behind"
        "none", "default", "waiting" -> "None"
        else -> row.installed ?: "—"
    }

    private fun probeText(cell: CertProbeDto): String {
        val stale = if (cell.stale) cell.probedAt?.let { " · ${ago(it * 1000, now)}" }.orEmpty() else ""
        return when (cell.state) {
            "default" -> "Default certificate"
            "none" -> "No certificate"
            "mismatch" -> "Wrong name · ${cell.subject.orEmpty()}"
            "unknown" -> "—"
            else -> listOfNotNull(
                cell.subject?.substringAfter("CN=")?.substringBefore(',')?.ifBlank { null },
                cell.daysRemaining?.let { "$it d" },
            ).joinToString(" · ").ifEmpty { "?" }
        } + stale
    }

    private fun cellText(cell: CertProbeDto): String = when (cell.state) {
        "unknown" -> "—"
        "none" -> "none"
        "default" -> "default"
        "mismatch" -> "wrong name"
        else -> "${cell.daysRemaining ?: "?"} d" + if (cell.stale) "?" else ""
    }

    private fun probeTone(cell: CertProbeDto): Tone = when {
        cell.state == "unknown" -> Tone.Muted
        cell.state == "none" || cell.state == "default" || cell.state == "expiring" -> Tone.Down
        cell.stale || cell.state == "soon" || cell.state == "mismatch" -> Tone.Warn
        else -> Tone.Ok
    }

    private fun stateTone(state: String): Tone = when (state) {
        "ok" -> Tone.Ok
        "behind", "unknown" -> Tone.Warn
        "waiting" -> Tone.Accent
        else -> Tone.Down
    }

    /** A certificate that is not yet due for its renewal is fine, however few days it has. */
    private fun lifetimeTone(cert: IssuedCertificateDto): Tone {
        val days = daysLeft ?: 0
        return if (days >= 0 && nowSeconds < cert.renewAt) Tone.Ok else expiryTone(days)
    }

    private fun expiryTone(days: Int): Tone = when {
        days < 7 -> Tone.Down
        days < 14 -> Tone.Warn
        else -> Tone.Ok
    }

    private fun orderResult(record: CertOrderRecordDto): String = when {
        !record.ok -> "failed"
        record.kind == "revoke" -> "revoked"
        else -> "issued" + record.durationMs?.let { " · ${duration(it)}" }.orEmpty()
    }

    private fun duration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    private fun directoryName(): String = when {
        certs.staging -> "Let's Encrypt · staging"
        certs.directory.contains("acme-v02.api.letsencrypt.org") -> "Let's Encrypt · production"
        else -> certs.directory.removePrefix("https://").substringBefore('/')
    }

    private fun toneHealth(tone: Tone): Health = when (tone) {
        Tone.Ok -> Health.OK
        Tone.Warn, Tone.Accent -> Health.DEGRADED
        Tone.Down -> Health.DOWN
        Tone.Muted -> Health.UNKNOWN
    }

    companion object {
        const val CERTIFICATE = "certificate"
        const val NODE = "node:"
        const val ORDER = "order:"
        const val PROBE = "probe:"
        private const val DAY = 86_400L
        private const val RATE_LIMIT = 5
        private const val SHOWN_NAMES = 8

        private val STEPS = listOf(
            Triple("records", "Records created", "in Bunny DNS"),
            Triple("dns", "Waiting for DNS", "Let's Encrypt reads the records"),
            Triple("validated", "Validated", "Let's Encrypt issues the certificate"),
            Triple("issued", "Issued", "the next pass sends it to each node"),
        )

        private val GROUPS = listOf(
            "management" to "Management",
            "shared" to "Shared pages",
            "service" to "Services",
            "homelab" to "Held by the homelab",
        )

        private val STATES = mapOf(
            "ok" to "OK",
            "behind" to "behind",
            "none" to "none",
            "default" to "default served",
            "waiting" to "waiting for its name",
            "unknown" to "unknown",
        )

        private val CAUSES = mapOf(
            "dns-timeout" to "Let's Encrypt looked for the challenge record before Bunny DNS served it.",
            "rate-limit" to "Let's Encrypt allows 5 failed orders for each name in an hour.",
            "caa" to "A CAA record in DNS does not allow Let's Encrypt with the DNS challenge. See who can issue, below.",
            "bunny-key" to "HomeGAP writes the challenge records with the Bunny key. Set bunny.api_key, or check that the key can edit the zone.",
            "acme-unreachable" to "Let's Encrypt or the network did not answer.",
            "too-many-names" to "One certificate holds at most 100 names.",
            "other" to "The full text is in the activity log.",
        )
    }
}
