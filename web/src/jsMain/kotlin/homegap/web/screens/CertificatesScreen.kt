package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.certificates.CertificatePanelModel
import homegap.presentation.certificates.CertificatesScreenModel
import homegap.presentation.certificates.OrderFailureModel
import homegap.presentation.certificates.OrderProgressModel
import homegap.presentation.certificates.OrderRowModel
import homegap.presentation.certificates.PolicyModel
import homegap.presentation.certificates.StepState
import homegap.presentation.certificates.Tone
import homegap.presentation.certificates.VisitorMatrixModel
import homegap.presentation.routing.Location
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.attributes.colspan
import org.jetbrains.compose.web.dom.*

/** The Certificates tab of DNS. It shows state only: no certificate material passes through it. */
@Composable
fun CertificatesScreen(
    model: CertificatesScreenModel,
    onOpen: (Location) -> Unit,
) {
    model.banner?.let { text ->
        Div({
            classes("cert-banner")
            attr("role", "status")
        }) {
            Text(text)
        }
    }
    model.progress?.let { OrderProgress(it) }
    model.failure?.let {
        OrderFailure(
            failure = it,
            onOpen = onOpen,
        )
    }
    model.certificate?.let {
        CertificatePanel(
            panel = it,
            onOpen = onOpen,
        )
    }
    Figure(
        title = "Delivery to the nodes",
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text(model.deliverySummary) } },
    ) {
        DataTable(headers = DELIVERY_HEADERS) {
            model.delivery.forEach { row ->
                key(row.node) {
                    Tr({
                        if (row.selected) {
                            classes("selected")
                        }
                    }) {
                        Cell("Node", "primary-cell") {
                            NavLink(
                                href = HashRouter.href(row.location),
                                onNavigate = { onOpen(row.location) },
                            ) {
                                B { Text(row.node) }
                                Text(" ")
                                Span({ classes("muted") }) { Text(row.place) }
                            }
                        }
                        Cell("Installed", "mono", row.installedTone.className) { Text(row.installed) }
                        Cell("Installed at", "mono", "muted") { Text(row.installedAt) }
                        Cell("Management name", "mono", "wide-cell", row.managementTone.className) { Text(row.management) }
                        Cell("State", "mono", row.stateTone.className) { Text(row.state) }
                        Cell("", "actions") {
                            row.resend?.let { resend ->
                                Action(
                                    action = resend,
                                    onOpen = onOpen,
                                    small = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    model.visitor?.let {
        VisitorMatrix(
            matrix = it,
            onOpen = onOpen,
        )
    }
    Div({ classes("cert-lower") }) {
        Orders(
            rows = model.orders,
            onOpen = onOpen,
        )
        model.policy?.let { Policy(it) }
    }
}

@Composable
private fun OrderProgress(progress: OrderProgressModel) {
    Figure(
        title = progress.caption,
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text(progress.started) } },
    ) {
        Ol({
            classes("cert-steps")
            attr("aria-label", "Order progress")
        }) {
            progress.steps.forEach { step ->
                Li({
                    classes("cert-step", step.state.name.lowercase())
                    if (step.state == StepState.Now) {
                        attr("aria-current", "step")
                    }
                }) {
                    Span({ classes("cert-step-mark") }) {
                        Text(
                            when (step.state) {
                                StepState.Done -> "✓"
                                StepState.Now -> "◌"
                                StepState.Next -> "·"
                            }
                        )
                    }
                    Span {
                        Span({ classes("cert-step-label") }) { Text(step.label) }
                        if (step.note.isNotEmpty()) {
                            Span({ classes("muted", "small", "block") }) { Text(step.note) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderFailure(failure: OrderFailureModel, onOpen: (Location) -> Unit) {
    Section({
        classes("cert-failure")
        attr("aria-label", "Failed order")
    }) {
        Div({ classes("caption", "tone-down") }) { Text(failure.title) }
        P { Text(failure.text) }
        if (failure.names.isNotEmpty()) {
            Div({ classes("cert-failure-names", "mono") }) { Text(failure.names.joinToString(" · ")) }
        }
        Div({ classes("cert-failure-meta", "mono") }) {
            Span { Text(failure.retry) }
            Span { Text(failure.failures) }
            NavLink(
                href = HashRouter.href(failure.activity),
                onNavigate = { onOpen(failure.activity) },
                classNames = listOf("link"),
            ) {
                Text("Full text in Activity →")
            }
        }
    }
}

@Composable
private fun CertificatePanel(panel: CertificatePanelModel, onOpen: (Location) -> Unit) {
    val life = panel.lifetime
    Figure(
        title = "Node certificate",
        flush = true,
        actions = {
            NavLink(
                href = HashRouter.href(panel.location),
                onNavigate = { onOpen(panel.location) },
                classNames = listOf("link"),
            ) {
                Text("Details →")
            }
        },
    ) {
        Div({
            classes("lifetime", life.tone.className)
            style {
                property("--today", "${life.todayPercent}%")
                property("--renew", "${life.renewPercent}%")
            }
        }) {
            Div({
                classes("lifetime-bar")
                attr("role", "img")
                attr("aria-label", "Issued ${life.issued}, renews ${life.renews}, expires ${life.expires}, ${life.left}")
            }) {
                Span({ classes("lifetime-today") })
                Span({ classes("lifetime-renew") })
            }
            Div({
                classes("lifetime-marks", "mono")
                attr("aria-hidden", "true")
            }) {
                Span({ classes("lifetime-issued") }) { Text("issued"); Br(); B { Text(life.issued) } }
                // The labels stand in the order of their marks, side by side, so they never overprint.
                if (life.todayPercent <= life.renewPercent) {
                    Span({ classes("lifetime-now") }) { Text("today"); Br(); B { Text(life.left) } }
                    Span({ classes("lifetime-renews") }) { Text("renews"); Br(); B { Text(life.renews) } }
                } else {
                    Span({ classes("lifetime-renews") }) { Text("renews"); Br(); B { Text(life.renews) } }
                    Span({ classes("lifetime-now") }) { Text("today"); Br(); B { Text(life.left) } }
                }
                Span({ classes("lifetime-expires") }) { Text("expires"); Br(); B { Text(life.expires) } }
            }
        }
        Dl({ classes("cert-facts") }) {
            panel.facts.forEach { fact ->
                Div {
                    Dt { Text(fact.label) }
                    Dd({ classes("mono") }) { Text(fact.value) }
                }
            }
        }
        Div({ classes("cert-names-head") }) {
            Span({ classes("caption") }) { Text("Names on the certificate · ${panel.names.size}") }
            Span({ classes("muted", "small") }) { Text("from the config and the service labels") }
        }
        Ul({ classes("cert-names") }) {
            panel.names.forEach { name ->
                key(name.name) {
                    Li {
                        val location = name.location
                        if (location != null) {
                            NavLink(
                                href = HashRouter.href(location),
                                onNavigate = { onOpen(location) },
                                classNames = listOf("mono"),
                            ) {
                                HostName(name.name)
                            }
                        } else {
                            Span({ classes("mono") }) { HostName(name.name) }
                        }
                        Span({ classes("muted", "small") }) { Text(name.reason) }
                    }
                }
            }
        }
        panel.pending?.let { pending ->
            Div({ classes("cert-pending") }) {
                Div({ classes("caption", "tone-accent") }) { Text("Pending change · the config differs from the certificate") }
                pending.add.forEach { name ->
                    Div({ classes("mono", "tone-ok") }) {
                        Text("+ ${name.name} ")
                        Span({ classes("muted") }) { Text(name.reason) }
                    }
                }
                pending.remove.forEach { name ->
                    Div({ classes("mono", "tone-down") }) { Text("− $name") }
                }
                Div({ classes("small", "muted") }) { Text(pending.note) }
            }
        }
        Div({ classes("cert-danger") }) {
            P({ classes("small") }) {
                B({ classes("tone-down") }) { Text("Danger. ") }
                Text("A node may be compromised? A new key and certificate for the same names, and the old certificate is revoked.")
            }
            Action(
                action = panel.replaceKey,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun VisitorMatrix(matrix: VisitorMatrixModel, onOpen: (Location) -> Unit) {
    Figure(
        title = "What a visitor gets",
        flush = true,
        actions = {
            Button({
                classes("chip")
                if (matrix.faultsOnly) {
                    classes("active")
                }
                attr("type", "button")
                attr("aria-pressed", matrix.faultsOnly.toString())
                onClick { matrix.onFaultsOnly(!matrix.faultsOnly) }
            }) {
                Text("Faults only ${matrix.faultRows}")
            }
            Action(
                action = matrix.check,
                onOpen = onOpen,
                small = true,
            )
            Span({ classes("caption", "muted") }) { Text(matrix.probed) }
        },
    ) {
        Div({ classes("matrix-wrap") }) {
            ScrollHint()
            Table({ classes("matrix", "cert-matrix") }) {
                Thead {
                    Tr {
                        Th { Text("Name") }
                        matrix.nodes.forEach { node -> Th { Text(node) } }
                    }
                }
                Tbody {
                    if (matrix.groups.isEmpty()) {
                        Tr {
                            Td({
                                colspan(matrix.nodes.size + 1)
                                classes("muted")
                            }) {
                                Text(if (matrix.faultsOnly) "No faults: every name answers with a valid certificate." else "No public names yet.")
                            }
                        }
                    }
                    matrix.groups.forEach { group ->
                        key(group.title) {
                            Tr({ classes("cert-group") }) {
                                Th({
                                    colspan(matrix.nodes.size + 1)
                                    attr("scope", "colgroup")
                                }) {
                                    Text(group.title)
                                }
                            }
                            group.rows.forEach { row ->
                                key(row.name) {
                                    Tr {
                                        Td({ classes("matrix-host") }) {
                                            Span({ classes("cert-row-name") }) {
                                                Span({ classes("mono") }) { HostName(row.name) }
                                                Span({ classes("cert-tag", if (row.passthrough) "homelab" else "node") }) { Text(row.tag) }
                                            }
                                            if (row.passthrough) {
                                                Span({ classes("muted", "small", "block", "cert-pass-note") }) {
                                                    Text("The nodes forward this name. They hold no certificate for it.")
                                                }
                                            }
                                        }
                                        row.cells.forEach { cell ->
                                            Td({ classes("cert-cell-wrap") }) {
                                                NavLink(
                                                    href = HashRouter.href(cell.location),
                                                    onNavigate = { onOpen(cell.location) },
                                                    classNames = listOfNotNull("cert-cell", cell.tone.className, "selected".takeIf { cell.selected }),
                                                ) {
                                                    Span({ attr("aria-label", cell.label) }) { Text(cell.text) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        P({ classes("figure-note") }) {
            Text(
                "Cyan: valid for 14 days or more. Amber: fewer than 14 days, a name the certificate does not cover, or a node " +
                    "that did not answer (the last value stays, marked ?). Rose: no certificate, the default certificate, or fewer than 7 days."
            )
        }
    }
}

@Composable
private fun Orders(rows: List<OrderRowModel>, onOpen: (Location) -> Unit) {
    Figure(
        title = "Orders",
        flush = true,
        actions = { Span({ classes("caption", "muted") }) { Text("last 20 kept") } },
    ) {
        if (rows.isEmpty()) {
            P({ classes("figure-note") }) { Text("No orders yet. The first pass with a node orders the certificate.") }
        }
        Ul({ classes("cert-orders") }) {
            rows.forEach { row ->
                key(row.location.path) {
                    Li {
                        NavLink(
                            href = HashRouter.href(row.location),
                            onNavigate = { onOpen(row.location) },
                            classNames = listOfNotNull("cert-order", "selected".takeIf { row.selected }),
                        ) {
                            Span({ classes("mono", "muted", "cert-order-at") }) { Text(row.at) }
                            Span({ classes("cert-order-what") }) {
                                Text(row.what)
                                Span({ classes("small", "block", row.noteTone.className) }) { Text(row.note) }
                            }
                            Span({ classes("mono", "cert-order-result", row.tone.className) }) { Text(row.result) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Policy(policy: PolicyModel) {
    Figure(
        title = "Who can issue",
        actions = { Span({ classes("caption", "muted") }) { Text(policy.caption) } },
    ) {
        Ul({ classes("cert-caa", "mono") }) {
            policy.records.forEach { record ->
                Li {
                    Span {
                        Span({ classes("muted") }) { Text("CAA ") }
                        Text(record.value)
                    }
                    Span({ classes(record.tone.className) }) { Text(record.state) }
                }
            }
        }
        policy.warning?.let { warning ->
            P({ classes("cert-warning") }) { Text(warning) }
        }
        Dl({ classes("fact-list") }) {
            policy.facts.forEach { fact ->
                Div {
                    Dt { Text(fact.label) }
                    Dd({ classes("mono") }) { Text(fact.value) }
                }
            }
        }
        P({ classes("small", "muted") }) { Text(policy.note) }
    }
}

private val DELIVERY_HEADERS = listOf("Node", "Installed", "Installed at", "Management name", "State", "")

/** The class that colors a [Tone]. */
val Tone.className: String
    get() = when (this) {
        Tone.Ok -> "tone-ok"
        Tone.Warn -> "tone-warn"
        Tone.Down -> "tone-down"
        Tone.Muted -> "tone-unknown"
        Tone.Accent -> "tone-accent"
    }
