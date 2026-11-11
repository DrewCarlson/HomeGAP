package homegap.reconcile

import homegap.config.adoptSharedDirs
import homegap.config.clusterConfigDir
import homegap.config.meshDir
import homegap.config.tunnelsDir
import homegap.provision.ExitNodes
import homegap.provision.TunnelDns
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
import homegap.discovery.TraefikApi
import homegap.discovery.TraefikRouter
import homegap.discovery.traefikApiUrl
import homegap.discovery.concernsHomegap
import homegap.model.DesiredState
import homegap.model.Planner
import homegap.model.isHttpLike
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.platform.queryNameserver
import homegap.provision.CertMaterial
import homegap.provision.Certificates
import homegap.provision.ClusterProxy
import kotlin.io.encoding.Base64
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
    private val meshRec by lazy { MeshReconciler(HomelabMesh(config.meshDir)) }
    @Volatile private var meshStatus: homegap.api.MeshStatusDto? = null
    private val clusterRec by lazy {
        ClusterReconciler(
            dataDir = config.dataDir,
            http = localHttp,
            configDir = config.clusterConfigDir,
            inUse = { state.snapshot().let { s -> s.clusterPangolin?.bootstrapped == true || s.nodes.values.any { it.meshPublicKey != null } } },
        )
    }
    /** The cluster's reachable Pangolins as of the last pass, and which node each exit node is. */
    @kotlin.concurrent.Volatile private var clusterReady: Map<String, ReadyNode> = emptyMap()
    @kotlin.concurrent.Volatile private var exitNodes: Map<Int, String> = emptyMap()
    /** Each node's recent cluster proxy readings, for counting lost primaries. */
    private val proxyHistory = mutableMapOf<String, MutableList<Pair<Long, Map<String, Long>>>>()

    /** The last routers each front door's Traefik API reported (kept while it doesn't answer). */
    private val routerCache = mutableMapOf<String, List<TraefikRouter>>()

    /**
     * The homelab Traefik's routes as last read from its API, or null when HomeGAP does not read it
     * (no container sets the private-middleware label) or has not read it yet.
     */
    @Volatile var homelabRouters: List<TraefikRouter>? = null
        private set

    /** The front door container's addresses on its Docker networks, as last discovered. */
    @Volatile var frontDoorAddresses: Set<String> = emptySet()
        private set
    private val routerFailing = mutableSetOf<String>()
    private val traefikApi by lazy { TraefikApi(localHttp) }

    /**
     * Reads the routers of every Traefik API a front door names (`homegap.private_middleware`).
     * Null when one has never answered: its private paths are unknown, so discovery counts as
     * failed rather than deleting them.
     */
    private suspend fun traefikRouters(containers: List<DockerContainerSummary>): Map<String, List<TraefikRouter>>? {
        val urls = containers.filter { !it.labels[Labels.PRIVATE_MIDDLEWARE].isNullOrBlank() }.map { traefikApiUrl(it.name, it.labels) }.distinct()
        var complete = true
        for (url in urls) {
            try {
                routerCache[url] = traefikApi.routers(url)
                if (routerFailing.remove(url)) log.info("discovery", "the Traefik API at $url answers again")
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                if (routerFailing.add(url)) {
                    log.warn("discovery", "cannot read the Traefik API at $url: ${e.message}" +
                        if (url in routerCache) " (keeping the routers it last reported)" else " (services and private paths are left as they are until it answers)")
                }
                if (url !in routerCache) complete = false
            }
        }
        if (urls.isNotEmpty()) homelabRouters = urls.mapNotNull { routerCache[it] }.flatten().takeIf { urls.all { u -> u in routerCache } }
        return if (complete) routerCache.filterKeys { it in urls } else null
    }

    /**
     * A reachable Pangolin of the cluster (the first ready node in config order) and the exit node
     * map, for dashboard actions on its users and devices; null without a cluster or a ready node.
     */
    /** Moves the database primary to [to] now (dashboard); see [ClusterReconciler.switchover]. */
    suspend fun switchPrimary(to: String): String {
        check(config.cluster != null && !dryRun) { "the cluster is not enabled" }
        val message = clusterRec.switchover(to)
        log.change("cluster", "$message (requested from the dashboard)")
        trigger("dashboard:switchover")
        return message
    }

    /** Moves the primaries off the homelab and holds them there (see [ClusterReconciler.beginMaintenance]). */
    suspend fun beginMaintenance(holdMinutes: Int): String {
        val cluster = config.cluster
        check(cluster != null && !dryRun) { "the cluster is not enabled" }
        // No pass right away: Pangolin's connections are still moving to the new primary.
        val message = clusterRec.beginMaintenance(cluster, holdMinutes.coerceIn(5, 240) * 60_000L)
        log.change("cluster", message)
        return message
    }

    fun endMaintenance(): String {
        check(config.cluster != null) { "the cluster is not enabled" }
        val message = clusterRec.endMaintenance()
        log.change("cluster", message)
        trigger("maintenance")
        return message
    }

    fun clusterPangolin(): Pair<ReadyNode, Map<Int, String>>? {
        if (config.cluster == null) return null
        val ready = clusterReady
        val first = config.nodes.firstOrNull { it.id in ready } ?: return null
        return ready.getValue(first.id) to exitNodes
    }
    @Volatile private var clusterStatus: homegap.api.ClusterStatusDto? = null
    /** Desired-records hash and time of the last successful DNS sync (see the DNS step). */
    private var dnsKey: String? = null
    private var dnsSyncedAt = 0L

    /** Whether each zone nameserver serves the records yet; remembers matches across passes. */
    private val nameserverCheck = NameserverCheck({ ns, name, type -> queryNameserver(ns, name, type) })

    data class NodeObservation(
        val reachable: Boolean,
        val checks: List<CheckDto>,
        val certificate: CertificateDto?,
        val apiOk: Boolean,
        /** Each service hostname requested through this node's proxy: status code and time. */
        val serviceProbes: Map<String, ServiceProbe> = emptyMap(),
        /** Traefik's request and response-byte counters, summed over entry points; null without metrics. */
        val traffic: Pair<Double, Double>? = null,
    )

    /** One request to a service through a node's proxy; [code] 0 when it got no answer. */
    data class ServiceProbe(val code: Int, val ms: Double)

    /** Recent measurements per edge, for the dashboard's graphs. */
    val metrics = EdgeMetrics()

    /** When each attention item was first seen, kept across passes. */
    @Volatile private var attentionSeen: Map<String, Long> = emptyMap()

    /** The Newt version each node's Pangolin reports for its homelab site. */
    private val newtVersions = mutableMapOf<String, String>()

    /** Asks the tunnel supervisor to restart [nodeId]'s connector now. */
    fun restartTunnel(nodeId: String): String {
        val cfg = config
        requireNotNull(cfg.node(nodeId)) { "unknown node $nodeId" }
        val dir = cfg.tunnelsDir
        Files.writeAtomic(TunnelFiles.restartPath(dir, nodeId), nowMillis().toString())
        log.change("tunnels", "connector restart requested from the dashboard", nodeId)
        return "Restarting the $nodeId connector"
    }

    /** Labeled homelab containers at the last discovery. */
    @Volatile private var homelabContainers: Int? = null

    /** What each running provisioning or update job is doing now. */
    private val jobSteps = mutableMapOf<String, String>()

    /** The stack an update would bring each node (set while one is pending or running). */
    private val targetStacks = mutableMapOf<String, String>()

    /** Tunnel byte counters at the previous sample, for throughput: node id to (time, in, out). */
    private val tunnelBytes = mutableMapOf<String, Triple<Long, Long, Long>>()

    /** Traefik counters at the previous sample: node id to (time, requests, response bytes). */
    private val trafficCounters = mutableMapOf<String, Triple<Long, Double, Double>>()

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
        if (!dryRun) {
            runCatching { config.adoptSharedDirs() }.onFailure { log.error("controller", "could not bring the shared files to their new place: ${it.message}") }
        }
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
                    .filter { it.concernsHomegap() }
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
        log.pass = log.nextPass()
        log.drainCounters()
        try {
            reloadConfig()
            val cfg = config
            val diff = DiffCollector()
            val applyOnce = APPLY_ONCE in trigger.split(',')
            val ctx = ReconcileContext(cfg, state, log, ssh, localHttp, externalHttp, dryRun, diff, applyOnce)
            val pauses = ctx.pauses()
            if (pauses.global) log.info("controller", "global pause active: observing only")
            if (applyOnce && state.snapshot().pauses.global) log.change("controller", "applying waiting changes once while paused")

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
            containers?.let { homelabContainers = it.size }
            val routers = containers?.let { traefikRouters(it) }
            val plan = Planner.plan(cfg, containers ?: emptyList(), pauses.services, traefikRouters = routers.orEmpty())
            if (containers != null && routers != null) {
                desired = plan
                frontDoorAddresses = plan.frontDoor?.let { fd -> containers.firstOrNull { it.name == fd.containerName } }
                    ?.networkSettings?.networks?.values?.map { it.ipAddress }?.filter { it.isNotBlank() }?.toSet().orEmpty()
                plan.labelErrors.forEach { log.error("discovery", it.message, it.container) }
                plan.labelWarnings.forEach { log.warn("discovery", it.message, it.container) }
                if (dryRun) logDesired(plan)
            }
            val effective = if (containers != null && routers != null) plan else desired ?: plan
            val pausedKeys = effective.services.filter { it.paused }.map { it.key }.toSet() + pauses.services
            val discoveryOk = containers != null && routers != null

            val certs: CertMaterial? = try {
                Certificates.load(cfg.certificates, cfg.domain)
            } catch (e: Exception) {
                log.error("nodes", "certificate material unavailable: ${e.message}")
                null
            }

            // 2-4. Node lifecycle, tunnels and resources, per node in parallel.
            val nodeRec = NodeReconciler(ctx, if (cfg.cluster != null) clusterRec else null)
            val tunnelRec = TunnelReconciler(ctx)
            val exitRec = ExitReconciler(ctx)
            val exitSites = mutableMapOf<String, String>()
            val resourceRec = ResourceReconciler(ctx)
            val health = mutableMapOf<String, Map<String, ServiceNodeHealthDto>>()
            // niceIds observed present on each node this pass (absent key = node not observed).
            val present = mutableMapOf<String, Set<String>>()
            val online = mutableMapOf<String, Boolean?>()
            val obs = mutableMapOf<String, NodeObservation>()
            // In a cluster the resources are shared: applied once after the per-node work.
            val clustered = cfg.cluster != null
            val readyNodes = mutableMapOf<String, Pair<ReadyNode, PangolinSite>>()
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
                            if (a.kind == NodeReconciler.Kind.UPDATE) targetStacks[node.id] = a.hash else targetStacks.remove(node.id)
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
                                        if (nodeRec.provisionJob(a, slot, tunnelWasUp, waitForCluster = false)) ready = runDomain("nodes", node.id) { nodeRec.ready(a, slot) }
                                    } else {
                                        jobs[node.id] = a.kind to sc.launch {
                                            try {
                                                nodeRec.provisionJob(
                                                    a = a,
                                                    slot = slot,
                                                    tunnelWasUp = tunnelWasUp,
                                                    progress = { step ->
                                                        jobSteps[node.id] = step
                                                        revision.value++
                                                    },
                                                )
                                            } finally {
                                                jobSteps.remove(node.id)
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
                            // The same addresses the DNS domain publishes for the management host.
                            val pins = effective.managementAddresses(node.id)
                            site = runDomain("tunnels", node.id) { tunnelRec.reconcile(ready, slot, pins) }
                            if (clustered) {
                                if (site != null) synchronizedPut(readyNodes, node.id, ready to site)
                                runDomain("exits", node.id) { exitRec.reconcile(ready, pins) }?.let { synchronizedPut(exitSites, node.id, it) }
                            } else if (discoveryOk && site != null) {
                                resources = runDomain("resources", node.id) { resourceRec.reconcile(ready, nd, pausedKeys) } ?: emptyList()
                                if (resources.isNotEmpty()) present[node.id] = resources.map { it.niceId }.toSet()
                            }
                        } else if (dryRun) {
                            val bp = resourceRec.displayBlueprint(nd.publicResources.filter { !it.paused }, nd.privateResources.filter { !it.paused }, cfg.siteNiceId(node.id))
                            log.info("resources", "[dry-run] blueprint: $bp", node.id)
                            nd.publicResources.forEach { diff.add("resources", node.id, it.niceId, "unknown", "${it.mode.wire} ${it.fullDomain ?: ":${it.proxyPort}"} -> ${it.target.host}:${it.target.port}") }
                        }
                        val probeHosts = effective.services
                            .filter { it.isHttpLike() && !it.paused && !it.hostname.startsWith("*") && it.servesNode(node.id) }
                            .map { it.hostname }
                        val o = runDomain("observe", node.id) { observeNode(ctx, node, ready, site, probeHosts) }
                        synchronizedPut(obs, node.id, o ?: NodeObservation(false, emptyList(), null, false))
                        synchronizedPut(online, node.id, site?.online)
                        if (!clustered || ready == null || site == null) synchronizedPut(health, node.id, serviceHealthFor(effective, node.id, resources, site))
                    }
                }.awaitAll()
            }
            if (clustered) {
                clusterReady = readyNodes.mapValues { it.value.first }
                exitNodes = readyNodes.mapNotNull { (id, v) -> v.second.exitNodeId?.let { it to id } }.toMap()
            }
            if (clustered && discoveryOk && readyNodes.isNotEmpty()) {
                // Through the first ready node in config order; each node then writes its own routes.
                val sites = readyNodes.mapValues { (_, v) -> v.second.niceId }
                val via = cfg.nodes.first { it.id in readyNodes }.id
                val actual = runDomain("resources", ResourceReconciler.CLUSTER_SCOPE) {
                    resourceRec.reconcileCluster(readyNodes.getValue(via).first, effective.nodes, sites, pausedKeys, ExitNodes.resources(cfg, exitSites, sites) + TunnelDns.resources(cfg, exitSites, sites))
                }
                if (actual != null) for ((id, rs) in readyNodes) {
                    val (ready, site) = rs
                    // What this node can serve: resources with a target on its own site.
                    val mine = actual.filter { r -> r.targets.any { it.siteNiceId == site.niceId } }
                    runDomain("resources", id) { resourceRec.writeRoutes(ready, effective.nodes.getValue(id), mine) }
                    present[id] = mine.map { it.niceId }.toSet()
                    health[id] = serviceHealthFor(effective, id, mine, site)
                }
            }
            // What departed nodes left in the cluster's Pangolin (their sites and exit nodes).
            if (clustered && !dryRun && readyNodes.isNotEmpty()) {
                val via = readyNodes.getValue(cfg.nodes.first { it.id in readyNodes }.id).first
                val known = cfg.nodes.map { it.id }.toSet() + state.snapshot().nodes.keys
                runDomain("cluster", null) { clusterRec.pruneDeparted(ctx, via, known) }
            }
            if (!dryRun) runDomain("tunnels", null) { tunnelRec.prune(cfg.nodes.filter { !state.node(it.id).pendingDecommission }.map { it.id }.toSet()) }

            // The cluster mesh (host-level, independent of each node's stack).
            meshStatus = if (cfg.cluster == null || dryRun) null
            else runDomain("mesh", null) { meshRec.reconcile(ctx, cfg.nodes, state.snapshot().nodes.mapNotNull { (id, s) -> s.tunnelSlot?.let { id to it } }.toMap()) } ?: meshStatus
            // The cluster's database layer, over the mesh.
            clusterStatus = if (cfg.cluster == null || dryRun) null
            else runDomain("cluster", null) {
                // Nodes follow the cluster image the homelab has (pinned to its registry digest).
                val image = cfg.cluster.image.let { ref ->
                    runCatching { docker.image(ref) }.getOrNull()?.takeIf { it.created.isNotEmpty() }?.let { MemberImage(it.pinned(ref), it.created) }
                }
                clusterRec.reconcile(ctx, cfg.nodes, state.snapshot().nodes.mapNotNull { (id, s) -> s.tunnelSlot?.let { id to it } }.toMap(), image)
            } ?: clusterStatus
            clusterStatus = clusterStatus?.let { cs ->
                val shared = state.snapshot().clusterPangolin
                val license = ClusterAlerts.License(
                    configured = cfg.pangolin.licenseKey() != null,
                    activated = shared?.licenseActivated == true,
                    rejected = shared?.licenseRejected != null,
                )
                cs.copy(alerts = ClusterAlerts.evaluate(cs, meshStatus, license, nowMillis()))
            }

            // 5. DNS
            if (discoveryOk) {
                val bunny = cfg.bunny.apiKey.resolve()?.let { BunnyClient(externalHttp, cfg.bunny.apiUrl, it) }
                val records = servableDns(effective, present)
                // The zone download is the pass's only external API call: skip it while the desired
                // records are unchanged and the last sync succeeded recently.
                val key = homegap.platform.Crypto.fnv64(records.toString() + pausedKeys.sorted() + state.snapshot().pauses + cfg.bunny)
                // A record a nameserver does not serve yet is checked again every pass until it does.
                val waitingForNameservers = dnsObs.any { it.diff?.startsWith(DnsReconciler.NOT_ON_EVERY_NAMESERVER) == true }
                val fresh = key == dnsKey && !waitingForNameservers && nowMillis() - dnsSyncedAt < cfg.reconcile.dnsCheck.inWholeMilliseconds
                if (!fresh || trigger.contains("dashboard")) {
                    val obs = runDomain("dns", null) { DnsReconciler(ctx, bunny, nameserverCheck).reconcile(records, pausedKeys) }
                    if (obs != null) {
                        dnsObs = obs
                        if (bunny != null) { dnsKey = key; dnsSyncedAt = nowMillis() }
                    } else {
                        dnsKey = null
                    }
                }
            }

            nodeObs = obs
            if (!dryRun) trackIsolation(cfg.nodes.filter { !state.node(it.id).pendingDecommission }.mapNotNull { obs[it.id]?.reachable }, nowMillis())
            siteOnline = online
            serviceHealth = health
            lastDiff = diff.all()
            if (!dryRun) {
                val current = snapshot()
                recordMetrics(cfg, current)
                attentionSeen = attentionSince(current.attention, attentionSeen)
            }
        } finally {
            val (changes, errors) = log.drainCounters()
            lastSummary = ReconcileSummaryDto(started, nowMillis(), trigger, errors == 0, changes, errors)
            reconciling = false
            revision.value++
            val duration = nowMillis() - started
            log.info(
                domain = "controller",
                message = "pass complete in ${duration}ms ($changes changes, $errors errors) trigger=$trigger",
                fields = mapOf(
                    "duration_ms" to duration.toString(),
                    "changes" to changes.toString(),
                    "errors" to errors.toString(),
                    "trigger" to trigger,
                ),
            )
            log.pass = null
            log.save()
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
                d.purpose == "root" -> nd.publicResources.filter { it.serviceKey == fdKey && it.fullDomain != fdWildcard && !Planner.isHealth(it.niceId) }
                d.purpose == "wildcard" -> nd.publicResources.filter { it.serviceKey == fdKey && (it.mode.wire == "tcp" || it.fullDomain == fdWildcard) }
                d.purpose.startsWith("service:") -> nd.publicResources.filter { it.serviceKey == d.purpose.removePrefix("service:") && !Planner.isHealth(it.niceId) }
                else -> emptyList()
            }.filter { !it.paused }.map { it.niceId }
            val missing = needed.filter { it !in have }
            if (missing.isNotEmpty()) log.warn("dns", "not steering ${d.fqdn} to this node: ${missing.joinToString()} missing on it", d.nodeId)
            missing.isEmpty()
        }

    /** When the homelab last lost contact with every node: (start, end or null while it lasts). */
    @kotlin.concurrent.Volatile private var isolation: Pair<Long, Long?>? = null

    /**
     * With two or more nodes, none reachable over SSH in a pass means the homelab itself lost its
     * way to them (two providers rarely fail at the same moment). Logged when it starts and ends,
     * and shown on the dashboard.
     */
    private fun trackIsolation(reachable: List<Boolean>, now: Long) {
        val current = isolation
        val next = isolationAfter(current, reachable, now)
        if (next == current) return
        isolation = next
        if (next?.second == null) {
            log.error("controller", "the homelab can't reach any node: check the homelab's internet connection (the nodes keep serving, and fail the database over if this lasts)")
        } else {
            log.change("controller", "the homelab reaches the nodes again after ${((next.second!! - next.first) / 60_000).coerceAtLeast(1)} min without any")
        }
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

    // ------------------------------------------------------------------ observability

    private suspend fun observeNode(
        ctx: ReconcileContext,
        node: NodeConfig,
        ready: ReadyNode?,
        site: PangolinSite?,
        probeHosts: List<String>,
    ): NodeObservation {
        if (ctx.dryRun) return NodeObservation(false, listOf(CheckDto("mode", Health.UNKNOWN, "dry-run: no remote access")), null, false)
        val checks = mutableListOf<CheckDto>()
        val serviceProbes = mutableMapOf<String, ServiceProbe>()
        var traffic: Pair<Double, Double>? = null
        val mgmt = node.managementHost(ctx.config.domain)
        val root = ctx.config.domain
        val script = $$"""
            |for s in pangolin gerbil traefik; do echo "C $s $(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $s 2>/dev/null || echo 'missing none')"; done
            |echo "D $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)"
            |probe() { c=$(echo | timeout 6 openssl s_client -connect 127.0.0.1:443 -servername "$1" 2>/dev/null | openssl x509 -noout -enddate -subject 2>/dev/null) || { echo "none"; return; }; end=$(echo "$c" | sed -n 's/^notAfter=//p'); sub=$(echo "$c" | sed -n 's/^subject=//p'); echo "$(date -d "$end" +%s 2>/dev/null || echo 0)|$sub"; }
            |echo "T mgmt $(probe $${shQuote(mgmt)})"
            |echo "T root $(probe $${shQuote(root)})"
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "U ok" || echo "U missing"
        """.trimMargin() + (if (ctx.config.cluster == null) "" else "\necho \"P \$(${ClusterProxy.CSV_CMD} | base64 -w0)\"") +
            serviceProbeScript(probeHosts) + "\n" + homegap.provision.NodeTemplates.TRAFFIC_CMD
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
                    "D" -> checks += CheckDto("docker", if (parts.getOrNull(1) == "none") Health.DOWN else Health.OK, parts.getOrNull(1).orEmpty())
                    "U" -> checks += CheckDto("gerbil-udp", if (parts.getOrNull(1) == "ok") Health.OK else Health.DOWN, "51820/udp")
                    "H" -> parseServiceProbe(parts)?.let { (host, probe) -> serviceProbes[host] = probe }
                    "M" -> {
                        val requests = parts.getOrNull(1)?.toDoubleOrNull()
                        val bytes = parts.getOrNull(2)?.toDoubleOrNull()
                        if (requests != null && bytes != null) traffic = requests to bytes
                    }
                    "P" -> {
                        val csv = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }
                        checks += ClusterProxy.check(csv, proxyHistory.getOrPut(node.id) { mutableListOf() }, nowMillis(), clusterRec.plannedMoves)
                    }
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
                            checks += CheckDto("tls-$which", h, "$subject${days?.let { ", $it days left" }.orEmpty()}")
                        }
                    }
                }
            }
        }
        // The Pangolin API probe: a request through the edge to its Pangolin, timed.
        val apiStarted = nowMillis()
        val apiOk = ready != null && runCatching {
            ready.api.getOrg(ctx.config.pangolin.orgId)
        }.onFailure {
            if (it is CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }.isSuccess
        val apiMs = (nowMillis() - apiStarted).toDouble()
        checks += when {
            !apiOk -> CheckDto("pangolin-api", Health.DOWN, "not reachable", threshold = API_PROBE_SLOW_MS, unit = "ms")
            else -> CheckDto(
                name = "pangolin-api",
                health = if (apiMs > API_PROBE_SLOW_MS) Health.DEGRADED else Health.OK,
                detail = if (apiMs > API_PROBE_SLOW_MS) "slow" else "integration API",
                value = apiMs,
                threshold = API_PROBE_SLOW_MS,
                unit = "ms",
            )
        }
        val shared = state.snapshot().clusterPangolin
        if (ctx.config.cluster != null) {
            // One license for the whole cluster (stored in the shared database). A missing activation
            // is a cluster alert, not a problem with this node.
            if (ctx.config.pangolin.licenseKey() != null) when {
                shared?.licenseActivated == true && node.id in shared.licenseRecheck -> CheckDto("ee-license", Health.DEGRADED, "activated; this node has not re-read it yet")
                shared?.licenseActivated == true -> CheckDto("ee-license", Health.OK, "the cluster's license is activated")
                shared?.licenseRejected != null -> CheckDto("ee-license", Health.DEGRADED, "rejected: key bound to another instance")
                else -> null
            }?.let { checks += it }
        } else if (node.licenseKey(ctx.config.pangolin) != null) {
            val ns = state.node(node.id)
            checks += when {
                ns.licenseActivated -> CheckDto("ee-license", Health.OK, "activated")
                ns.facts["licenseRejected"] != null -> CheckDto("ee-license", Health.DEGRADED, "rejected: key bound to another instance")
                else -> CheckDto("ee-license", Health.UNKNOWN, "not activated")
            }
        }
        site?.newtVersion?.let { newtVersions[node.id] = it }
        checks += CheckDto("tunnel", when (site?.online) { true -> Health.OK; false -> Health.DOWN; null -> Health.UNKNOWN }, site?.let { "site ${it.niceId}${it.newtVersion?.let { v -> ", newt $v" }.orEmpty()}" } ?: "no site")
        return NodeObservation(
            reachable = reachable,
            checks = checks,
            certificate = cert,
            apiOk = apiOk,
            serviceProbes = serviceProbes,
            traffic = traffic,
        )
    }

    private fun serviceHealthFor(plan: DesiredState, nodeId: String, resources: List<PangolinResource>, site: PangolinSite?): Map<String, ServiceNodeHealthDto> {
        val nd = plan.nodes[nodeId] ?: return emptyMap()
        val byNice = resources.associateBy { it.niceId }
        val out = mutableMapOf<String, ServiceNodeHealthDto>()
        for (s in plan.services) {
            if (!s.servesNode(nodeId)) continue
            val res = nd.publicResources.filter { it.serviceKey == s.key && !Planner.isHealth(it.niceId) } +
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
            val tunnel = TunnelFiles.readStatus(cfg.tunnelsDir, id)
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
                region = n?.region.orEmpty(),
                provider = n?.provider.orEmpty(),
                host = n?.host.orEmpty(),
                managementHost = n?.managementHost(cfg.domain).orEmpty(),
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
                facts = (ns?.facts ?: emptyMap()).filterKeys { it in setOf("os", "arch", "kernel", "docker", "mem_kb", "vcpu", "disk_free_gb", "tcp_cc", "qdisc", "edge_tcp") } +
                    (tunnel?.let { mapOf("newt_restarts" to it.restarts.toString()) } ?: emptyMap()),
                job = jobs[id]?.takeIf { it.second.isActive }?.let { if (it.first == NodeReconciler.Kind.UPDATE) "updating" else "provisioning" },
                updateWaiting = waiting[id],
                update = ns?.update?.let { NodeUpdateDto(it.status, it.error, it.at, it.rolledBack) },
                stack = ns?.stackHash?.take(8),
                targetStack = targetStacks[id]?.takeIf { it != ns?.stackHash }?.take(8),
                jobStep = jobSteps[id],
                runs = ns?.runs.orEmpty().map { run ->
                    homegap.api.NodeRunDto(
                        kind = run.kind,
                        startedAt = run.startedAt,
                        finishedAt = run.finishedAt,
                        ok = run.ok,
                        error = run.error,
                        stack = run.stack?.take(8),
                        rolledBack = run.rolledBack,
                    )
                },
                location = n?.let { node ->
                    node.location?.let { homegap.api.PlaceDto(it.latitude, it.longitude, it.name) }
                        ?: node.effectiveLocation()?.let { homegap.api.PlaceDto(it.latitude, it.longitude, null, estimated = true) }
                },
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
                    s.protocol.isRaw -> "raw"
                    else -> "dedicated"
                },
                paused = s.paused || s.key in st.pauses.services,
                name = s.name,
                ports = s.ports.map { it.toString() },
                labels = s.labels,
                perNode = cfg.nodes.filter { s.servesNode(it.id) }.map { n ->
                    val health = serviceHealth[n.id]?.get(s.key) ?: ServiceNodeHealthDto(n.id, Health.UNKNOWN)
                    health.withProbe(nodeObs[n.id]?.serviceProbes?.get(s.hostname))
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
                changedAt = log.lastRecordChange(o.desired?.fqdn ?: o.record.name),
            )
        }
        val tunnelsDir = cfg.tunnelsDir
        val tunnels = cfg.nodes.map { n ->
            val t = TunnelFiles.readStatus(tunnelsDir, n.id)
            val fresh = t != null && nowMillis() - t.updatedAt < 60_000
            TunnelStatusDto(
                nodeId = n.id,
                siteNiceId = cfg.siteNiceId(n.id),
                endpoint = "https://${n.managementHost(cfg.domain)}",
                up = siteOnline[n.id] == true,
                processRunning = fresh && t.running,
                siteOnline = siteOnline[n.id],
                restarts = t?.restarts ?: 0,
                lastExit = t?.lastExit,
                bytesIn = t?.bytesIn,
                bytesOut = t?.bytesOut,
                updatedAt = t?.updatedAt,
                exits = t?.exits.orEmpty().map { homegap.api.TunnelExitDto(it.at, it.reason) },
                newtVersion = newtVersions[n.id],
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
            cluster = clusterStatus.takeIf { cfg.cluster != null },
            homelab = cfg.homelab.location?.let { homegap.api.PlaceDto(it.latitude, it.longitude, it.name) },
            homelabIsolation = isolation?.let { (start, end) -> homegap.api.IsolationDto(start, end) },
            speedRun = speedRun,
            homelabContainers = homelabContainers,
        ).let { snapshot ->
            snapshot.copy(attention = attentionItems(snapshot, attentionSeen))
        }
    }

    /**
     * Takes this pass's sample of each edge for the graphs: the Pangolin API probe, the node's
     * mesh round trip to the homelab, and the tunnel's throughput since the last sample.
     */
    private suspend fun recordMetrics(cfg: AppConfig, snapshot: StatusSnapshot) {
        val now = nowMillis()
        val tunnelsDir = cfg.tunnelsDir
        val points = cfg.nodes.flatMap { node ->
            val api = nodeObs[node.id]?.checks?.firstOrNull { it.name == "pangolin-api" }
            val mesh = snapshot.mesh?.members
                ?.firstOrNull { it.id == node.id }
                ?.peers?.firstOrNull { it.id == "homelab" }
                ?.probe
            val tunnel = TunnelFiles.readStatus(tunnelsDir, node.id)
            val bytesIn = tunnel?.bytesIn
            val bytesOut = tunnel?.bytesOut
            val previous = tunnelBytes[node.id]
            val rates = if (previous != null && bytesIn != null && bytesOut != null && now > previous.first) {
                val seconds = (now - previous.first) / 1000.0
                // A restarted connector starts counting again: no rate for that sample.
                val inRate = (bytesIn - previous.second).takeIf { it >= 0 }?.let { it * 8 / 1e6 / seconds }
                val outRate = (bytesOut - previous.third).takeIf { it >= 0 }?.let { it * 8 / 1e6 / seconds }
                inRate to outRate
            } else {
                null
            }
            if (bytesIn != null && bytesOut != null) {
                tunnelBytes[node.id] = Triple(now, bytesIn, bytesOut)
            }
            val traffic = nodeObs[node.id]?.traffic
            val previousTraffic = trafficCounters[node.id]
            val trafficRates = if (traffic != null && previousTraffic != null && now > previousTraffic.first) {
                val seconds = (now - previousTraffic.first) / 1000.0
                // Traefik restarted (counters back at zero): no rate for that sample.
                val requests = (traffic.first - previousTraffic.second).takeIf { it >= 0 }?.let { it / seconds }
                val egress = (traffic.second - previousTraffic.third).takeIf { it >= 0 }?.let { it * 8 / 1e6 / seconds }
                requests to egress
            } else {
                null
            }
            if (traffic != null) {
                trafficCounters[node.id] = Triple(now, traffic.first, traffic.second)
            }
            buildList {
                add(MetricPointDto(node.id, EdgeMetrics.API, api?.value))
                if (trafficRates != null) {
                    add(MetricPointDto(node.id, EdgeMetrics.REQUESTS, trafficRates.first))
                    add(MetricPointDto(node.id, EdgeMetrics.EGRESS, trafficRates.second))
                }
                if (snapshot.mesh != null) {
                    add(MetricPointDto(node.id, EdgeMetrics.MESH, mesh?.rttMs))
                }
                if (rates != null) {
                    add(MetricPointDto(node.id, EdgeMetrics.TUNNEL_IN, rates.first))
                    add(MetricPointDto(node.id, EdgeMetrics.TUNNEL_OUT, rates.second))
                }
            }
        }
        metrics.record(
            atSeconds = now / 1000,
            points = points,
        )
        metrics.retain(cfg.nodes.map { it.id }.toSet())
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
                        put("blueprint", rr.displayBlueprint(n.publicResources.filter { !it.paused }, n.privateResources.filter { !it.paused }, config.siteNiceId(id)))
                        putJsonArray("sniRoutes") { n.sniRoutes.forEach { add(homegap.provision.NodeTemplates.sniRule(it)) } }
                        putJsonArray("rawPorts") { n.rawPorts.forEach { add(it.entryPoint) } }
                    }
                }
            }
            putJsonArray("dns") {
                plan.dns.forEach { d -> add("${d.type} ${d.fqdn} -> ${d.value} [${d.nodeId}] ${d.purpose}${if (d.geo) " geo" else ""} monitor=${d.monitor}") }
            }
        }
        return PRETTY_JSON.encodeToString(obj)
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
    /** The homelab ↔ node test running now, as far as it has got; null when none runs. */
    fun speedLive(): homegap.api.SpeedTestLiveDto? = speedTokens.live.snapshot()

    suspend fun runTunnelSpeedTest(nodeId: String, streams: Int? = null): SpeedTestResultDto {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        val node = cfg.node(nodeId) ?: throw IllegalArgumentException("unknown node $nodeId")
        check(!dryRun) { "speed tests need remote access (dry-run is on)" }
        check(speedRunning.add(nodeId)) { "a speed test for $nodeId is already running" }
        val (token, _) = speedTokens.issue(5 * 60_000L)
        speedTokens.live.start(token, nodeId)
        // Wakes the event streams, which then send the live gauge a few times a second.
        revision.value++
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
            log.info("speedtest", "tunnel: ping ${p.pingMs?.let { fmt1(it) } ?: "-"} ms, jitter ${p.jitterMs?.let { fmt1(it) } ?: "-"} ms, down ${p.downMbps?.let { fmt1(it) } ?: "-"} Mbps, up ${p.upMbps?.let { fmt1(it) } ?: "-"} Mbps ($n streams)${err?.let { " ($it)" }.orEmpty()}", nodeId)
            return result
        } finally {
            speedTokens.live.stop(token)
            speedTokens.revoke(token)
            speedRunning.remove(nodeId)
        }
    }

    private fun fmt1(d: Double): String = ((d * 10).toLong() / 10.0).toString()

    @Volatile private var speedRun: SpeedRunDto? = null
    private var speedRunJob: Job? = null

    /**
     * Tests every node, one at a time, on the orchestrator: the run goes on if the dashboard is
     * closed, and its progress is in the snapshot ([StatusSnapshot.speedRun]).
     */
    fun startSpeedRun(streams: Int?): String {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        check(speedRunJob?.isActive != true) { "a test of all nodes is already running" }
        val scope = checkNotNull(jobScope) { "the controller is not running" }
        val nodes = cfg.nodes.map { it.id }
        require(nodes.isNotEmpty()) { "there are no nodes to test" }
        val count = (streams ?: cfg.speedtest.streams).coerceIn(1, homegap.speedtest.TunnelSpeedTest.MAX_STREAMS)
        speedRun = SpeedRunDto(streams = count, nodes = nodes, current = nodes.first(), startedAt = nowMillis())
        revision.value++
        speedRunJob = scope.launch {
            try {
                for (id in nodes) {
                    speedRun = speedRun?.copy(current = id)
                    revision.value++
                    val ok = runCatching { runTunnelSpeedTest(id, count).error == null }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(false)
                    speedRun = speedRun?.let { run ->
                        if (ok) run.copy(done = run.done + id) else run.copy(done = run.done + id, failed = run.failed + id)
                    }
                    revision.value++
                }
            } finally {
                speedRun = speedRun?.copy(current = null, finishedAt = nowMillis(), cancelled = speedRun?.done?.size != nodes.size)
                revision.value++
            }
        }
        log.info("speedtest", "testing all ${nodes.size} nodes, one at a time ($count streams)")
        return "Testing all ${nodes.size} nodes, one at a time"
    }

    /** Stops a run after the node being tested now. */
    fun cancelSpeedRun(): String {
        val job = speedRunJob?.takeIf { it.isActive } ?: return "No test of all nodes is running"
        job.cancel()
        return "Stopped the test of all nodes"
    }

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
        log.change("controller", "${if (paused) "paused" else "resumed"} $scope${id?.let { " $it" }.orEmpty()}")
        if (!paused) trigger("resume")
        return "${if (paused) "Paused" else "Resumed"} $scope${id?.let { " $it" }.orEmpty()}"
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

    /** Sets (or with null, clears) where the homelab is, for the maps. Rewrites the config file. */
    fun setHomelabLocation(place: homegap.api.PlaceDto?): String {
        require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        val location = place?.let {
            require(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0) { "coordinates are out of range" }
            homegap.config.GeoLocation(it.latitude, it.longitude, it.name?.trim()?.ifBlank { null })
        }
        val next = config.copy(homelab = config.homelab.copy(location = location))
        ConfigLoader.save(configPath, next)
        config = next
        revision.value++
        log.change("controller", if (location == null) "cleared the homelab's location" else "set the homelab's location${location.name?.let { " to $it" }.orEmpty()}")
        return if (location == null) "Homelab location cleared" else "Homelab location saved"
    }

    /**
     * Sets where node [id] is: its coordinates steer geo DNS and place it on the maps. Rewrites
     * its config entry and runs a pass so DNS follows.
     */
    fun setNodeLocation(id: String, place: homegap.api.PlaceDto): String {
        require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        require(place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0) { "coordinates are out of range" }
        val node = config.node(id) ?: throw IllegalArgumentException("unknown node $id")
        val location = homegap.config.GeoLocation(place.latitude, place.longitude, place.name?.trim()?.ifBlank { null })
        val next = config.copy(nodes = config.nodes.map { if (it.id == id) node.copy(location = location) else it })
        ConfigLoader.save(configPath, next)
        config = next
        log.change("controller", "moved $id to ${location.name ?: "${location.latitude}, ${location.longitude}"}", id, fields = mapOf("node" to id))
        trigger("config")
        return "$id is now in ${location.name ?: "the picked place"}; DNS follows on the next pass"
    }

    /** Things that can be undone for a while: an id each, with how to undo it. */
    private class Undoable(val expiresAt: Long, val undo: suspend () -> String)

    private val undoables = mutableMapOf<String, Undoable>()

    /** Keeps [undo] for [ttlMs] and returns the id that runs it ([undo]). */
    fun offerUndo(ttlMs: Long, undo: suspend () -> String): String {
        val now = nowMillis()
        undoables.entries.removeAll { it.value.expiresAt < now }
        val id = homegap.platform.Crypto.token(12)
        undoables[id] = Undoable(now + ttlMs, undo)
        return id
    }

    /** Undoes an earlier action, while it still can be. */
    suspend fun undo(id: String): String {
        val undoable = undoables.remove(id)?.takeIf { it.expiresAt >= nowMillis() }
            ?: throw IllegalArgumentException("that can no longer be undone")
        return undoable.undo()
    }

    /** Removes [id] from the config (DNS drains at once); it can be added back for a day. Returns the message and undo id. */
    suspend fun removeNode(id: String): Pair<String, String> {
        require(config.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        val node = config.node(id) ?: throw IllegalArgumentException("unknown node $id")
        val next = config.copy(nodes = config.nodes - node)
        ConfigLoader.save(configPath, next)
        config = next
        state.updateNode(id) { it.copy(pendingDecommission = true, facts = it.facts + nodeFacts(node)) }
        log.change("controller", "removed node $id from config (pending decommission)", id)
        trigger("config")
        val undo = offerUndo(ttlMs = UNDO_NODE_MS) {
            check(state.node(id).pendingDecommission) { "$id was already decommissioned" }
            addNode(node)
            "Added $id back"
        }
        return "Node $id removed from config. DNS drains on the next pass; confirm decommission to stop its stack." to undo
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
            val n = NodeConfig(id = id, host = host, sshUser = ns.facts["cfg.user"] ?: "root", sshPort = ns.facts["cfg.port"]?.toIntOrNull() ?: 22, sshKeyFile = ns.facts["cfg.key"].orEmpty(), publicIpv4 = "0.0.0.0")
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

/**
 * The homelab's isolation after a pass that reached [reachable] nodes: it starts when two or more
 * nodes were observed and none answered, and ends at the first pass that reaches one.
 */
internal fun isolationAfter(current: Pair<Long, Long?>?, reachable: List<Boolean>, now: Long): Pair<Long, Long?>? {
    if (reachable.size < 2) return current
    val cut = reachable.none { it }
    val ongoing = current != null && current.second == null
    return when {
        cut && !ongoing -> now to null
        !cut && ongoing -> current!!.first to now
        else -> current
    }
}

/** Above this, the Pangolin API probe is slow and its node degraded. */
private const val API_PROBE_SLOW_MS = 300.0

/** Above this, a service request through an edge is slow. */
internal const val SERVICE_SLOW_MS = 1000.0

/**
 * Lines for the node's observe script: one request per hostname through the node's own proxy
 * (`--resolve` to 127.0.0.1), printing `H <host> <code> <seconds>`. Each waits at most 3 s.
 */
internal fun serviceProbeScript(hosts: List<String>): String = hosts.joinToString(separator = "") { host ->
    val quoted = shQuote(host)
    "\necho \"H $host \$(curl -s -o /dev/null --max-time 3 --resolve $quoted:443:127.0.0.1 " +
        "-w '%{http_code} %{time_total}' https://$quoted/ 2>/dev/null || echo '000 0')\""
}

/** Reads one `H <host> <code> <seconds>` line (split in three by the caller). */
internal fun parseServiceProbe(parts: List<String>): Pair<String, Controller.ServiceProbe>? {
    val host = parts.getOrNull(1) ?: return null
    val result = parts.getOrNull(2)?.split(' ') ?: return null
    val code = result.getOrNull(0)?.toIntOrNull() ?: return null
    val seconds = result.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    return host to Controller.ServiceProbe(
        code = code,
        ms = seconds * 1000,
    )
}

/** This edge's health with its request [probe] folded in: no answer or a 5xx fails it, a slow one degrades it. */
internal fun ServiceNodeHealthDto.withProbe(probe: Controller.ServiceProbe?): ServiceNodeHealthDto {
    if (probe == null) return this
    val measured = copy(
        ms = probe.ms.takeIf { probe.code != 0 },
        code = probe.code,
    )
    return when {
        probe.code == 0 -> measured.copy(health = Health.DOWN, detail = "no answer", ms = null)
        probe.code >= 500 -> measured.copy(health = Health.DOWN, detail = "HTTP ${probe.code}")
        probe.ms > SERVICE_SLOW_MS && health == Health.OK -> measured.copy(health = Health.DEGRADED, detail = "slow")
        else -> measured
    }
}

/** How long removing a node can be undone (until it is decommissioned). */
private const val UNDO_NODE_MS = 24 * 3_600_000L

/** The trigger for one pass that applies changes while paused. */
internal const val APPLY_ONCE = "apply-once"
