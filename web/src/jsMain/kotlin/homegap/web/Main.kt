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

enum class Tab(val title: String) { OVERVIEW("Overview"), NODES("Nodes"), SERVICES("Services"), DNS("DNS"), TUNNELS("Tunnels"), LOG("Reconcile log"), CONFIG("Config & diff") }

class AppState {
    var status by mutableStateOf<StatusSnapshot?>(null)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var tab by mutableStateOf(tabFromHash())
    var busy by mutableStateOf(false)

    fun notify(msg: String) {
        toast = msg
        scope.launch { delay(4500); if (toast == msg) toast = null }
    }

    fun run(label: String, block: suspend () -> ActionResponse) {
        scope.launch {
            busy = true
            try {
                notify(block().message)
                refresh()
            } catch (e: Throwable) {
                notify("$label failed: ${e.message}")
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
            error = if (e.status == 401) "Unauthorized: set the dashboard token below." else e.message
        } catch (e: Throwable) {
            error = "Controller unreachable: ${e.message}"
        }
    }
}

private fun tabFromHash(): Tab = Tab.entries.firstOrNull { "#" + it.name.lowercase() == window.location.hash } ?: Tab.OVERVIEW

fun main() {
    val app = AppState()
    window.onhashchange = { app.tab = tabFromHash(); null }
    scope.launch {
        while (true) {
            app.refresh()
            delay(5000)
        }
    }
    renderComposable(rootElementId = "root") { App(app) }
}

@Composable
fun App(app: AppState) {
    val s = app.status
    Header({}) {
        H1 { Text("HomeGAP") }
        if (s != null) Span({ classes("meta") }) { Text("${s.project} · ${s.domain} · v${s.version}") }
        Nav {
            Tab.entries.forEach { t ->
                A(href = "#${t.name.lowercase()}", { if (t == app.tab) classes("active") }) { Text(t.title) }
            }
        }
        Div({ classes("spacer") })
        if (s != null) {
            s.lastReconcile?.let { r ->
                Span({ classes("meta") }) {
                    Text(if (s.reconciling) "reconciling… " else "last pass ${ago(r.finishedAt)} (${r.trigger}) · ${r.changes} changes · ${r.errors} errors")
                }
            }
            Button({
                classes("primary")
                if (app.busy) disabled()
                onClick { app.run("Resync") { Api.resync() } }
            }) { Text("Resync now") }
            Button({ onClick { app.run("Pause") { Api.pause("global", null, !s.paused) } } }) {
                Text(if (s.paused) "Resume all" else "Pause all")
            }
        }
    }
    Main {
        app.error?.let { Div({ classes("banner", "error") }) { Text(it) }; TokenBox(app) }
        if (s == null) {
            if (app.error == null) P { Text("Loading…") }
            return@Main
        }
        if (s.dryRun) Div({ classes("banner", "warn") }) { Text("Dry-run mode: discovery and planning only, nothing is written.") }
        if (s.paused) Div({ classes("banner", "warn") }) { Text("Reconciliation is paused globally. Manual changes on nodes, Pangolin and Bunny are left alone.") }
        when (app.tab) {
            Tab.OVERVIEW -> Overview(app, s)
            Tab.NODES -> NodesView(app, s)
            Tab.SERVICES -> ServicesView(app, s)
            Tab.DNS -> DnsView(s)
            Tab.TUNNELS -> TunnelsView(s)
            Tab.LOG -> LogView()
            Tab.CONFIG -> ConfigView(app)
        }
    }
    app.toast?.let { Div({ classes("toast") }) { Text(it) } }
}

@Composable
fun TokenBox(app: AppState) {
    var v by remember { mutableStateOf(Api.token ?: "") }
    Div({ classes("filters") }) {
        Input(InputType.Password) { value(v); placeholder("dashboard token"); onInput { v = it.value } }
        Button({ onClick { Api.token = v; scope.launch { app.refresh() } } }) { Text("Save token") }
    }
}

@Composable
fun Pill(health: Health, text: String = health.name.lowercase()) {
    Span({ classes("pill", health.name) }) { Text(text) }
}

@Composable
fun Panel(title: String, extra: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Section {
        H2 { Text(title); extra() }
        content()
    }
}

@Composable
fun Overview(app: AppState, s: StatusSnapshot) {
    Div({ classes("cards") }) {
        Card("Nodes healthy", "${s.nodes.count { it.health == Health.OK }}/${s.nodes.size}")
        Card("Tunnels up", "${s.tunnels.count { it.up }}/${s.tunnels.size}")
        Card("Services", s.services.size.toString())
        Card("DNS records in sync", "${s.dns.count { it.inSync }}/${s.dns.size}")
        Card("Label errors", s.labelErrors.size.toString())
    }
    Br()
    if (s.labelErrors.isNotEmpty()) {
        Panel("Label errors") {
            Table {
                Tbody { s.labelErrors.forEach { e -> Tr { Td({ classes("mono") }) { Text(e.container) }; Td { Text(e.message) } } } }
            }
        }
    }
    Panel("Nodes") { NodesTable(app, s, compact = true) }
    Panel("Services") { ServicesTable(app, s, compact = true) }
}

@Composable
fun Card(label: String, value: String) {
    Div({ classes("card") }) {
        Div({ classes("n") }) { Text(value) }
        Div({ classes("l") }) { Text(label) }
    }
}

@Composable
fun NodesView(app: AppState, s: StatusSnapshot) {
    Panel("Nodes") { NodesTable(app, s, compact = false) }
    AddNodeForm(app)
}

@Composable
fun NodesTable(app: AppState, s: StatusSnapshot, compact: Boolean) {
    Table {
        Thead {
            Tr {
                Th { Text("Node") }; Th { Text("Health") }; Th { Text("Checks") }; Th { Text("Certificate") }
                Th { Text("Tunnel") }; Th { Text("Last provision") }
                if (!compact) { Th { Text("Drift") }; Th { Text("Actions") } }
            }
        }
        Tbody {
            s.nodes.forEach { n ->
                Tr {
                    Td {
                        B { Text(n.id) }
                        Div({ classes("muted", "mono") }) { Text(n.host) }
                        Div({ classes("muted") }) { Text(listOf(n.region, n.provider, n.facts["os"]).filter { !it.isNullOrBlank() }.joinToString(" · ")) }
                        if (n.paused) Span({ classes("tag") }) { Text("paused") }
                        if (n.pendingDecommission) Span({ classes("tag") }) { Text("pending decommission") }
                    }
                    Td { Pill(n.health) }
                    Td { Div({ classes("checks") }) { n.checks.forEach { c -> Span({ classes("pill", c.health.name); title(c.detail) }) { Text(c.name) } } } }
                    Td {
                        n.certificate?.let { c ->
                            Pill(c.health, c.daysRemaining?.let { "$it days" } ?: "unknown")
                            Div({ classes("muted", "mono") }) { Text(c.subject) }
                        } ?: Span({ classes("muted") }) { Text("—") }
                    }
                    Td { Pill(if (n.tunnelUp) Health.OK else Health.DOWN, if (n.tunnelUp) "up" else "down") }
                    Td {
                        Text(n.lastProvisionAt?.let { ago(it) } ?: "never")
                        n.lastProvisionOk?.let { ok -> Text(" "); Pill(if (ok) Health.OK else Health.DOWN, if (ok) "ok" else "failed") }
                        n.lastProvisionError?.let { Div({ classes("muted", "mono") }) { Text(it.take(300)) } }
                    }
                    if (!compact) {
                        Td { if (n.drift.isEmpty()) Span({ classes("muted") }) { Text("none") } else n.drift.forEach { Div({ classes("mono") }) { Text(it) } } }
                        Td {
                            Div({ classes("row-actions") }) {
                                if (!n.pendingDecommission) {
                                    Button({ onClick { app.run("Re-provision") { Api.provision(n.id) } } }) { Text("Re-provision") }
                                    Button({ onClick { app.run("Pause") { Api.pause("node", n.id, !n.paused) } } }) { Text(if (n.paused) "Resume" else "Pause") }
                                    Button({
                                        classes("danger")
                                        onClick { if (window.confirm("Remove node ${n.id} from the config? DNS drains first; the stack stays up until you confirm decommission.")) app.run("Remove") { Api.removeNode(n.id) } }
                                    }) { Text("Remove") }
                                } else {
                                    Button({
                                        classes("danger")
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
    Panel("Add node") {
        Div({ classes("body") }) {
            P({ classes("muted") }) { Text("Adds an entry to the controller config file; the next pass provisions it over SSH and extends DNS.") }
            Div({ classes("inline") }) {
                Field("Node id", id, "na2") { id = it }
                Field("SSH host / IP", host, "203.0.113.30") { host = it }
                Field("Region code", region, "EU") { region = it }
                Field("Provider", provider, "any") { provider = it }
                Field("OS", os, "ubuntu-24.04") { os = it }
                Field("SSH user", user, "deploy") { user = it }
                Field("SSH key file", key, "/secrets/ssh/na2") { key = it }
                Field("Latitude", lat, "50.1") { lat = it }
                Field("Longitude", lon, "8.7") { lon = it }
            }
            Br()
            Button({
                classes("primary")
                onClick {
                    app.run("Add node") {
                        Api.addNode(NodeConfigDto(
                            id = id.trim(), region = region.trim(), provider = provider.trim(), host = host.trim(), os = os.trim(),
                            sshUser = user.trim(), sshKeyFile = key.trim(), latitude = lat.toDoubleOrNull(), longitude = lon.toDoubleOrNull(),
                        ))
                    }
                }
            }) { Text("Add and apply") }
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

@Composable
fun ServicesView(app: AppState, s: StatusSnapshot) {
    Panel("Services") { ServicesTable(app, s, compact = false) }
}

@Composable
fun ServicesTable(app: AppState, s: StatusSnapshot, compact: Boolean) {
    Table {
        Thead {
            Tr {
                Th { Text("Hostname") }; Th { Text("Kind") }; Th { Text("Protocol / TLS") }; Th { Text("Access") }
                Th { Text("Regions") }; Th { Text("Per-node health") }
                if (!compact) Th { Text("Actions") }
            }
        }
        Tbody {
            if (s.services.isEmpty()) Tr { Td({ attr("colspan", "7") }) { Span({ classes("muted") }) { Text("No containers carry homegap.expose labels.") } } }
            s.services.forEach { svc ->
                Tr {
                    Td {
                        B({ classes("mono") }) { Text(svc.hostname + if (svc.wildcard) "  (+ *.${svc.hostname})" else "") }
                        Div({ classes("muted") }) { Text(svc.container) }
                        if (svc.paused) Span({ classes("tag") }) { Text("paused") }
                    }
                    Td { Span({ classes("tag") }) { Text(svc.kind) } }
                    Td { Text("${svc.protocol} / ${svc.tls}") }
                    Td { Text(svc.access + if (svc.auth != "none") " · ${svc.auth}" else "") }
                    Td { svc.regions.forEach { Span({ classes("tag") }) { Text(it) } } }
                    Td { Div({ classes("checks") }) { svc.perNode.forEach { h -> Span({ classes("pill", h.health.name); title(h.detail) }) { Text(h.nodeId) } } } }
                    if (!compact) Td {
                        Button({ onClick { app.run("Pause") { Api.pause("service", svc.key, !svc.paused) } } }) { Text(if (svc.paused) "Resume" else "Pause") }
                    }
                }
            }
        }
    }
}

@Composable
fun DnsView(s: StatusSnapshot) {
    Panel("Bunny DNS records") {
        Table {
            Thead { Tr { Th { Text("Name") }; Th { Text("Type") }; Th { Text("Value") }; Th { Text("Node") }; Th { Text("Routing") }; Th { Text("Monitor") }; Th { Text("Desired") } } }
            Tbody {
                if (s.dns.isEmpty()) Tr { Td({ attr("colspan", "7") }) { Span({ classes("muted") }) { Text("No DNS observations yet.") } } }
                s.dns.forEach { r ->
                    Tr {
                        Td({ classes("mono") }) { Text(r.name) }
                        Td { Text(r.type) }
                        Td({ classes("mono") }) { Text(r.value) }
                        Td { Text(r.nodeId ?: "—") }
                        Td({ classes("muted") }) { Text(r.geo ?: "plain") }
                        Td {
                            val h = when (r.monitorStatus) { "online" -> Health.OK; "offline" -> Health.DOWN; else -> Health.UNKNOWN }
                            Pill(h, "${r.monitor}: ${r.monitorStatus}")
                        }
                        Td { if (r.inSync) Pill(Health.OK, "in sync") else Pill(Health.DEGRADED, r.diff ?: "differs") }
                    }
                }
            }
        }
    }
}

@Composable
fun TunnelsView(s: StatusSnapshot) {
    Panel("Newt connectors") {
        Table {
            Thead { Tr { Th { Text("Node") }; Th { Text("Endpoint") }; Th { Text("Site") }; Th { Text("Process") }; Th { Text("Restarts") }; Th { Text("Throughput (in / out)") } } }
            Tbody {
                s.tunnels.forEach { t ->
                    Tr {
                        Td { B { Text(t.nodeId) } }
                        Td({ classes("mono") }) { Text(t.endpoint) }
                        Td { Pill(when (t.siteOnline) { true -> Health.OK; false -> Health.DOWN; null -> Health.UNKNOWN }, t.siteOnline?.let { if (it) "online" else "offline" } ?: "unknown") }
                        Td { Pill(if (t.processRunning) Health.OK else Health.DOWN, if (t.processRunning) "running" else "stopped") ; t.lastExit?.let { Div({ classes("muted") }) { Text("last exit: $it") } } }
                        Td { Text(t.restarts.toString()) }
                        Td({ classes("mono") }) { Text(if (t.bytesIn == null) "—" else "${bytes(t.bytesIn!!)} / ${bytes(t.bytesOut ?: 0)}") }
                    }
                }
            }
        }
    }
}

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
    Panel("Reconcile log", extra = {
        Div({ classes("filters"); style { property("margin-left", "auto") } }) {
            listOf(null, "discovery", "nodes", "tunnels", "resources", "dns", "private", "observe", "controller").forEach { d ->
                Button({ if (d == domain) classes("primary"); onClick { domain = d } }) { Text(d ?: "all") }
            }
        }
    }) {
        Table({ classes("log") }) {
            Tbody {
                entries.forEach { e ->
                    Tr({ classes(e.level.name) }) {
                        Td({ classes("mono"); style { property("white-space", "nowrap") } }) { Text(Date(e.time.toDouble()).toLocaleTimeString()) }
                        Td { Span({ classes("tag") }) { Text(e.domain) } }
                        Td { Text(e.target ?: "") }
                        Td { Text(e.message) }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigView(app: AppState) {
    var diff by remember { mutableStateOf<DiffResponse?>(null) }
    var secrets by remember { mutableStateOf<SecretsResponse?>(null) }
    var cfg by remember { mutableStateOf<ConfigResponse?>(null) }
    var onlyChanges by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        diff = runCatching { Api.diff() }.getOrNull()
        secrets = runCatching { Api.secrets() }.getOrNull()
        cfg = runCatching { Api.config() }.getOrNull()
    }
    Panel("Desired vs actual (last pass)", extra = {
        Label(attrs = { style { property("margin-left", "auto"); property("flex-direction", "row") } }) {
            CheckboxInput(onlyChanges) { onChange { onlyChanges = it.value } }
            Text(" only differences")
        }
    }) {
        val items = diff?.items?.filter { !onlyChanges || it.action != "none" } ?: emptyList()
        Table {
            Thead { Tr { Th { Text("Domain") }; Th { Text("Target") }; Th { Text("Key") }; Th { Text("Action") }; Th { Text("Desired") }; Th { Text("Actual") } } }
            Tbody {
                if (items.isEmpty()) Tr { Td({ attr("colspan", "6") }) { Span({ classes("muted") }) { Text("Everything matches the desired state.") } } }
                items.forEach { d ->
                    Tr {
                        Td { Span({ classes("tag") }) { Text(d.domain) } }
                        Td { Text(d.target) }
                        Td({ classes("mono") }) { Text(d.key) }
                        Td { Pill(if (d.action == "none") Health.OK else Health.DEGRADED, d.action) }
                        Td({ classes("mono") }) { Text(d.desired ?: "") }
                        Td({ classes("mono") }) { Text(d.actual ?: "") }
                    }
                }
            }
        }
    }
    Panel("Referenced secrets (values never shown)") {
        Table {
            Thead { Tr { Th { Text("Secret") }; Th { Text("Used by") }; Th { Text("Source") }; Th { Text("Present") } } }
            Tbody {
                secrets?.secrets?.forEach { r ->
                    Tr {
                        Td({ classes("mono") }) { Text(r.name) }
                        Td { Text(r.usedBy) }
                        Td({ classes("mono") }) { Text(r.source) }
                        Td { Pill(if (r.present) Health.OK else Health.DOWN, if (r.present) "present" else "missing") }
                    }
                }
            }
        }
    }
    Panel("Effective desired state") { Pre { Text(diff?.desiredState ?: "") } }
    Panel("Config file" + (cfg?.path?.let { " ($it)" } ?: "")) { Pre { Text(cfg?.yaml ?: "") } }
}

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
