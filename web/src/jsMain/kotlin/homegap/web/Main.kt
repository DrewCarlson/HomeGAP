package homegap.web

import androidx.compose.runtime.*
import homegap.api.*
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.renderComposable
import kotlin.js.Date

private val scope = MainScope()

enum class Tab(val title: String, val subtitle: String) {
    OVERVIEW("Overview", "Health of nodes, tunnels, services and DNS at a glance"),
    NODES("Nodes", "Regional edge nodes provisioned over SSH"),
    SERVICES("Services", "Containers published through homegap.* labels"),
    DNS("DNS", "Bunny DNS records managed by HomeGAP"),
    TUNNELS("Tunnels", "Newt connectors from the homelab to each node"),
    SPEEDTEST("Speed test", "Latency and throughput through the real traffic path"),
    LOG("Activity", "Reconcile log, newest first"),
    CONFIG("Config", "Desired vs. actual state, secrets and the config file"),
}

class AppState {
    var status by mutableStateOf<StatusSnapshot?>(null)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<Toast?>(null)
    var tab by mutableStateOf(tabFromHash())
    var busy by mutableStateOf(false)

    data class Toast(val text: String, val error: Boolean)

    fun notify(msg: String, error: Boolean = false) {
        val t = Toast(msg, error)
        toast = t
        scope.launch { delay(5000); if (toast == t) toast = null }
    }

    fun run(label: String, block: suspend () -> ActionResponse) {
        scope.launch {
            busy = true
            try {
                notify(block().message)
                refresh()
            } catch (e: Throwable) {
                notify("$label failed: ${e.message}", error = true)
            } finally {
                busy = false
            }
        }
    }

    suspend fun refresh() {
        try {
            status = Api.status()
            error = null
        } catch (e: Api.ApiError) {
            error = if (e.status == 401) "Enter the dashboard token to continue." else e.message
        } catch (e: Throwable) {
            error = "Controller unreachable: ${e.message}"
        }
    }
}

private fun tabFromHash(): Tab = Tab.entries.firstOrNull { "#" + it.name.lowercase() == window.location.hash } ?: Tab.OVERVIEW

fun main() {
    val app = AppState()
    window.onhashchange = { app.tab = tabFromHash(); window.scrollTo(0.0, 0.0); null }
    scope.launch {
        while (true) {
            app.refresh()
            delay(5000)
        }
    }
    renderComposable(rootElementId = "root") { App(app) }
}

// ------------------------------------------------------------------------------------- shell

/** Tabs that currently need attention (shown as a dot in the navigation). */
private fun attention(s: StatusSnapshot?): Set<Tab> {
    if (s == null) return emptySet()
    return buildSet {
        if (s.nodes.any { it.health == Health.DOWN || it.lastProvisionOk == false }) add(Tab.NODES)
        if (s.services.any { svc -> svc.perNode.any { it.health == Health.DOWN } } || s.labelErrors.isNotEmpty()) add(Tab.SERVICES)
        if (s.dns.any { !it.inSync || it.monitorStatus == "offline" }) add(Tab.DNS)
        if (s.tunnels.any { !it.up }) add(Tab.TUNNELS)
    }
}

@Composable
fun NavLinks(app: AppState, cls: String) {
    val warn = attention(app.status)
    Nav({ classes(cls) }) {
        Tab.entries.forEach { t ->
            A(href = "#${t.name.lowercase()}", { if (t == app.tab) classes("active") }) {
                Span { Text(t.title) }
                if (t in warn) Span({ classes("dot"); title("Needs attention") })
            }
        }
    }
}

@Composable
fun Brand() {
    Div({ classes("brand") }) {
        Div({ classes("brand-mark") }) { Text("G") }
        Div {
            Div({ classes("brand-name") }) { Text("HomeGAP") }
            Div({ classes("brand-sub") }) { Text("Global Access Platform") }
        }
    }
}

