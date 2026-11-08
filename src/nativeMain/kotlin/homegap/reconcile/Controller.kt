package homegap.reconcile

import homegap.VERSION
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.api.*
import homegap.di.ExternalHttp
import homegap.di.LocalHttp
import homegap.di.RunOptions
import homegap.clients.*
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.discovery.DockerClient
import homegap.discovery.DockerContainerSummary
import homegap.discovery.Labels
import homegap.model.DesiredState
import homegap.model.Planner
import homegap.model.isHttpLike
import homegap.platform.nowMillis
import homegap.provision.CertMaterial
import homegap.provision.Certificates
import homegap.state.StateStore
import homegap.tunnels.TunnelFiles
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * The reconciler: drives actual state toward desired state (labels + config) across six
 * independent domains. Runs event-driven (Docker events, debounced), periodically (full
 * resync) and on demand (dashboard). A failure in one domain never blocks the others.
 */
@Inject
@SingleIn(AppScope::class)
class Controller(
    options: RunOptions,
    initialConfig: AppConfig,
    private val docker: DockerClient,
    @LocalHttp private val localHttp: HttpClient,
    @ExternalHttp private val externalHttp: HttpClient,
    val log: ReconcileLog,
    val state: StateStore,
    private val ssh: SshPool,
    val speedTokens: homegap.speedtest.SpeedTestTokens,
    val speedStore: homegap.speedtest.SpeedTestStore,
) {
    private val configPath = options.configPath
    private val dryRunOverride = options.dryRun
    @Volatile var config: AppConfig = initialConfig
        private set
    private val passMutex = Mutex()
    /** Bumped when in-memory status changes (passes, background jobs). */
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Fires whenever anything the dashboard shows may have changed. */
    val changes: kotlinx.coroutines.flow.Flow<Long>
        get() = kotlinx.coroutines.flow.merge(revision, state.revision, log.revision)
    private val triggers = Channel<String>(Channel.UNLIMITED)
    private val forceProvision = mutableSetOf<String>()
    /** Background provisioning per node: the kind of work and its job. */
    private val jobs = mutableMapOf<String, Pair<NodeReconciler.Kind, Job>>()
    /** Why a node's pending provisioning is not running yet (shown on the dashboard). */
    private val waiting = mutableMapOf<String, String>()
    private var jobScope: CoroutineScope? = null
    private val speedRunning = mutableSetOf<String>()

    val dryRun: Boolean get() = dryRunOverride || config.reconcile.dryRun

    // Latest results, read by the dashboard.
    @Volatile var desired: DesiredState? = null; private set
    @Volatile var lastDiff: List<DiffItemDto> = emptyList(); private set
    @Volatile var lastSummary: ReconcileSummaryDto? = null; private set
    @Volatile var reconciling: Boolean = false; private set
    @Volatile private var nodeObs: Map<String, NodeObservation> = emptyMap()
    @Volatile private var serviceHealth: Map<String, Map<String, ServiceNodeHealthDto>> = emptyMap()
    @Volatile private var dnsObs: List<DnsReconciler.Observed> = emptyList()
    @Volatile private var siteOnline: Map<String, Boolean?> = emptyMap()
    private val meshRec by lazy { MeshReconciler(HomelabMesh("${config.dataDir}/mesh")) }
    @Volatile private var meshStatus: homegap.api.MeshStatusDto? = null
    /** Desired-records hash and time of the last successful DNS sync (see the DNS step). */
    private var dnsKey: String? = null
    private var dnsSyncedAt = 0L

    data class NodeObservation(
        val reachable: Boolean,
        val checks: List<CheckDto>,
        val certificate: CertificateDto?,
        val apiOk: Boolean,
    )

    fun trigger(reason: String) {
        triggers.trySend(reason)
    }

    fun requestProvision(nodeId: String) {
        forceProvision += nodeId
        trigger("provision:$nodeId")
    }

    /** Clears a failed or skipped update and applies it to [nodeId] now, with rollback. */
    suspend fun retryUpdate(nodeId: String): String {
        requireNotNull(config.node(nodeId)) { "unknown node $nodeId" }
        check(jobs[nodeId]?.second?.isActive != true) { "$nodeId is already being provisioned" }
        state.updateNode(nodeId) { it.copy(update = null) }
        log.change("nodes", "update retry requested", nodeId)
        requestProvision(nodeId)
        return "Retrying the update on $nodeId"
    }

    /** Releases the rollout hold caused by [nodeId]: it stays on its current stack. */
    suspend fun skipUpdate(nodeId: String): String {
        val u = state.node(nodeId).update ?: throw IllegalStateException("$nodeId has no failed update")
        state.updateNode(nodeId) { it.copy(update = u.copy(status = "skipped")) }
        log.change("nodes", "update skipped; the rollout continues without this node", nodeId)
        trigger("skip:$nodeId")
        return "Skipped the update on $nodeId; other nodes continue"
    }

    suspend fun run(scope: CoroutineScope, once: Boolean) {
        if (once) {
            reconcileOnce("startup")
            return
        }
        jobScope = scope
        scope.launch { watchDocker() }
        scope.launch {
            while (isActive) {
                delay(config.reconcile.resync)
                trigger("periodic")
            }
        }
        trigger("startup")
        for (reason in triggers) {
            // coalesce bursts of triggers into one pass
            val reasons = mutableSetOf(reason)
            while (true) reasons += triggers.tryReceive().getOrNull() ?: break
            try {
                reconcileOnce(reasons.joinToString(","))
            } catch (e: Exception) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                log.error("controller", "reconcile pass crashed: ${e.message}")
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchDocker() {
        val filters = mapOf("type" to listOf("container"), "event" to listOf("start", "stop", "die", "destroy", "update", "rename"))
        while (true) {
            try {
                docker.streamEvents(filters)
                    .filter { e -> e.actor.attributes.keys.any { it.startsWith(Labels.PREFIX) } || e.action in setOf("die", "destroy", "stop") }
                    .debounce(config.reconcile.debounceDuration)
                    .collect { e -> trigger("docker:${e.action}") }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                log.warn("discovery", "Docker event stream disconnected (${e.message}); reconnecting in 5s")
            }
            delay(5.seconds)
        }
    }

    private fun reloadConfig() {
        try {
            val next = ConfigLoader.load(configPath)
            if (next != config) log.info("controller", "configuration reloaded")
            config = next
        } catch (e: Exception) {
            log.error("controller", "config reload failed, keeping last good config: ${e.message}")
        }
    }

    suspend fun reconcileOnce(trigger: String) = passMutex.withLock {
        reconciling = true
        revision.value++
        val started = nowMillis()
        log.drainCounters()
        try {
            reloadConfig()
            val cfg = config
            val diff = DiffCollector()
            val ctx = ReconcileContext(cfg, state, log, ssh, localHttp, externalHttp, dryRun, diff)
            val pauses = state.snapshot().pauses
            if (pauses.global) log.info("controller", "global pause active: observing only")

            // Remember how to reach each node so it can be torn down after removal from config.
            if (!dryRun) for (n in cfg.nodes) state.updateNode(n.id) { it.copy(facts = it.facts + nodeFacts(n), pendingDecommission = false) }
            // Track removed nodes (decommission needs explicit confirmation).
            val configured = cfg.nodes.map { it.id }.toSet()
            for (id in state.snapshot().nodes.keys - configured) {
                if (!state.node(id).pendingDecommission) {
                    state.updateNode(id) { it.copy(pendingDecommission = true) }
                    log.warn("nodes", "removed from config; confirm decommission in the dashboard to tear it down", id)
                }
            }

            // 1. Service discovery (abort destructive domains if it fails: an empty view must never delete everything).
            val containers: List<DockerContainerSummary>? = try {
                docker.listContainers(mapOf("status" to listOf("running"), "label" to listOf(Labels.EXPOSE)))
            } catch (e: Exception) {
                if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                log.error("discovery", "cannot list containers: ${e.message}")
                null
            }
            val plan = Planner.plan(cfg, containers ?: emptyList(), pauses.services)
            if (containers != null) {
                desired = plan
                plan.labelErrors.forEach { log.error("discovery", it.message, it.container) }
                plan.labelWarnings.forEach { log.warn("discovery", it.message, it.container) }
                if (dryRun) logDesired(plan)
            }
            val effective = if (containers != null) plan else desired ?: plan
            val pausedKeys = effective.services.filter { it.paused }.map { it.key }.toSet() + pauses.services
            val discoveryOk = containers != null

            val certs: CertMaterial? = try {
                Certificates.load(cfg.certificates, cfg.domain)
            } catch (e: Exception) {
                log.error("nodes", "certificate material unavailable: ${e.message}")
                null
            }

            // 2-4. Node lifecycle, tunnels and resources, per node in parallel.
            val nodeRec = NodeReconciler(ctx)
            val tunnelRec = TunnelReconciler(ctx)
            val resourceRec = ResourceReconciler(ctx)
            val health = mutableMapOf<String, Map<String, ServiceNodeHealthDto>>()
            // niceIds observed present on each node this pass (absent key = node not observed).
            val present = mutableMapOf<String, Set<String>>()
            val online = mutableMapOf<String, Boolean?>()
            val obs = mutableMapOf<String, NodeObservation>()
            // Permanent tunnel slots (subnet, interface, ports); never derived from list position.
            val slots = TunnelSlots.assign(cfg.nodes.map { it.id }, state.snapshot().nodes)
            if (!dryRun) for ((id, slot) in slots) {
                if (state.node(id).tunnelSlot != slot) state.updateNode(id) { it.copy(tunnelSlot = slot) }
            }
            coroutineScope {
                cfg.nodes.map { node ->
                    async {
                        val slot = state.node(node.id).tunnelSlot ?: slots.getValue(node.id)
                        val nd = effective.nodes.getValue(node.id)
                        // A node being provisioned in the background keeps its last observation.
                        fun carryOver() {
                            nodeObs[node.id]?.let { synchronizedPut(obs, node.id, it) }
                            synchronizedPut(online, node.id, siteOnline[node.id])
                            serviceHealth[node.id]?.let { synchronizedPut(health, node.id, it) }
                        }
                        if (jobs[node.id]?.second?.isActive == true) {
                            carryOver()
                            return@async
                        }
                        val force = forceProvision.remove(node.id)
                        val a = runDomain("nodes", node.id) { nodeRec.assess(nd, slot, force, certs) }
                        var ready: ReadyNode? = null
                        if (a != null) {
                            val decision = if (a.paused || a.kind == NodeReconciler.Kind.NONE) RolloutPolicy.Decision.Start
                            // Until the slot is checked against the live subnet, the rendered stack may be wrong.
                            else if (a.kind == NodeReconciler.Kind.UPDATE && !state.node(node.id).tunnelSlotConfirmed) RolloutPolicy.Decision.Wait("confirming the node's tunnel slot")
                            else RolloutPolicy.decide(
                                node.id, a.kind, a.hash, force, state.snapshot().nodes,
                                jobs.filter { (_, j) -> j.first == NodeReconciler.Kind.UPDATE && j.second.isActive }.keys, nowMillis(),
                            )
                            when {
                                decision is RolloutPolicy.Decision.Wait -> {
                                    if (waiting.put(node.id, decision.reason) != decision.reason) log.info("nodes", "${a.reason}: ${decision.reason}", node.id)
                                    // A node waiting for its update keeps serving on its current stack.
                                    if (a.kind != NodeReconciler.Kind.FRESH) ready = runDomain("nodes", node.id) { nodeRec.ready(a, slot) }
                                }
                                a.paused || a.kind == NodeReconciler.Kind.NONE -> {
                                    waiting.remove(node.id)
                                    // The desired stack is back to what the node runs: nothing left to retry.
                                    if (!a.paused && state.node(node.id).update?.let { it.targetHash != a.hash } == true) {
                                        state.updateNode(node.id) { it.copy(update = null) }
                                        log.change("nodes", "pending update no longer needed (desired stack matches the running one)", node.id)
                                    }
                                    ready = runDomain("nodes", node.id) { nodeRec.ready(a, slot) }
                                }
                                else -> {
                                    waiting.remove(node.id)
                                    val tunnelWasUp = siteOnline[node.id] == true
                                    val sc = jobScope
                                    if (sc == null) {
                                        // One-shot mode: provision inline.
                                        if (nodeRec.provisionJob(a, slot, tunnelWasUp)) ready = runDomain("nodes", node.id) { nodeRec.ready(a, slot) }
                                    } else {
                                        jobs[node.id] = a.kind to sc.launch {
                                            try {
                                                nodeRec.provisionJob(a, slot, tunnelWasUp)
                                            } finally {
                                                jobs.remove(node.id)
                                                revision.value++
                                                trigger("provisioned:${node.id}")
                                            }
                                        }
                                        revision.value++
                                        carryOver()
                                        return@async
                                    }
                                }
                            }
                        }
                        var site: PangolinSite? = null
                        var resources: List<PangolinResource> = emptyList()
                        if (ready != null) {
                            site = runDomain("tunnels", node.id) { tunnelRec.reconcile(ready, slot) }
                            if (discoveryOk && site != null) {
                                resources = runDomain("resources", node.id) { resourceRec.reconcile(ready, nd, pausedKeys) } ?: emptyList()
                                if (resources.isNotEmpty()) present[node.id] = resources.map { it.niceId }.toSet()
                            }
                        } else if (dryRun) {
                            val bp = resourceRec.buildBlueprint(nd.publicResources.filter { !it.paused }, nd.privateResources.filter { !it.paused })
                            log.info("resources", "[dry-run] blueprint: $bp", node.id)
                            nd.publicResources.forEach { diff.add("resources", node.id, it.niceId, "unknown", "${it.mode.wire} ${it.fullDomain ?: ":${it.proxyPort}"} -> ${it.target.host}:${it.target.port}") }
                        }
                        val o = runDomain("observe", node.id) { observeNode(ctx, node, ready, site) }
                        synchronizedPut(obs, node.id, o ?: NodeObservation(false, emptyList(), null, false))
                        synchronizedPut(online, node.id, site?.online)
                        synchronizedPut(health, node.id, serviceHealthFor(effective, node.id, resources, site))
                    }
                }.awaitAll()
            }
            if (!dryRun) runDomain("tunnels", null) { tunnelRec.prune(cfg.nodes.filter { !state.node(it.id).pendingDecommission }.map { it.id }.toSet()) }

            // The cluster mesh (host-level, independent of each node's stack).
            meshStatus = if (cfg.cluster == null || dryRun) null
            else runDomain("mesh", null) { meshRec.reconcile(ctx, cfg.nodes, state.snapshot().nodes.mapNotNull { (id, s) -> s.tunnelSlot?.let { id to it } }.toMap()) } ?: meshStatus

            // Private access on the primary (manual anchor): only private resources are written.
            if (discoveryOk) runDomain("private", "primary") { reconcilePrimary(ctx, effective, pausedKeys) }

            // 5. DNS
            if (discoveryOk) {
                val bunny = cfg.bunny.apiKey.resolve()?.let { BunnyClient(externalHttp, cfg.bunny.apiUrl, it) }
                val records = servableDns(effective, present)
                // The zone download is the pass's only external API call: skip it while the desired
                // records are unchanged and the last sync succeeded recently.
                val key = homegap.platform.Crypto.fnv64(records.toString() + pausedKeys.sorted() + state.snapshot().pauses + cfg.bunny)
                val fresh = key == dnsKey && nowMillis() - dnsSyncedAt < cfg.reconcile.dnsCheck.inWholeMilliseconds
                if (!fresh || trigger.contains("dashboard")) {
                    val obs = runDomain("dns", null) { DnsReconciler(ctx, bunny).reconcile(records, pausedKeys) }
                    if (obs != null) {
                        dnsObs = obs
                        if (bunny != null) { dnsKey = key; dnsSyncedAt = nowMillis() }
                    } else {
                        dnsKey = null
                    }
                }
            }

            nodeObs = obs
            siteOnline = online
            serviceHealth = health
            lastDiff = diff.all()
        } finally {
            val (changes, errors) = log.drainCounters()
            lastSummary = ReconcileSummaryDto(started, nowMillis(), trigger, errors == 0, changes, errors)
            reconciling = false
            revision.value++
            log.info("controller", "pass complete in ${nowMillis() - started}ms ($changes changes, $errors errors) trigger=$trigger")
        }
    }

    /**
     * Keeps a node in a hostname's record set only while the Pangolin resource serving that
     * hostname exists on it (e.g. a wildcard refused for lack of an EE license), so DNS never
     * steers viewers to a node that would answer 404. Nodes not observed this pass are kept.
     */
    private fun servableDns(plan: DesiredState, present: Map<String, Set<String>>): List<homegap.model.DesiredDnsRecord> =
        plan.dns.filter { d ->
            val have = present[d.nodeId] ?: return@filter true
            val nd = plan.nodes[d.nodeId] ?: return@filter true
            val frontDoor = plan.frontDoor
            val fdKey = frontDoor?.key
            val fdWildcard = frontDoor?.let { "*.${it.hostname}" }
            val needed = when {
                d.purpose == "management" -> emptyList()
                d.purpose == "root" -> nd.publicResources.filter { it.serviceKey == fdKey && it.fullDomain != fdWildcard && it.niceId != Planner.HEALTH }
                d.purpose == "wildcard" -> nd.publicResources.filter { it.serviceKey == fdKey && (it.mode.wire == "tcp" || it.fullDomain == fdWildcard) }
                d.purpose.startsWith("service:") -> nd.publicResources.filter { it.serviceKey == d.purpose.removePrefix("service:") && it.niceId != Planner.HEALTH }
                else -> emptyList()
            }.filter { !it.paused }.map { it.niceId }
            val missing = needed.filter { it !in have }
            if (missing.isNotEmpty()) log.warn("dns", "not steering ${d.fqdn} to this node: ${missing.joinToString()} missing on it", d.nodeId)
            missing.isEmpty()
        }

    private fun <K, V> synchronizedPut(map: MutableMap<K, V>, k: K, v: V) {
        // async blocks run on the single runBlocking event loop; a plain put is safe there.
        map[k] = v
    }

    private suspend fun <T> runDomain(domain: String, target: String?, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        log.error(domain, e.message ?: e.toString(), target)
        null
    }

    private fun logDesired(plan: DesiredState) {
        log.info("discovery", "[dry-run] ${plan.services.size} labeled service(s): " + plan.services.joinToString { "${it.hostname}(${it.protocol.label}/${it.tls.label}/${it.access.label})" })
        plan.frontDoor?.let { log.info("discovery", "[dry-run] front door: ${it.containerName} claims ${it.hostname}${if (it.wildcard) " + *.${it.hostname}" else ""} (${it.tls.label})") }
        for ((id, n) in plan.nodes) {
            log.info("resources", "[dry-run] public=${n.publicResources.map { it.niceId }} private=${n.privateResources.map { it.niceId }} sni=${n.sniRoutes.map { it.name }} raw=${n.rawPorts.map { it.entryPoint }}", id)
        }
        plan.dns.forEach { log.info("dns", "[dry-run] ${it.type} ${it.fqdn} -> ${it.value} node=${it.nodeId} geo=${it.geo} monitor=${it.monitor}") }
    }

    private suspend fun reconcilePrimary(ctx: ReconcileContext, plan: DesiredState, pausedKeys: Set<String>) {
        val p = ctx.config.primaryPangolin ?: return
        val active = plan.primaryPrivate.filter { !it.paused }
        val key = p.apiKey.resolve() ?: run {
            if (active.isNotEmpty()) log.error("private", "primary Pangolin key ${p.apiKey.describe()} missing")
            return
        }
        val api = PangolinClient(externalHttp, p.url.trimEnd('/'), key)
        val rr = ResourceReconciler(ctx)
        active.forEach { ctx.diff.add("private", "primary", it.niceId, "ensure", "${it.alias} -> ${it.destination}:${it.tcpPorts}${it.udpPorts}") }
        if (ctx.dryRun) return
        if (active.isNotEmpty()) {
            api.applyBlueprint(p.org, rr.buildBlueprint(emptyList(), active))
        }
        val keep = plan.primaryPrivate.map { it.niceId }.toSet()
        for (a in api.listSiteResources(p.org)) {
            if (!a.niceId.startsWith(Planner.NICE_PREFIX) || a.niceId in keep) continue
            if (pausedKeys.any { a.niceId.startsWith(Planner.NICE_PREFIX + it) }) continue
            api.deleteSiteResource(a.siteResourceId)
            log.change("private", "deleted private resource ${a.niceId} on primary")
        }
    }

    // ------------------------------------------------------------------ observability

    private suspend fun observeNode(
        ctx: ReconcileContext,
        node: NodeConfig,
        ready: ReadyNode?,
        site: PangolinSite?,
    ): NodeObservation {
        if (ctx.dryRun) return NodeObservation(false, listOf(CheckDto("mode", Health.UNKNOWN, "dry-run: no remote access")), null, false)
        val checks = mutableListOf<CheckDto>()
        val mgmt = node.managementHost(ctx.config.domain)
        val root = ctx.config.domain
        val script = $$"""
            |for s in pangolin gerbil traefik; do echo "C $s $(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $s 2>/dev/null || echo 'missing none')"; done
            |echo "D $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)"
            |probe() { c=$(echo | timeout 6 openssl s_client -connect 127.0.0.1:443 -servername "$1" 2>/dev/null | openssl x509 -noout -enddate -subject 2>/dev/null) || { echo "none"; return; }; end=$(echo "$c" | sed -n 's/^notAfter=//p'); sub=$(echo "$c" | sed -n 's/^subject=//p'); echo "$(date -d "$end" +%s 2>/dev/null || echo 0)|$sub"; }
            |echo "T mgmt $(probe $${shQuote(mgmt)})"
            |echo "T root $(probe $${shQuote(root)})"
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "U ok" || echo "U missing"
        """.trimMargin()
        val r = runCatching {
            ctx.ssh.session(node).runScript(script, 60)
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }.getOrNull()
        val reachable = r != null && r.ok
        checks += CheckDto("ssh", if (reachable) Health.OK else Health.DOWN, if (reachable) "" else (r?.stderr?.trim()?.take(200) ?: "unreachable"))
        var cert: CertificateDto? = null
        if (reachable) {
            for (line in r.stdout.lines()) {
                val parts = line.split(' ', limit = 3)
                when (parts.firstOrNull()) {
                    "C" -> if (parts.size == 3) {
                        val (status, hc) = parts[2].split(' ').let { it[0] to it.getOrElse(1) { "none" } }
                        val h = when {
                            status != "running" -> Health.DOWN
                            hc == "unhealthy" -> Health.DOWN
                            hc == "starting" -> Health.DEGRADED
                            else -> Health.OK
                        }
                        checks += CheckDto(parts[1], h, "$status${if (hc != "none") " ($hc)" else ""}")
                    }
                    "D" -> checks += CheckDto("docker", if (parts.getOrNull(1) == "none") Health.DOWN else Health.OK, parts.getOrNull(1) ?: "")
                    "U" -> checks += CheckDto("gerbil-udp", if (parts.getOrNull(1) == "ok") Health.OK else Health.DOWN, "51820/udp")
                    "T" -> if (parts.size == 3) {
                        val which = parts[1]
                        val v = parts[2]
                        if (v == "none") {
                            checks += CheckDto("tls-$which", Health.DOWN, "no certificate served")
                        } else {
                            val epoch = v.substringBefore('|').toLongOrNull() ?: 0
                            val subject = v.substringAfter('|')
                            val days = if (epoch > 0) ((epoch * 1000 - nowMillis()) / 86_400_000L).toInt() else null
                            val h = when {
                                days == null -> Health.UNKNOWN
                                days < 0 -> Health.DOWN
                                days < 14 -> Health.DEGRADED
                                else -> Health.OK
                            }
                            val dto = CertificateDto(subject, if (epoch > 0) epochToIso(epoch) else null, days, which, h)
                            if (which == "mgmt") cert = dto
                            checks += CheckDto("tls-$which", h, "$subject${days?.let { ", $it days left" } ?: ""}")
                        }
                    }
                }
            }
        }
        val apiOk = ready != null && runCatching {
            ready.api.getOrg(ctx.config.pangolin.orgId)
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }.isSuccess
        checks += CheckDto("pangolin-api", if (apiOk) Health.OK else Health.DOWN, if (apiOk) "integration API" else "not reachable")
        if (node.licenseKey(ctx.config.pangolin) != null) {
            val ns = state.node(node.id)
            checks += when {
                ns.licenseActivated -> CheckDto("ee-license", Health.OK, "activated")
                ns.facts["licenseRejected"] != null -> CheckDto("ee-license", Health.DEGRADED, "rejected: key bound to another instance")
                else -> CheckDto("ee-license", Health.UNKNOWN, "not activated")
            }
        }
        checks += CheckDto("tunnel", when (site?.online) { true -> Health.OK; false -> Health.DOWN; null -> Health.UNKNOWN }, site?.let { "site ${it.niceId}${it.newtVersion?.let { v -> ", newt $v" } ?: ""}" } ?: "no site")
        return NodeObservation(reachable, checks, cert, apiOk)
    }

    private fun serviceHealthFor(plan: DesiredState, nodeId: String, resources: List<PangolinResource>, site: PangolinSite?): Map<String, ServiceNodeHealthDto> {
        val nd = plan.nodes[nodeId] ?: return emptyMap()
        val byNice = resources.associateBy { it.niceId }
        val out = mutableMapOf<String, ServiceNodeHealthDto>()
        for (s in plan.services) {
            if (!s.servesNode(nodeId)) continue
            val res = nd.publicResources.filter { it.serviceKey == s.key && it.niceId != Planner.HEALTH } +
                emptyList()
            val priv = nd.privateResources.filter { it.serviceKey == s.key }
            if (res.isEmpty() && priv.isEmpty()) continue
            val actual = res.mapNotNull { byNice[it.niceId] }
            val h = when {
                site?.online == false -> ServiceNodeHealthDto(nodeId, Health.DOWN, "tunnel down")
                res.isNotEmpty() && actual.size < res.size -> ServiceNodeHealthDto(nodeId, Health.DOWN, "resource missing")
                actual.any { it.targets.any { t -> t.healthStatus == "unhealthy" } } -> ServiceNodeHealthDto(nodeId, Health.DEGRADED, "target unhealthy")
                site?.online == true -> ServiceNodeHealthDto(nodeId, Health.OK, actual.firstOrNull()?.targets?.firstOrNull()?.healthStatus ?: "online")
                else -> ServiceNodeHealthDto(nodeId, Health.UNKNOWN, "")
            }
            out[s.key] = h
        }
        return out
    }

    fun snapshot(): StatusSnapshot {
        val cfg = config
        val st = state.snapshot()
        val plan = desired
        val nodes = (cfg.nodes.map { it.id } + st.nodes.keys).distinct().map { id ->
            val n = cfg.node(id)
            val ns = st.nodes[id]
            val o = nodeObs[id]
            val tunnel = TunnelFiles.readStatus(cfg.tunnels.stateDir ?: "${cfg.dataDir}/tunnels", id)
            val worst = o?.checks?.map { it.health }?.let { hs ->
                when {
                    hs.contains(Health.DOWN) -> Health.DOWN
                    hs.contains(Health.DEGRADED) -> Health.DEGRADED
                    hs.all { it == Health.OK } -> Health.OK
                    else -> Health.UNKNOWN
                }
            } ?: Health.UNKNOWN
            NodeStatusDto(
                id = id,
                region = n?.region ?: "",
                provider = n?.provider ?: "",
                host = n?.host ?: "",
                managementHost = n?.managementHost(cfg.domain) ?: "",
                health = if (ns?.lastProvisionOk == false) Health.DOWN else worst,
                reachable = o?.reachable ?: false,
                checks = o?.checks ?: emptyList(),
                certificate = o?.certificate,
                tunnelUp = siteOnline[id] == true,
                lastProvisionAt = ns?.lastProvisionAt,
                lastProvisionOk = ns?.lastProvisionOk,
                lastProvisionError = ns?.lastProvisionError,
                drift = ns?.drift ?: emptyList(),
                paused = st.pauses.global || id in st.pauses.nodes,
                pendingDecommission = ns?.pendingDecommission == true || n == null,
                facts = (ns?.facts ?: emptyMap()).filterKeys { it in setOf("os", "arch", "kernel", "docker", "mem_kb", "tcp_cc", "qdisc", "edge_tcp") } +
                    (tunnel?.let { mapOf("newt_restarts" to it.restarts.toString()) } ?: emptyMap()),
                job = jobs[id]?.takeIf { it.second.isActive }?.let { if (it.first == NodeReconciler.Kind.UPDATE) "updating" else "provisioning" },
                updateWaiting = waiting[id],
                update = ns?.update?.let { NodeUpdateDto(it.status, it.error, it.at, it.rolledBack) },
            )
        }
        val services = plan?.services?.map { s ->
            ServiceStatusDto(
                key = s.key,
                container = s.containerName,
                hostname = s.hostname,
                wildcard = s.wildcard,
                protocol = s.protocol.label,
                tls = if (s.isHttpLike()) s.tls.label else "—",
                access = s.access.label,
                auth = s.auth.label,
                regions = s.regions ?: cfg.nodes.map { it.id },
                kind = when {
                    s == plan.frontDoor -> "front-door"
                    s.access.label == "private" -> "private"
                    s.protocol.label in setOf("tcp", "udp") -> "raw"
                    else -> "dedicated"
                },
                paused = s.paused || s.key in st.pauses.services,
                perNode = cfg.nodes.filter { s.servesNode(it.id) }.map { n ->
                    serviceHealth[n.id]?.get(s.key) ?: ServiceNodeHealthDto(n.id, Health.UNKNOWN)
                },
            )
        } ?: emptyList()
        val dns = dnsObs.map { o ->
            DnsRecordStatusDto(
                name = o.desired?.fqdn ?: o.record.name,
                type = o.record.typeName,
                value = o.record.value,
                nodeId = o.desired?.nodeId,
                geo = when (o.record.smartRoutingType) {
                    BunnyRecord.ROUTING_GEO -> "geo ${o.record.latitude},${o.record.longitude}"
                    BunnyRecord.ROUTING_LATENCY -> "latency ${o.record.latencyZone}"
                    else -> null
                },
                monitor = listOf("none", "ping", "http", "monitor").getOrElse(o.record.monitorType) { "?" },
                monitorStatus = BunnyRecord.MONITOR_STATUS.getOrElse(o.record.monitorStatus) { "unknown" },
                inSync = o.inSync,
                diff = o.diff,
            )
        }
        val tunnelsDir = cfg.tunnels.stateDir ?: "${cfg.dataDir}/tunnels"
        val tunnels = cfg.nodes.map { n ->
            val t = TunnelFiles.readStatus(tunnelsDir, n.id)
            val fresh = t != null && nowMillis() - t.updatedAt < 60_000
            TunnelStatusDto(
                nodeId = n.id,
                siteNiceId = cfg.pangolin.siteName,
                endpoint = "https://${n.managementHost(cfg.domain)}",
                up = siteOnline[n.id] == true,
                processRunning = fresh && t.running,
                siteOnline = siteOnline[n.id],
                restarts = t?.restarts ?: 0,
                lastExit = t?.lastExit,
                bytesIn = t?.bytesIn,
                bytesOut = t?.bytesOut,
                updatedAt = t?.updatedAt,
            )
        }
        return StatusSnapshot(
            generatedAt = nowMillis(),
            version = VERSION,
            dryRun = dryRun,
            project = cfg.project,
            domain = cfg.domain,
            paused = st.pauses.global,
            lastReconcile = lastSummary,
            reconciling = reconciling,
            nodes = nodes,
            services = services,
            dns = dns,
            tunnels = tunnels,
            labelErrors = plan?.labelErrors?.map { LabelErrorDto(it.container, it.message) } ?: emptyList(),
            pauses = PausesDto(st.pauses.global, st.pauses.nodes.sorted(), st.pauses.services.sorted()),
            mesh = meshStatus.takeIf { cfg.cluster != null },
        )
    }

    fun desiredStateJson(): String {
        val plan = desired ?: return "{}"
        val rr = ResourceReconciler(ReconcileContext(config, state, log, ssh, localHttp, externalHttp, true, DiffCollector()))
        val obj = buildJsonObject {
            put("frontDoor", plan.frontDoor?.hostname)
            putJsonArray("services") {
                plan.services.forEach { s ->
                    addJsonObject {
                        put("key", s.key); put("hostname", s.hostname); put("container", s.containerName); put("target", "${s.targetHost}:${s.port}")
                        put("protocol", s.protocol.label); put("tls", s.tls.label); put("access", s.access.label); put("auth", s.auth.label)
                        put("wildcard", s.wildcard); put("regions", s.regions?.joinToString(",") ?: "all"); put("paused", s.paused)
                    }
                }
            }
            putJsonObject("nodes") {
                plan.nodes.forEach { (id, n) ->
                    putJsonObject(id) {
                        put("managementHost", n.managementHost)
                        put("blueprint", rr.buildBlueprint(n.publicResources.filter { !it.paused }, n.privateResources.filter { !it.paused }))
                        putJsonArray("sniRoutes") { n.sniRoutes.forEach { add(homegap.provision.NodeTemplates.sniRule(it)) } }
                        putJsonArray("rawPorts") { n.rawPorts.forEach { add(it.entryPoint) } }
                    }
                }
            }
            putJsonArray("dns") {
                plan.dns.forEach { d -> add("${d.type} ${d.fqdn} -> ${d.value} [${d.nodeId}] ${d.purpose}${if (d.geo) " geo" else ""} monitor=${d.monitor}") }
            }
        }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), obj)
    }

    // ------------------------------------------------------------------ speed tests

    fun speedOverview(): SpeedTestOverview {
        val cfg = config
        val st = cfg.speedtest
        return SpeedTestOverview(
            enabled = st.enabled,
            autoUrl = if (st.enabled) "http://${st.autoHost(cfg.domain)}/" else null,
            nodes = cfg.nodes.map { it.id },
            results = speedStore.recent(),
            streams = st.streams,
        )
    }

    /** A device test link valid for `speedtest.link_ttl`; the token travels in the URL fragment. */
    fun createSpeedLink(): SpeedTestLinkDto {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        val (token, exp) = speedTokens.issue(homegap.config.parseDuration(cfg.speedtest.linkTtl).inWholeMilliseconds)
        return SpeedTestLinkDto(
            autoUrl = "http://${cfg.speedtest.autoHost(cfg.domain)}/#t=$token",
            nodeUrls = cfg.nodes.associate { it.id to "https://${cfg.speedtest.nodeHost(cfg.domain, it.id)}/#t=$token" },
            expiresAt = exp,
        )
    }

    /** Measures homelab <-> node through the tunnel, driven from the node over SSH. */
    suspend fun runTunnelSpeedTest(nodeId: String, streams: Int? = null): SpeedTestResultDto {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        val node = cfg.node(nodeId) ?: throw IllegalArgumentException("unknown node $nodeId")
        check(!dryRun) { "speed tests need remote access (dry-run is on)" }
        check(speedRunning.add(nodeId)) { "a speed test for $nodeId is already running" }
        val (token, _) = speedTokens.issue(5 * 60_000L)
        try {
            val host = cfg.speedtest.nodeHost(cfg.domain, nodeId)
            val secs = cfg.speedtest.phaseSeconds
            val n = (streams ?: cfg.speedtest.streams).coerceIn(1, homegap.speedtest.TunnelSpeedTest.MAX_STREAMS)
            val r = ssh.session(node).runScript(homegap.speedtest.TunnelSpeedTest.script(host, token, secs, n), secs * 2 + 120)
            val p = homegap.speedtest.TunnelSpeedTest.parse(r.stdout, host, n)
            val err = p.error ?: if (!r.ok) r.stderr.trim().take(300).ifEmpty { "exit ${r.exitCode}" } else null
            val result = SpeedTestResultDto(
                id = homegap.speedtest.newResultId(), at = nowMillis(), kind = "tunnel", nodeId = nodeId,
                pingMs = p.pingMs, jitterMs = p.jitterMs, downMbps = p.downMbps, upMbps = p.upMbps, pingSamples = p.samples, error = err,
                streams = n, downStreamsMbps = p.downStreams, upStreamsMbps = p.upStreams,
                client = "node $nodeId (newt mtu ${cfg.tunnels.mtu ?: "default"}${if (cfg.tunnels.native) ", kernel wireguard" else ""})",
            )
            speedStore.add(result)
            log.info("speedtest", "tunnel: ping ${p.pingMs?.let { fmt1(it) } ?: "-"} ms, jitter ${p.jitterMs?.let { fmt1(it) } ?: "-"} ms, down ${p.downMbps?.let { fmt1(it) } ?: "-"} Mbps, up ${p.upMbps?.let { fmt1(it) } ?: "-"} Mbps ($n streams)${err?.let { " ($it)" } ?: ""}", nodeId)
            return result
        } finally {
            speedTokens.revoke(token)
            speedRunning.remove(nodeId)
        }
    }

    private fun fmt1(d: Double): String = ((d * 10).toLong() / 10.0).toString()

    // ------------------------------------------------------------------ dashboard actions

    suspend fun setPause(scope: String, id: String?, paused: Boolean): String {
        when (scope) {
            "global" -> state.update { it.copy(pauses = it.pauses.copy(global = paused)) }
            "node" -> {
                requireNotNull(id) { "node id required" }
                state.update { it.copy(pauses = it.pauses.copy(nodes = if (paused) it.pauses.nodes + id else it.pauses.nodes - id)) }
            }
            "service" -> {
                requireNotNull(id) { "service key required" }
                state.update { it.copy(pauses = it.pauses.copy(services = if (paused) it.pauses.services + id else it.pauses.services - id)) }
            }
            else -> throw IllegalArgumentException("scope must be global|node|service")
        }
        log.change("controller", "${if (paused) "paused" else "resumed"} $scope${id?.let { " $it" } ?: ""}")
        if (!paused) trigger("resume")
        return "${if (paused) "Paused" else "Resumed"} $scope${id?.let { " $it" } ?: ""}"
    }

    suspend fun addNode(node: NodeConfig): String {
        require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        require(config.node(node.id) == null) { "node ${node.id} already exists" }
        val next = config.copy(nodes = config.nodes + node)
        ConfigLoader.save(configPath, next)
        config = next
        state.updateNode(node.id) { it.copy(pendingDecommission = false) }
        log.change("controller", "added node ${node.id} to config", node.id)
        trigger("config")
        return "Node ${node.id} added; provisioning will start on the next pass"
    }

    suspend fun removeNode(id: String): String {
        require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        val node = config.node(id) ?: throw IllegalArgumentException("unknown node $id")
        val next = config.copy(nodes = config.nodes - node)
        ConfigLoader.save(configPath, next)
        config = next
        state.updateNode(id) { it.copy(pendingDecommission = true, facts = it.facts + nodeFacts(node)) }
        log.change("controller", "removed node $id from config (pending decommission)", id)
        trigger("config")
        return "Node $id removed from config. DNS drains on the next pass; confirm decommission to stop its stack."
    }

    private fun nodeFacts(n: NodeConfig) = mapOf(
        "cfg.host" to n.host, "cfg.user" to n.sshUser, "cfg.port" to n.sshPort.toString(), "cfg.key" to n.sshKeyFile,
    )

    /** Tears down a node already removed from config. Requires typing the node id to confirm. */
    suspend fun confirmDecommission(id: String, confirm: String): String {
        require(confirm == id) { "confirmation must equal the node id" }
        require(config.node(id) == null) { "node $id is still in the config; remove it first" }
        val ns = state.node(id)
        val host = ns.facts["cfg.host"]
        if (host != null && !dryRun) {
            val n = NodeConfig(id = id, host = host, sshUser = ns.facts["cfg.user"] ?: "root", sshPort = ns.facts["cfg.port"]?.toIntOrNull() ?: 22, sshKeyFile = ns.facts["cfg.key"] ?: "", publicIpv4 = "0.0.0.0")
            val ctx = ReconcileContext(config, state, log, ssh, localHttp, externalHttp, dryRun, DiffCollector())
            runCatching {
                NodeReconciler(ctx).decommission(n)
            }.onFailure {
                if (it is CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                log.warn("nodes", "stack teardown failed (${it.message}); removing from state anyway", id)
            }
        }
        state.removeNode(id)
        TunnelReconciler(ReconcileContext(config, state, log, ssh, localHttp, externalHttp, dryRun, DiffCollector())).prune(config.nodes.map { it.id }.toSet())
        log.change("nodes", "decommissioned", id)
        trigger("decommission")
        return "Node $id decommissioned"
    }
}

private fun epochToIso(epochSeconds: Long): String =
    kotlin.time.Instant.fromEpochSeconds(epochSeconds).toString()

private val PRETTY_JSON = Json { prettyPrint = true }