@Composable
fun App(app: AppState) {
    val s = app.status
    // Keep the active tab visible in the horizontally scrolling mobile tab strip.
    LaunchedEffect(app.tab) {
        delay(30)
        kotlinx.browser.document.querySelector(".tabs a.active")
            ?.asDynamic()?.scrollIntoView(js("({ inline: 'center', block: 'nearest' })"))
    }
    Div({ classes("shell") }) {
        Aside({ classes("sidebar") }) {
            Brand()
            NavLinks(app, "side-nav")
            Div({ classes("sidebar-foot") }) {
                if (s != null) {
                    Div({ classes("mono") }) { Text(s.domain) }
                    Div { Text("${s.project} · v${s.version}") }
                }
            }
        }
        Div({ classes("main-col") }) {
            Header({ classes("topbar") }) {
                Div({ classes("topbar-brand") }) { Brand() }
                Div({ classes("page-head") }) {
                    H1 { Text(app.tab.title) }
                    P({ classes("page-sub") }) { Text(app.tab.subtitle) }
                }
                if (s != null) {
                    Div({ classes("topbar-actions") }) {
                        PassStatus(s)
                        Button({
                            classes("btn", "primary")
                            if (app.busy) disabled()
                            onClick { app.run("Resync") { Api.resync() } }
                        }) { Text("Resync") }
                        Button({
                            classes("btn")
                            title(if (s.paused) "Resume reconciliation everywhere" else "Stop all changes; observe only")
                            onClick { app.run("Pause") { Api.pause("global", null, !s.paused) } }
                        }) { Text(if (s.paused) "Resume" else "Pause") }
                    }
                }
            }
            NavLinks(app, "tabs")
            Main({ classes("content") }) {
                app.error?.let {
                    Banner("error", it)
                    TokenBox(app)
                }
                if (s == null) {
                    if (app.error == null) Div({ classes("loading") }) { Text("Loading…") }
                    return@Main
                }
                if (s.dryRun) Banner("warn", "Dry-run mode: discovery and planning only. Nothing is written to nodes, Pangolin or DNS.")
                if (s.paused) Banner("warn", "Reconciliation is paused globally. Manual changes on nodes, Pangolin and Bunny are left alone.")
                when (app.tab) {
                    Tab.OVERVIEW -> Overview(app, s)
                    Tab.NODES -> NodesView(app, s)
                    Tab.SERVICES -> ServicesView(app, s)
                    Tab.DNS -> DnsView(s)
                    Tab.TUNNELS -> TunnelsView(s)
                    Tab.SPEEDTEST -> SpeedTestView(app)
                    Tab.LOG -> LogView()
                    Tab.CONFIG -> ConfigView()
                }
            }
        }
    }
    app.toast?.let { t -> Div({ classes("toast"); if (t.error) classes("toast-error") }) { Text(t.text) } }
}

@Composable
fun PassStatus(s: StatusSnapshot) {
    val r = s.lastReconcile
    val health = when {
        s.reconciling -> Health.UNKNOWN
        r == null -> Health.UNKNOWN
        r.errors > 0 -> Health.DEGRADED
        else -> Health.OK
    }
    Span({
        classes("pass-status")
        r?.let { title("Last pass ${ago(it.finishedAt)} · trigger ${it.trigger} · ${it.changes} changes · ${it.errors} errors") }
    }) {
        Span({ classes("status-dot", health.name) })
        Text(
            when {
                s.reconciling -> "Reconciling…"
                r == null -> "Waiting for first pass"
                else -> "Synced ${ago(r.finishedAt)}" + if (r.errors > 0) " · ${r.errors} error${if (r.errors == 1) "" else "s"}" else ""
            }
        )
    }
}

@Composable
fun Banner(kind: String, text: String) {
    Div({ classes("banner", kind) }) { Text(text) }
}

@Composable
fun TokenBox(app: AppState) {
    var v by remember { mutableStateOf(Api.token ?: "") }
    Section({ classes("panel") }) {
        Div({ classes("panel-body", "token-box") }) {
            Label {
                Text("Dashboard token")
                Input(InputType.Password) { value(v); placeholder("Paste HOMEGAP_DASHBOARD_TOKEN"); onInput { v = it.value } }
            }
            Button({ classes("btn", "primary"); onClick { Api.token = v; scope.launch { app.refresh() } } }) { Text("Save") }
        }
    }
}

// ----------------------------------------------------------------------------- building blocks

@Composable
fun Pill(health: Health, text: String = health.label()) {
    Span({ classes("pill", health.name) }) { Text(text) }
}

private fun Health.label() = when (this) {
    Health.OK -> "healthy"; Health.DEGRADED -> "degraded"; Health.DOWN -> "down"; Health.UNKNOWN -> "unknown"
}

@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    flush: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Section({ classes("panel") }) {
        Div({ classes("panel-head") }) {
            Div({ classes("panel-titles") }) {
                H2 { Text(title) }
                subtitle?.let { P({ classes("panel-sub") }) { Text(it) } }
            }
            Div({ classes("panel-actions") }) { actions() }
        }
        Div({ classes(if (flush) "panel-flush" else "panel-body") }) { content() }
    }
}

/** A table cell that turns into a labelled row on narrow screens. */
@Composable
fun Cell(label: String, vararg cls: String, content: @Composable () -> Unit) {
    Td({
        attr("data-label", label)
        if (cls.isNotEmpty()) classes(*cls)
    }) { Div({ classes("cell") }) { content() } }
}

@Composable
fun DataTable(headers: List<String>, extraClass: String? = null, rows: @Composable () -> Unit) {
    Table({ classes("data"); extraClass?.let { classes(it) } }) {
        Thead { Tr { headers.forEach { Th { Text(it) } } } }
        Tbody { rows() }
    }
}

@Composable
fun EmptyRow(cols: Int, text: String) {
    Tr({ classes("empty") }) { Td({ attr("colspan", cols.toString()) }) { Text(text) } }
}

@Composable
fun Muted(text: String, mono: Boolean = false) {
    Div({ classes("muted"); if (mono) classes("mono") }) { Text(text) }
}

@Composable
fun Tag(text: String) {
    Span({ classes("tag") }) { Text(text) }
}

@Composable
fun Stat(label: String, value: String, health: Health, hint: String? = null) {
    Div({ classes("stat", health.name) }) {
        Div({ classes("stat-label") }) { Text(label) }
        Div({ classes("stat-value") }) { Text(value) }
        hint?.let { Div({ classes("stat-hint") }) { Text(it) } }
    }
}

private fun ratioHealth(ok: Int, total: Int) = when {
    total == 0 -> Health.UNKNOWN
    ok == total -> Health.OK
    ok == 0 -> Health.DOWN
    else -> Health.DEGRADED
}

// ---------------------------------------------------------------------------------- overview

@Composable
fun Overview(app: AppState, s: StatusSnapshot) {
    val nodesOk = s.nodes.count { it.health == Health.OK }
    val tunnelsUp = s.tunnels.count { it.up }
    val dnsOk = s.dns.count { it.inSync }
    val svcOk = s.services.count { svc -> svc.perNode.isNotEmpty() && svc.perNode.all { it.health == Health.OK } }
    Div({ classes("stats") }) {
        Stat("Nodes healthy", "$nodesOk / ${s.nodes.size}", ratioHealth(nodesOk, s.nodes.size))
        Stat("Tunnels up", "$tunnelsUp / ${s.tunnels.size}", ratioHealth(tunnelsUp, s.tunnels.size))
        Stat("Services", "$svcOk / ${s.services.size}", ratioHealth(svcOk, s.services.size), "healthy on every node")
        Stat("DNS in sync", "$dnsOk / ${s.dns.size}", ratioHealth(dnsOk, s.dns.size))
        Stat("Label errors", s.labelErrors.size.toString(), if (s.labelErrors.isEmpty()) Health.OK else Health.DOWN)
    }
    if (s.labelErrors.isNotEmpty()) {
        Panel("Label errors", "Fix these labels; the affected containers are ignored until then") {
            DataTable(listOf("Container", "Problem")) {
                s.labelErrors.forEach { e -> Tr { Cell("Container", "mono") { Text(e.container) }; Cell("Problem") { Text(e.message) } } }
            }
        }
    }
    Panel("Nodes", actions = { A(href = "#nodes", { classes("link") }) { Text("Manage →") } }) { NodesTable(app, s, compact = true) }
    Panel("Services", actions = { A(href = "#services", { classes("link") }) { Text("All services →") } }) { ServicesTable(app, s, compact = true) }
}

// ------------------------------------------------------------------------------------- nodes

@Composable
fun NodesView(app: AppState, s: StatusSnapshot) {
    Panel("Nodes") { NodesTable(app, s, compact = false) }
    AddNodeForm(app)
}

@Composable
fun NodesTable(app: AppState, s: StatusSnapshot, compact: Boolean) {
    val headers = listOf("Node", "Health", "Checks", "Certificate", "Tunnel", "Last provision") + if (compact) emptyList() else listOf("Drift", "")
    DataTable(headers) {
        if (s.nodes.isEmpty()) EmptyRow(headers.size, "No nodes configured yet. Add one below or in the config file.")
        s.nodes.forEach { n ->
            Tr {
                Cell("Node", "primary-cell") {
                    Div({ classes("cell-title") }) {
                        B { Text(n.id) }
                        if (n.paused) Tag("paused")
                        if (n.pendingDecommission) Tag("pending decommission")
                    }
                    Muted(n.host, mono = true)
                    val meta = listOf(n.region, n.provider, n.facts["os"]).filter { !it.isNullOrBlank() }.joinToString(" · ")
                    if (meta.isNotEmpty()) Muted(meta)
                    if (!compact) n.facts["tcp_cc"]?.let { Muted("tcp: $it${n.facts["qdisc"]?.let { q -> " / $q" } ?: ""}") }
                    if (!compact) n.facts["edge_tcp"]?.takeIf { it != "unknown" }?.let { Muted("edge tcp: $it") }
                }
                Cell("Health") { Pill(n.health) }
                Cell("Checks") { ChecksSummary(n.checks) }
                Cell("Certificate") {
                    n.certificate?.let { c ->
                        Pill(c.health, c.daysRemaining?.let { "$it days left" } ?: "unknown")
                        Muted(c.subject, mono = true)
                    } ?: Span({ classes("muted") }) { Text("—") }
                }
                Cell("Tunnel") { Pill(if (n.tunnelUp) Health.OK else Health.DOWN, if (n.tunnelUp) "up" else "down") }
                Cell("Last provision") {
                    Div({ classes("inline-row") }) {
                        Span { Text(n.lastProvisionAt?.let { ago(it) } ?: "never") }
                        n.lastProvisionOk?.let { ok -> Pill(if (ok) Health.OK else Health.DOWN, if (ok) "ok" else "failed") }
                    }
                    n.lastProvisionError?.let { Div({ classes("error-text", "mono") }) { Text(it.take(300)) } }
                }
                if (!compact) {
                    Cell("Drift") {
                        if (n.drift.isEmpty()) Span({ classes("muted") }) { Text("none") } else n.drift.forEach { Div({ classes("mono") }) { Text(it) } }
                    }
                    Cell("Actions", "actions-cell") {
                        Div({ classes("row-actions") }) {
                            if (!n.pendingDecommission) {
                                Button({ classes("btn", "sm"); onClick { app.run("Re-provision") { Api.provision(n.id) } } }) { Text("Re-provision") }
                                Button({ classes("btn", "sm"); onClick { app.run("Pause") { Api.pause("node", n.id, !n.paused) } } }) { Text(if (n.paused) "Resume" else "Pause") }
                                Button({
                                    classes("btn", "sm", "danger")
                                    onClick { if (window.confirm("Remove node ${n.id} from the config? DNS drains first; the stack stays up until you confirm decommission.")) app.run("Remove") { Api.removeNode(n.id) } }
                                }) { Text("Remove") }
                            } else {
                                Button({
                                    classes("btn", "sm", "danger")
                                    onClick {
                                        val typed = window.prompt("Type the node id to stop its stack and forget it:")
                                        if (typed != null) app.run("Decommission") { Api.decommission(n.id, typed) }
                                    }
                                }) { Text("Confirm decommission") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Problems first, healthy checks folded into one counter (names in its tooltip). */
@Composable
fun ChecksSummary(checks: List<CheckDto>) {
    val bad = checks.filter { it.health != Health.OK }
    val good = checks.filter { it.health == Health.OK }
    Div({ classes("checks") }) {
        bad.forEach { c -> Span({ classes("pill", "sm", c.health.name); title(c.detail.ifEmpty { c.name }) }) { Text(c.name) } }
        if (good.isNotEmpty()) {
            Span({ classes("pill", "sm", "OK"); title(good.joinToString("\n") { it.name + if (it.detail.isNotEmpty()) " — " + it.detail else "" }) }) {
                Text(if (bad.isEmpty()) "all ${good.size} passing" else "${good.size} passing")
            }
        }
        if (checks.isEmpty()) Span({ classes("muted") }) { Text("—") }
    }
}

@Composable
fun AddNodeForm(app: AppState) {
    var id by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var os by remember { mutableStateOf("ubuntu-24.04") }
    var user by remember { mutableStateOf("deploy") }
    var key by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    Panel("Add node", "Adds an entry to the config file; the next pass provisions it over SSH and extends DNS.", flush = false) {
        Div({ classes("form-grid") }) {
            Field("Node id", id, "eu1") { id = it }
            Field("SSH host / IP", host, "203.0.113.30") { host = it }
            Field("Region code", region, "EU") { region = it }
            Field("Provider", provider, "optional") { provider = it }
            Field("OS", os, "ubuntu-24.04") { os = it }
            Field("SSH user", user, "deploy") { user = it }
            Field("SSH key file", key, "/secrets/ssh/eu1") { key = it }
            Field("Latitude", lat, "50.1") { lat = it }
            Field("Longitude", lon, "8.7") { lon = it }
        }
        Div({ classes("form-actions") }) {
            Button({
                classes("btn", "primary")
                if (app.busy || id.isBlank() || host.isBlank() || key.isBlank()) disabled()
                onClick {
                    app.run("Add node") {
                        Api.addNode(NodeConfigDto(
                            id = id.trim(), region = region.trim(), provider = provider.trim(), host = host.trim(), os = os.trim(),
                            sshUser = user.trim(), sshKeyFile = key.trim(), latitude = lat.toDoubleOrNull(), longitude = lon.toDoubleOrNull(),
                        ))
                    }
                }
            }) { Text("Add and provision") }
        }
    }
}

@Composable
fun Field(label: String, value: String, ph: String, onChange: (String) -> Unit) {
    Label {
        Text(label)
        Input(InputType.Text) { value(value); placeholder(ph); onInput { onChange(it.value) } }
    }
}

// ---------------------------------------------------------------------------------- services

@Composable
fun ServicesView(app: AppState, s: StatusSnapshot) {
    Panel("Services") { ServicesTable(app, s, compact = false) }
}

@Composable
fun ServicesTable(app: AppState, s: StatusSnapshot, compact: Boolean) {
    val headers = listOf("Hostname", "Kind", "Protocol / TLS", "Access", "Regions", "Health per node") + if (compact) emptyList() else listOf("")
    DataTable(headers) {
        if (s.services.isEmpty()) EmptyRow(headers.size, "No containers carry homegap.expose labels yet.")
        s.services.forEach { svc ->
            Tr {
                Cell("Hostname", "primary-cell") {
                    Div({ classes("cell-title") }) {
                        B({ classes("mono") }) { Text(svc.hostname) }
                        if (svc.paused) Tag("paused")
                    }
                    if (svc.wildcard) Muted("+ *.${svc.hostname}", mono = true)
                    Muted(svc.container)
                }
                Cell("Kind") { Tag(svc.kind) }
                Cell("Protocol / TLS") { Text("${svc.protocol} / ${svc.tls}") }
                Cell("Access") { Text(svc.access + if (svc.auth != "none") " · ${svc.auth}" else "") }
                Cell("Regions") { Div({ classes("checks") }) { svc.regions.forEach { Tag(it) } } }
                Cell("Health per node") {
                    Div({ classes("checks") }) {
                        svc.perNode.forEach { h -> Span({ classes("pill", "sm", h.health.name); title(h.detail) }) { Text(h.nodeId) } }
                    }
                }
                if (!compact) Cell("Actions", "actions-cell") {
                    Button({ classes("btn", "sm"); onClick { app.run("Pause") { Api.pause("service", svc.key, !svc.paused) } } }) {
                        Text(if (svc.paused) "Resume" else "Pause")
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------- dns

@Composable
fun DnsView(s: StatusSnapshot) {
    Panel("Records", "Geo records drop offline members automatically; plain records always answer") {
        val headers = listOf("Name", "Type", "Value", "Node", "Routing", "Monitor", "State")
        DataTable(headers) {
            if (s.dns.isEmpty()) EmptyRow(headers.size, "No DNS observations yet.")
            s.dns.forEach { r ->
                Tr {
                    Cell("Name", "mono", "primary-cell") { Text(r.name) }
                    Cell("Type") { Tag(r.type) }
                    Cell("Value", "mono") { Text(r.value) }
                    Cell("Node") { Text(r.nodeId ?: "—") }
                    Cell("Routing", "muted") { Text(r.geo ?: "plain") }
                    Cell("Monitor") {
                        val h = when (r.monitorStatus) { "online" -> Health.OK; "offline" -> Health.DOWN; else -> Health.UNKNOWN }
                        if (r.monitor == "none") Span({ classes("muted") }) { Text("none") } else Pill(h, "${r.monitor} ${r.monitorStatus}")
                    }
                    Cell("State") { if (r.inSync) Pill(Health.OK, "in sync") else Pill(Health.DEGRADED, r.diff ?: "differs") }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------- tunnels

@Composable
fun TunnelsView(s: StatusSnapshot) {
    Panel("Newt connectors", "Run by the tunnels container; independent of the orchestrator") {
        val headers = listOf("Node", "Endpoint", "Site", "Process", "Restarts", "Traffic in / out")
        DataTable(headers) {
            if (s.tunnels.isEmpty()) EmptyRow(headers.size, "No tunnels yet.")
            s.tunnels.forEach { t ->
                Tr {
                    Cell("Node", "primary-cell") { B { Text(t.nodeId) } }
                    Cell("Endpoint", "mono") { Text(t.endpoint) }
                    Cell("Site") {
                        Pill(when (t.siteOnline) { true -> Health.OK; false -> Health.DOWN; null -> Health.UNKNOWN }, t.siteOnline?.let { if (it) "online" else "offline" } ?: "unknown")
                    }
                    Cell("Process") {
                        Pill(if (t.processRunning) Health.OK else Health.DOWN, if (t.processRunning) "running" else "stopped")
                        t.lastExit?.let { Muted("last exit: $it") }
                    }
                    Cell("Restarts") { Text(t.restarts.toString()) }
                    Cell("Traffic in / out", "mono") { Text(if (t.bytesIn == null) "—" else "${bytes(t.bytesIn!!)} / ${bytes(t.bytesOut ?: 0)}") }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------- speed test

@Composable
fun SpeedTestView(app: AppState) {
    var ov by remember { mutableStateOf<SpeedTestOverview?>(null) }
    var link by remember { mutableStateOf<SpeedTestLinkDto?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var streams by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(reload) {
        while (true) {
            ov = runCatching { Api.speedtest() }.getOrDefault(ov)
            delay(10000)
        }
    }
    val o = ov ?: run { Div({ classes("loading") }) { Text("Loading…") }; return }
    if (!o.enabled) {
        Banner(
            "info",
            "Speed tests are disabled. Set speedtest.enabled: true in the config, route the speedtest hostnames to the orchestrator's " +
                "speed test port in your homelab proxy, and restart the orchestrator.",
        )
        return
    }
    val latest = o.nodes.associateWith { id -> o.results.firstOrNull { it.kind == "tunnel" && it.nodeId == id } }
    val n = streams ?: o.streams
    Div({ classes("two-col") }) {
        Panel("Homelab ↔ node", "curl on the node → its own :443 → WireGuard tunnel → homelab proxy", flush = false) {
            Div({ classes("stream-picker") }) {
                Span({ classes("muted") }) { Text("Parallel streams") }
                Div({ classes("segmented") }) { listOf(1, 4, 8).forEach { c ->
                    Button({
                        if (c == n) classes("active")
                        if (running != null) disabled()
                        onClick { streams = c }
                    }) { Text("$c") }
                } }
            }
            Div({ classes("node-tests") }) {
                o.nodes.forEach { id ->
                    val r = latest[id]
                    Div({ classes("node-test") }) {
                        Div({ classes("node-test-head") }) {
                            B { Text(id) }
                            Button({
                                classes("btn", "sm")
                                if (running != null) disabled()
                                onClick {
                                    running = id
                                    scope.launch {
                                        try {
                                            val res = Api.runTunnelTest(id, n)
                                            if (res.error != null) app.notify("$id: ${res.error}", error = true)
                                            else app.notify("$id: ${mbps(res.downMbps)} down, ${mbps(res.upMbps)} up, ${ms(res.pingMs)} ping")
                                        } catch (e: Throwable) {
                                            app.notify("Speed test on $id failed: ${e.message}", error = true)
                                        } finally {
                                            running = null
                                            reload++
                                        }
                                    }
                                }
                            }) { Text(if (running == id) "Testing…" else "Run test") }
                        }
                        if (r == null) Muted("No result yet")
                        else Div({ classes("metrics") }) {
                            Metric("Ping", ms(r.pingMs)); Metric("Jitter", ms(r.jitterMs)); Metric("Down", mbps(r.downMbps)); Metric("Up", mbps(r.upMbps))
                        }
                        r?.let { Muted("${ago(it.at)}${streamsNote(it)}${it.error?.let { e -> " · $e" } ?: ""}") }
                    }
                }
            }
        }
        Panel("Your device → node → homelab", "Open a link on the device you want to test", flush = false) {
            P({ classes("muted") }) {
                Text("The auto link lets DNS pick the node for the device's location; that node redirects to its own hostname so the result is attributed to it. Anyone holding a link can run tests until it expires.")
            }
            Button({
                classes("btn", "primary")
                onClick { scope.launch { link = runCatching { Api.speedLink() }.onFailure { app.notify("Link failed: ${it.message}", error = true) }.getOrNull() } }
            }) { Text(if (link == null) "Create test link" else "Create new link") }
            link?.let { l ->
                Div({ classes("links") }) {
                    LinkRow("Auto (DNS)", l.autoUrl)
                    l.nodeUrls.forEach { (id, url) -> LinkRow(id, url) }
                    Muted("Expires ${Date(l.expiresAt.toDouble()).toLocaleString()}")
                }
            }
        }
    }
    Panel("History") {
        val headers = listOf("When", "Path", "Node", "Ping", "Jitter", "Down", "Up", "Notes")
        DataTable(headers) {
            if (o.results.isEmpty()) EmptyRow(headers.size, "No results yet.")
            o.results.forEach { r ->
                Tr {
                    Cell("When", "nowrap") { Text(ago(r.at)) }
                    Cell("Path") { Tag(if (r.kind == "tunnel") "homelab ↔ node" else "device"); if ((r.streams ?: 1) > 1) Muted("${r.streams} streams") }
                    Cell("Node") { Text((r.nodeId ?: "unknown") + if (r.auto) " (auto)" else "") }
                    Cell("Ping", "mono") { Text(ms(r.pingMs)) }
                    Cell("Jitter", "mono") { Text(ms(r.jitterMs)) }
                    Cell("Down", "mono") { Text(mbps(r.downMbps)) }
                    Cell("Up", "mono") { Text(mbps(r.upMbps)) }
                    Cell("Notes") {
                        r.error?.let { Div({ classes("error-text") }) { Text(it) } }
                        r.client?.let { Muted(shortClient(it)) }
                    }
                }
            }
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Div({ classes("metric") }) {
        Div({ classes("metric-label") }) { Text(label) }
        Div({ classes("metric-value") }) { Text(value) }
    }
}

@Composable
fun LinkRow(label: String, url: String) {
    Div({ classes("link-row") }) {
        Span({ classes("link-label") }) { Text(label) }
        A(href = url, { classes("mono"); attr("target", "_blank"); attr("rel", "noopener") }) { Text(url.substringBefore("#")) }
        Button({
            classes("btn", "sm")
            onClick { window.navigator.clipboard.writeText(url) }
        }) { Text("Copy") }
    }
}

/** Condenses a browser user agent to something readable. */
private fun shortClient(ua: String): String {
    if (ua.startsWith("node ")) return ua
    val os = listOf("iPhone", "iPad", "Android", "Mac OS X", "Windows", "Linux").firstOrNull { it in ua }?.let { if (it == "Mac OS X") "macOS" else it }
    val browser = listOf("Firefox", "Edg", "Chrome", "Safari").firstOrNull { it in ua }?.let { if (it == "Edg") "Edge" else it }
    return listOfNotNull(browser, os).joinToString(" on ").ifEmpty { ua.take(60) }
}

/** " · 4 streams (down 62/60/58/61)" for multi-stream tunnel results. */
private fun streamsNote(r: SpeedTestResultDto): String {
    val n = r.streams ?: return ""
    if (n <= 1) return " · 1 stream"
    val per = r.downStreamsMbps.takeIf { it.size > 1 }?.joinToString("/") { it.fmt() }?.let { " (down $it)" } ?: ""
    return " · $n streams$per"
}

private fun ms(v: Double?): String = v?.let { "${it.fmt()} ms" } ?: "—"
private fun mbps(v: Double?): String = v?.let { if (it >= 100) "${it.toInt()} Mbps" else "${it.fmt()} Mbps" } ?: "—"

// ---------------------------------------------------------------------------------- activity

@Composable
fun LogView() {
    var domain by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<LogEntryDto>>(emptyList()) }
    LaunchedEffect(domain) {
        while (true) {
            entries = runCatching { Api.log(domain).entries }.getOrDefault(entries)
            delay(4000)
        }
    }
    Div({ classes("segmented") }) {
        listOf(null, "discovery", "nodes", "tunnels", "resources", "dns", "private", "observe", "speedtest", "controller").forEach { d ->
            Button({ if (d == domain) classes("active"); onClick { domain = d } }) { Text(d ?: "all") }
        }
    }
    Section({ classes("panel") }) {
        Div({ classes("log") }) {
            if (entries.isEmpty()) Div({ classes("empty-state") }) { Text("Nothing logged yet.") }
            entries.forEach { e ->
                Div({ classes("log-row", e.level.name) }) {
                    Span({ classes("log-time", "mono") }) { Text(Date(e.time.toDouble()).toLocaleTimeString()) }
                    Span({ classes("log-level") }) { Text(e.level.name.lowercase()) }
                    Span({ classes("tag") }) { Text(e.domain) }
                    e.target?.let { Span({ classes("log-target") }) { Text(it) } }
                    Span({ classes("log-msg") }) { Text(e.message) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ config

@Composable
fun ConfigView() {
    var diff by remember { mutableStateOf<DiffResponse?>(null) }
    var secrets by remember { mutableStateOf<SecretsResponse?>(null) }
    var cfg by remember { mutableStateOf<ConfigResponse?>(null) }
    var onlyChanges by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        diff = runCatching { Api.diff() }.getOrNull()
        secrets = runCatching { Api.secrets() }.getOrNull()
        cfg = runCatching { Api.config() }.getOrNull()
    }
    Panel("Desired vs. actual", "From the last reconcile pass", actions = {
        Label(attrs = { classes("check") }) {
            CheckboxInput(onlyChanges) { onChange { onlyChanges = it.value } }
            Text("Only differences")
        }
    }) {
        val items = diff?.items?.filter { !onlyChanges || it.action != "none" } ?: emptyList()
        val headers = listOf("Domain", "Target", "Key", "Action", "Desired", "Actual")
        DataTable(headers) {
            if (items.isEmpty()) EmptyRow(headers.size, "Everything matches the desired state.")
            items.forEach { d ->
                Tr {
                    Cell("Domain") { Tag(d.domain) }
                    Cell("Target") { Text(d.target) }
                    Cell("Key", "mono", "primary-cell") { Text(d.key) }
                    Cell("Action") { Pill(if (d.action == "none") Health.OK else Health.DEGRADED, d.action) }
                    Cell("Desired", "mono") { Text(d.desired ?: "") }
                    Cell("Actual", "mono") { Text(d.actual ?: "") }
                }
            }
        }
    }
    Panel("Referenced secrets", "Names and sources only; values are never shown") {
        val headers = listOf("Secret", "Used by", "Source", "Present")
        DataTable(headers) {
            secrets?.secrets?.forEach { r ->
                Tr {
                    Cell("Secret", "mono", "primary-cell") { Text(r.name) }
                    Cell("Used by") { Text(r.usedBy) }
                    Cell("Source", "mono") { Text(r.source) }
                    Cell("Present") { Pill(if (r.present) Health.OK else Health.DOWN, if (r.present) "present" else "missing") }
                }
            }
        }
    }
    Panel("Effective desired state") { Pre { Text(diff?.desiredState ?: "") } }
    Panel("Config file", cfg?.path) { Pre { Text(cfg?.yaml ?: "") } }
}

// ----------------------------------------------------------------------------------- helpers

private fun ago(ms: Long): String {
    val s = ((Date.now() - ms) / 1000).toLong()
    return when {
        s < 5 -> "just now"
        s < 90 -> "${s}s ago"
        s < 5400 -> "${s / 60}m ago"
        s < 172800 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

private fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${(n / 1024.0).fmt()} KiB"
    n < 1024L * 1024 * 1024 -> "${(n / 1048576.0).fmt()} MiB"
    else -> "${(n / 1073741824.0).fmt()} GiB"
}

private fun Double.fmt(): String = asDynamic().toFixed(1) as String
