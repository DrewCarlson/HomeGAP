package homegap.reconcile

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homegap.VERSION
import homegap.acme.CertMaterial
import homegap.acme.CertNames
import homegap.acme.CertProbes
import homegap.acme.CertificateStatus
import homegap.acme.NodeCertificate
import homegap.acme.renewalIsLate
import homegap.api.*
import homegap.clients.*
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.AppConfig
import homegap.config.ConfigLoader
import homegap.config.GeoLocation
import homegap.config.NodeConfig
import homegap.config.clusterConfigDir
import homegap.config.meshDir
import homegap.config.parseDuration
import homegap.config.sharedDirProblems
import homegap.config.tunnelsDir
import homegap.di.ExternalHttp
import homegap.di.LocalHttp
import homegap.di.RunOptions
import homegap.discovery.DiscoveryMemory
import homegap.discovery.DockerClient
import homegap.discovery.DockerContainerSummary
import homegap.discovery.ServiceSpec
import homegap.discovery.TraefikApi
import homegap.discovery.TraefikRouter
import homegap.discovery.concernsHomegap
import homegap.discovery.declaresService
import homegap.discovery.traefikApiUrls
import homegap.model.DesiredDnsRecord
import homegap.model.DesiredState
import homegap.model.Planner
import homegap.model.isHttpLike
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.platform.queryNameserver
import homegap.platform.runCatchingCancellable
import homegap.provision.ClusterProxy
import homegap.provision.ExitNodes
import homegap.provision.NodeTemplates
import homegap.provision.TunnelDns
import homegap.speedtest.SpeedTestStore
import homegap.speedtest.SpeedTestTokens
import homegap.speedtest.TunnelSpeedTest
import homegap.speedtest.newResultId
import homegap.state.ControllerState
import homegap.state.NodeState
import homegap.state.StateStore
import homegap.tunnels.TunnelFiles
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The reconciler: moves the actual state to the desired state (labels and config) in six
 * independent domains. A pass starts on a Docker event (debounced), after each resync interval
 * and on a request from the dashboard. A failure in one domain never blocks the others.
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
    val speedTokens: SpeedTestTokens,
    val speedStore: SpeedTestStore,
) {
    private val configPath = options.configPath
    private val dryRunOverride = options.dryRun

    @Volatile var config: AppConfig = initialConfig
        private set
    private val passMutex = Mutex()
    private val passTimes = PassTimes()

    /**
     * The event loop that owns the state of the controller. The maps and lists below are plain,
     * and only code on this loop reads or changes them. The HTTP server has its own threads:
     *
     * - A request that reads gets a finished value: [status], [config], [desired], [lastDiff].
     *   It never waits for the loop.
     * - A request that changes something goes to the loop through [onLoop].
     */
    @Volatile private var loop: CoroutineDispatcher? = null

    private val published = MutableStateFlow<StatusSnapshot?>(null)

    /** The newest status of the system. The loop builds it, and each reader gets the same value. */
    val status: StateFlow<StatusSnapshot?> = published

    /** Runs [action] on the loop, then publishes the status, so the caller reads its own change. */
    private suspend fun <T> onLoop(action: suspend () -> T): T {
        val run: suspend () -> T = { action().also { publishStatus() } }
        return loop?.let { withContext(it) { run() } } ?: run()
    }

    /** Builds the status on the loop and publishes it. */
    suspend fun refreshStatus(): StatusSnapshot = loop?.let { withContext(it) { publishStatus() } } ?: publishStatus()

    private fun publishStatus(): StatusSnapshot = snapshot().also { published.value = it }

    /**
     * Keeps [status] current: after each change, and every few seconds while a dashboard listens,
     * because the tunnel status comes from files with no change signal.
     */
    private suspend fun keepStatusCurrent(scope: CoroutineScope) {
        val wake = changes.conflate().produceIn(scope)
        while (true) {
            publishStatus()
            val listening = published.subscriptionCount.value > 0
            withTimeoutOrNull(if (listening) STATUS_RECHECK else STATUS_IDLE) { wake.receive() }
            // Changes come in groups (a pass writes many log entries): one build for the group.
            delay(STATUS_SETTLE)
        }
    }

    /** Increases at each change of the status in memory (a pass, a background job). */
    private val revision = MutableStateFlow(0L)

    /** Emits after each change that the dashboard can show. */
    val changes: Flow<Long>
        get() = merge(revision, state.revision, log.revision)
    private val triggers = Channel<String>(Channel.UNLIMITED)
    private val forceProvision = mutableSetOf<String>()

    /** Background provisioning per node: the kind of work and its job. */
    private val jobs = mutableMapOf<String, Pair<NodeReconciler.Kind, Job>>()

    /** Why the pending provisioning of a node does not run yet. The dashboard shows it. */
    private val waiting = mutableMapOf<String, String>()
    private var jobScope: CoroutineScope? = null
    private val speedRunning = mutableSetOf<String>()

    val dryRun: Boolean get() = dryRunOverride || config.reconcile.dryRun

    // The latest results. The dashboard reads them.
    @Volatile var desired: DesiredState? = null
        private set

    @Volatile var lastDiff: List<DiffItemDto> = emptyList()
        private set

    @Volatile var lastSummary: ReconcileSummaryDto? = null
        private set

    @Volatile var reconciling: Boolean = false
        private set

    @Volatile private var nodeObs: Map<String, NodeObservation> = emptyMap()

    @Volatile private var serviceHealth: Map<String, Map<String, ServiceNodeHealthDto>> = emptyMap()

    @Volatile private var dnsObs: List<DnsReconciler.Observed> = emptyList()

    @Volatile private var siteOnline: Map<String, Boolean?> = emptyMap()
    private val meshRec by lazy { MeshReconciler(HomelabMesh(config.meshDir)) }

    @Volatile private var meshStatus: MeshStatusDto? = null
    private val nodeCert by lazy {
        NodeCertificate("${config.dataDir}/acme").also { it.onChange = { revision.value++ } }
    }

    /** The last TLS probes of each node. A node that does not answer keeps its last ones. */
    @Volatile private var certProbes: Map<String, CertificateStatus.NodeProbes> = emptyMap()

    /** The CAA records of the domain from the last zone read. Null before one. */
    @Volatile private var caaLive: Map<String, List<String>>? = null

    /** The error of each Bunny zone that the last DNS sync did not reach. */
    @Volatile private var dnsZoneErrors: Map<String, String> = emptyMap()
    private val discoveryMemory by lazy { DiscoveryMemory("${config.dataDir}/discovery.json") }
    private val heldContainers = mutableSetOf<String>()

    private val clusterRec by lazy {
        ClusterReconciler(
            dataDir = config.dataDir,
            http = localHttp,
            configDir = config.clusterConfigDir,
            inUse = {
                val stored = state.snapshot()
                stored.clusterPangolin?.bootstrapped == true || stored.nodes.values.any { it.meshPublicKey != null }
            },
        )
    }

    /** The cluster's reachable Pangolins as of the last pass, and which node each exit node is. */
    @Volatile private var clusterReady: Map<String, ReadyNode> = emptyMap()

    @Volatile private var exitNodes: Map<Int, String> = emptyMap()

    /** The recent cluster proxy readings of each node, to count lost primaries. */
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
     * Reads the routers of every Traefik API that a service names (`homegap.private_middleware`).
     * Null when one has never answered: its private paths are unknown, so discovery counts as
     * failed and does not delete them.
     */
    private suspend fun traefikRouters(containers: List<DockerContainerSummary>): Map<String, List<TraefikRouter>>? {
        val urls = containers.flatMap { traefikApiUrls(it.name, it.labels) }.distinct()
        var complete = true
        for (url in urls) {
            runCatchingCancellable {
                routerCache[url] = traefikApi.routers(url)
                if (routerFailing.remove(url)) {
                    log.info("discovery", "the Traefik API at $url answers again")
                }
            }.onFailure { e ->
                if (routerFailing.add(url)) {
                    val consequence = when (url) {
                        in routerCache -> " (keeping the routers it last reported)"
                        else -> " (services and private paths are left as they are until it answers)"
                    }
                    log.warn("discovery", "cannot read the Traefik API at $url: ${e.message}$consequence")
                }
                if (url !in routerCache) complete = false
            }
        }
        if (urls.isNotEmpty()) {
            val allAnswered = urls.all { it in routerCache }
            homelabRouters = urls.mapNotNull { routerCache[it] }.flatten().takeIf { allAnswered }
        }
        return if (complete) routerCache.filterKeys { it in urls } else null
    }

    /** Moves the database primary to [to] now (dashboard). See [ClusterReconciler.switchover]. */
    suspend fun switchPrimary(to: String): String = onLoop { switchPrimaryOnLoop(to) }

    private suspend fun switchPrimaryOnLoop(to: String): String {
        check(config.cluster != null && !dryRun) { "the cluster is not enabled" }
        val message = clusterRec.switchover(to)
        log.change("cluster", "$message (requested from the dashboard)")
        trigger("dashboard:switchover")
        return message
    }

    /** Moves the primaries off the homelab and holds them there (see [ClusterReconciler.beginMaintenance]). */
    suspend fun beginMaintenance(holdMinutes: Int): String = onLoop { beginMaintenanceOnLoop(holdMinutes) }

    private suspend fun beginMaintenanceOnLoop(holdMinutes: Int): String {
        val cluster = config.cluster
        check(cluster != null && !dryRun) { "the cluster is not enabled" }
        // No pass right away: Pangolin's connections are still moving to the new primary.
        val message = clusterRec.beginMaintenance(cluster, holdMinutes.coerceIn(5, 240) * 60_000L)
        log.change("cluster", message)
        return message
    }

    suspend fun endMaintenance(): String = onLoop { endMaintenanceOnLoop() }

    private fun endMaintenanceOnLoop(): String {
        check(config.cluster != null) { "the cluster is not enabled" }
        val message = clusterRec.endMaintenance()
        log.change("cluster", message)
        trigger("maintenance")
        return message
    }

    /**
     * A reachable Pangolin of the cluster (the first ready node in config order) and the exit node
     * map, for dashboard actions on its users and devices. Null without a cluster or a ready node.
     */
    fun clusterPangolin(): Pair<ReadyNode, Map<Int, String>>? {
        if (config.cluster == null) return null
        val ready = clusterReady
        val first = config.nodes.firstOrNull { it.id in ready } ?: return null
        return ready.getValue(first.id) to exitNodes
    }

    @Volatile private var clusterStatus: ClusterStatusDto? = null

    /** Desired-records hash and time of the last successful DNS sync (see the DNS step). */
    private var dnsKey: String? = null
    private var dnsSyncedAt = 0L

    /** Whether each zone nameserver serves the records yet. It remembers matches across passes. */
    private val nameserverCheck = NameserverCheck({ nameserver, name, type -> queryNameserver(nameserver, name, type) })

    data class NodeObservation(
        val reachable: Boolean,
        val checks: List<CheckDto>,
        val certificate: CertificateDto?,
        val apiOk: Boolean,
        /** Each service hostname requested through this node's proxy: status code and time. */
        val serviceProbes: Map<String, ServiceProbe> = emptyMap(),
        /** Traefik's request and response-byte counters, summed over entry points. Null without metrics. */
        val traffic: Pair<Double, Double>? = null,
        /** The TLS probe of each public name through this node, and its installed certificate. */
        val certProbes: CertificateStatus.NodeProbes? = null,
    )

    /** One request to a service through a node's proxy. [code] is 0 when it got no answer. */
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

    /** The stack that a pending update or an update in progress brings to each node. */
    private val targetStacks = mutableMapOf<String, String>()

    /** Tunnel byte counters at the previous sample, for throughput: node id to (time, in, out). */
    private val tunnelBytes = mutableMapOf<String, Triple<Long, Long, Long>>()

    /** Traefik counters at the previous sample: node id to (time, requests, response bytes). */
    private val trafficCounters = mutableMapOf<String, Triple<Long, Double, Double>>()

    /** The fastest Pangolin API probe of each node since the start, in ms. */
    private val apiBest = mutableMapOf<String, Double>()

    fun trigger(reason: String) {
        triggers.trySend(reason)
    }

    suspend fun requestProvision(nodeId: String) = onLoop { requestProvisionOnLoop(nodeId) }

    private fun requestProvisionOnLoop(nodeId: String) {
        forceProvision += nodeId
        trigger("provision:$nodeId")
    }

    /** Clears a failed or skipped update and applies it to [nodeId] now, with rollback. */
    suspend fun retryUpdate(nodeId: String): String = onLoop { retryUpdateOnLoop(nodeId) }

    private suspend fun retryUpdateOnLoop(nodeId: String): String {
        requireNotNull(config.node(nodeId)) { "unknown node $nodeId" }
        check(jobs[nodeId]?.second?.isActive != true) { "$nodeId is already being provisioned" }
        state.updateNode(nodeId) { it.copy(update = null) }
        log.change("nodes", "update retry requested", nodeId)
        requestProvision(nodeId)
        return "Retrying the update on $nodeId"
    }

    /** Releases the rollout hold caused by [nodeId]: it stays on its current stack. */
    suspend fun skipUpdate(nodeId: String): String = onLoop { skipUpdateOnLoop(nodeId) }

    private suspend fun skipUpdateOnLoop(nodeId: String): String {
        val update = checkNotNull(state.node(nodeId).update) { "$nodeId has no failed update" }
        state.updateNode(nodeId) { it.copy(update = update.copy(status = "skipped")) }
        log.change("nodes", "update skipped; the rollout continues without this node", nodeId)
        trigger("skip:$nodeId")
        return "Skipped the update on $nodeId; other nodes continue"
    }

    suspend fun run(scope: CoroutineScope, once: Boolean) {
        loop = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
        config.sharedDirProblems().forEach { log.error("controller", it) }
        if (once) {
            reconcileOnce("startup")
            return
        }
        jobScope = scope
        scope.launch { keepStatusCurrent(scope) }
        scope.launch { watchDocker() }
        scope.launch {
            while (isActive) {
                delay(config.reconcile.resync)
                trigger("periodic")
            }
        }
        trigger("startup")
        for (reason in triggers) {
            // A burst of triggers gives one pass.
            val reasons = mutableSetOf(reason)
            while (true) {
                reasons += triggers.tryReceive().getOrNull() ?: break
            }
            runCatchingCancellable { reconcileOnce(reasons.joinToString(",")) }
                .onFailure { log.error("controller", "reconcile pass crashed: ${it.message}") }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchDocker() {
        val filters = mapOf(
            "type" to listOf("container"),
            "event" to listOf("start", "stop", "die", "destroy", "update", "rename"),
        )
        while (true) {
            runCatchingCancellable {
                docker.streamEvents(filters)
                    .filter { it.concernsHomegap() }
                    .debounce(config.reconcile.debounceDuration)
                    .collect { event -> trigger("docker:${event.action}") }
            }.onFailure {
                log.warn("discovery", "Docker event stream disconnected (${it.message}); reconnecting in 5s")
            }
            delay(DOCKER_RECONNECT_DELAY)
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
        passTimes.drain()
        try {
            reloadConfig()
            val cfg = config
            val diff = DiffCollector()
            val applyOnce = APPLY_ONCE in trigger.split(',')
            val ctx = ReconcileContext(
                config = cfg,
                state = state,
                log = log,
                ssh = ssh,
                localHttp = localHttp,
                dryRun = dryRun,
                diff = diff,
                applyOnce = applyOnce,
                times = passTimes,
            )
            val pauses = ctx.pauses()
            if (pauses.global) log.info("controller", "global pause active: observing only")
            if (applyOnce && state.snapshot().pauses.global) {
                log.change("controller", "applying waiting changes once while paused")
            }

            if (!dryRun) rememberNodeAccess(cfg)
            flagRemovedNodes(cfg)

            // 1. Service discovery. If it fails, the destructive domains do not run: an empty view must
            // never delete everything.
            val listed: List<DockerContainerSummary>? = runCatchingCancellable {
                // Docker has no filter for a label prefix, and a container can have only named services.
                passTimes.measure("discovery") {
                    docker.listContainers(mapOf("status" to listOf("running"))).filter { declaresService(it.labels) }
                }
            }.onFailure {
                log.error("discovery", "cannot list containers: ${it.message}")
            }.getOrNull()
            listed?.let { homelabContainers = it.size }
            // A container that is gone stays for a grace period, and a failed discovery plans from
            // the last one. See [DiscoveryMemory].
            val remembered = discoveryMemory.merge(
                listed,
                nowMillis(),
                cfg.reconcile.removalGraceDuration.inWholeMilliseconds,
                save = !dryRun,
            )
            remembered?.held?.forEach { (name, left) ->
                if (heldContainers.add(name)) {
                    val minutes = (left + 59_999) / 60_000
                    log.warn(
                        "discovery",
                        "the container is gone: HomeGAP keeps its services for $minutes min, in case it starts again",
                        name,
                    )
                }
            }
            remembered?.dropped?.forEach {
                log.info("discovery", "the container did not come back: its services go away", it)
            }
            heldContainers.retainAll(remembered?.held?.keys.orEmpty())
            val known = remembered?.containers
            val containers = known.takeIf { listed != null }
            val routers = containers?.let { passTimes.measure("discovery") { traefikRouters(it) } }
            val plan = Planner.plan(cfg, known.orEmpty(), pauses.services, traefikRouters = routers.orEmpty())
            if (containers != null && routers != null) {
                desired = plan
                val doorContainers = plan.frontDoors.map { it.containerName }.toSet()
                frontDoorAddresses = containers.asSequence()
                    .filter { it.name in doorContainers }
                    .flatMap { it.networkSettings.networks.values }
                    .map { it.ipAddress }
                    .filter { it.isNotBlank() }
                    .toSet()
                plan.labelErrors.forEach { log.error("discovery", it.message, it.container) }
                plan.labelWarnings.forEach { log.warn("discovery", it.message, it.container) }
                if (dryRun) logDesired(plan)
            }
            val discoveryOk = containers != null && routers != null
            val effective = if (discoveryOk) plan else desired ?: plan
            val pausedKeys = effective.services.filter { it.paused }.map { it.key }.toSet() + pauses.services
            val servicesKnown = discoveryOk || desired != null || known != null

            // A failed discovery gives a plan without services: keep the certificate as it is.
            val certs: CertMaterial? = if (!discoveryOk) {
                nodeCert.current()
            } else {
                runDomain("certificates", null) {
                    val bunny = cfg.bunny.apiKey.resolve()?.let { BunnyClient(externalHttp, cfg.bunny.apiUrl, it) }
                    // An order waits for DNS, sometimes for minutes. The pass does not wait for it.
                    val background = jobScope?.let { scope ->
                        { order: suspend () -> Unit ->
                            scope.launch {
                                order()
                                trigger("certificate")
                            }
                            Unit
                        }
                    }
                    nodeCert.ensure(ctx, CertNames.of(cfg, effective), bunny, background)
                } ?: nodeCert.current()
            }

            val onCertificate = CertNames.of(cfg, effective)
            val certProbeNames = onCertificate + CertNames.passthrough(effective).filter { it !in onCertificate }

            // 2-4. Node lifecycle, tunnels and resources, per node in parallel.
            val nodeRec = NodeReconciler(ctx, if (cfg.cluster != null) clusterRec else null)
            val tunnelRec = TunnelReconciler(ctx)
            val exitRec = ExitReconciler(ctx)
            val exitSites = mutableMapOf<String, String>()
            val resourceRec = ResourceReconciler(ctx, certs)
            val health = mutableMapOf<String, Map<String, ServiceNodeHealthDto>>()
            // niceIds observed present on each node this pass (absent key = node not observed).
            val present = mutableMapOf<String, Set<String>>()
            val online = mutableMapOf<String, Boolean?>()
            val obs = mutableMapOf<String, NodeObservation>()
            // In a cluster the resources are shared: applied once after the per-node work.
            val clustered = cfg.cluster != null
            val readyNodes = mutableMapOf<String, Pair<ReadyNode, PangolinSite>>()
            // Permanent tunnel slots (subnet, interface, ports). The list position never gives a slot.
            val slots = TunnelSlots.assign(cfg.nodes.map { it.id }, state.snapshot().nodes)
            if (!dryRun) {
                for ((id, slot) in slots) {
                    if (state.node(id).tunnelSlot != slot) state.updateNode(id) { it.copy(tunnelSlot = slot) }
                }
            }
            coroutineScope {
                cfg.nodes.map { node ->
                    val nodeStarted = nowMillis()
                    async {
                        val slot = state.node(node.id).tunnelSlot ?: slots.getValue(node.id)
                        val nodePlan = effective.nodes.getValue(node.id)
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
                        val assessment = runDomain("nodes", node.id) { nodeRec.assess(nodePlan, slot, force) }
                        var ready: ReadyNode? = null
                        if (assessment != null) {
                            if (assessment.kind == NodeReconciler.Kind.UPDATE) {
                                targetStacks[node.id] = assessment.hash
                            } else {
                                targetStacks.remove(node.id)
                            }
                            val decision = rolloutDecision(node.id, assessment, force, servicesKnown)
                            when {
                                decision is RolloutPolicy.Decision.Wait -> {
                                    if (waiting.put(node.id, decision.reason) != decision.reason) {
                                        log.info("nodes", "${assessment.reason}: ${decision.reason}", node.id)
                                    }
                                    // A node waiting for its update keeps serving on its current stack.
                                    if (assessment.kind != NodeReconciler.Kind.FRESH) {
                                        ready = runDomain("nodes", node.id) { nodeRec.ready(assessment, slot) }
                                    }
                                }
                                assessment.paused || assessment.kind == NodeReconciler.Kind.NONE -> {
                                    waiting.remove(node.id)
                                    // The desired stack is back to what the node runs: nothing left to retry.
                                    val pendingUpdate = if (assessment.paused) null else state.node(node.id).update
                                    if (pendingUpdate != null && pendingUpdate.targetHash != assessment.hash) {
                                        state.updateNode(node.id) { it.copy(update = null) }
                                        log.change(
                                            "nodes",
                                            "pending update no longer needed (desired stack matches the running one)",
                                            node.id,
                                        )
                                    }
                                    ready = runDomain("nodes", node.id) { nodeRec.ready(assessment, slot) }
                                }
                                else -> {
                                    waiting.remove(node.id)
                                    val tunnelWasUp = siteOnline[node.id] == true
                                    val scope = jobScope
                                    if (scope == null) {
                                        // One-shot mode: provision inline.
                                        val provisioned = nodeRec.provisionJob(
                                            assessment,
                                            slot,
                                            tunnelWasUp,
                                            waitForCluster = false,
                                        )
                                        if (provisioned) {
                                            ready = runDomain("nodes", node.id) { nodeRec.ready(assessment, slot) }
                                        }
                                    } else {
                                        jobs[node.id] = assessment.kind to scope.launch {
                                            try {
                                                nodeRec.provisionJob(
                                                    a = assessment,
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
                                runDomain("exits", node.id) { exitRec.reconcile(ready, pins) }
                                    ?.let { synchronizedPut(exitSites, node.id, it) }
                            } else if (discoveryOk && site != null) {
                                resources = runDomain("resources", node.id) {
                                    resourceRec.reconcile(ready, nodePlan, pausedKeys)
                                }.orEmpty()
                                if (resources.isNotEmpty()) present[node.id] = resources.map { it.niceId }.toSet()
                            }
                        } else if (dryRun) {
                            val blueprint = resourceRec.displayBlueprint(
                                nodePlan.publicResources.filter { !it.paused },
                                nodePlan.privateResources.filter { !it.paused },
                                cfg.siteNiceId(node.id),
                            )
                            log.info("resources", "[dry-run] blueprint: $blueprint", node.id)
                            nodePlan.publicResources.forEach {
                                val address = it.fullDomain ?: ":${it.proxyPort}"
                                diff.add(
                                    "resources",
                                    node.id,
                                    it.niceId,
                                    "unknown",
                                    "${it.mode.wire} $address -> ${it.target.host}:${it.target.port}",
                                )
                            }
                        }
                        val probeHosts = effective.services
                            .filter { it.isHttpLike() && !it.paused && !it.hostname.startsWith("*") }
                            .filter { it.servesNode(node.id) }
                            .map { it.hostname }
                        val observation = runDomain("observe", node.id) {
                            observeNode(ctx, ObservedEdge(node, ready, site), ProbeTargets(probeHosts, certProbeNames))
                        }
                        val unobserved = NodeObservation(
                            reachable = false,
                            checks = emptyList(),
                            certificate = null,
                            apiOk = false,
                        )
                        synchronizedPut(obs, node.id, observation ?: unobserved)
                        synchronizedPut(online, node.id, site?.online)
                        if (!clustered || ready == null || site == null) {
                            synchronizedPut(health, node.id, serviceHealthFor(effective, node.id, resources, site))
                        }
                    }.also { work ->
                        work.invokeOnCompletion { passTimes.add("node:${node.id}", nowMillis() - nodeStarted) }
                    }
                }.awaitAll()
            }
            if (clustered) {
                clusterReady = readyNodes.mapValues { it.value.first }
                exitNodes = readyNodes
                    .mapNotNull { (id, readySite) -> readySite.second.exitNodeId?.let { it to id } }
                    .toMap()
            }
            if (clustered && discoveryOk && readyNodes.isNotEmpty()) {
                // Through the first ready node in config order. Each node then writes its own routes.
                val sites = readyNodes.mapValues { (_, readySite) -> readySite.second.niceId }
                val firstReadyId = cfg.nodes.first { it.id in readyNodes }.id
                val actual = runDomain("resources", ResourceReconciler.CLUSTER_SCOPE) {
                    resourceRec.reconcileCluster(
                        readyNodes.getValue(firstReadyId).first,
                        effective.nodes,
                        sites,
                        pausedKeys,
                        ExitNodes.resources(cfg, exitSites, sites) + TunnelDns.resources(cfg, exitSites, sites),
                    )
                }
                if (actual != null) {
                    for ((id, readySite) in readyNodes) {
                        val (ready, site) = readySite
                        // What this node can serve: resources with a target on its own site.
                        val mine = actual.filter { resource -> resource.targets.any { it.siteNiceId == site.niceId } }
                        runDomain("resources", id) {
                            resourceRec.writeRoutes(ready, effective.nodes.getValue(id), mine)
                        }
                        present[id] = mine.map { it.niceId }.toSet()
                        health[id] = serviceHealthFor(effective, id, mine, site)
                    }
                }
            }
            // What departed nodes left in the cluster's Pangolin (their sites and exit nodes).
            if (clustered && !dryRun && !pauses.global && readyNodes.isNotEmpty()) {
                val firstReady = readyNodes.getValue(cfg.nodes.first { it.id in readyNodes }.id).first
                val knownIds = cfg.nodes.map { it.id }.toSet() + state.snapshot().nodes.keys
                runDomain("cluster", null) { clusterRec.pruneDeparted(ctx, firstReady, knownIds) }
            }
            if (!dryRun && !pauses.global) {
                runDomain("tunnels", null) {
                    val active = cfg.nodes.filter { !state.node(it.id).pendingDecommission }
                    tunnelRec.prune(active.map { it.id }.toSet())
                }
            }

            // The cluster mesh (host-level, independent of each node's stack).
            meshStatus = if (cfg.cluster == null || dryRun) {
                null
            } else {
                runDomain("mesh", null) { meshRec.reconcile(ctx, clusterNodes(cfg), knownTunnelSlots()) } ?: meshStatus
            }
            // The cluster's database layer, over the mesh.
            clusterStatus = if (cfg.cluster == null || dryRun) {
                null
            } else {
                runDomain("cluster", null) {
                    // Nodes follow the cluster image the homelab has (pinned to its registry digest).
                    val image = cfg.cluster.image.let { ref ->
                        runCatchingCancellable { docker.image(ref) }
                            .getOrNull()
                            ?.takeIf { it.created.isNotEmpty() }
                            ?.let { MemberImage(it.pinned(ref), it.created) }
                    }
                    clusterRec.reconcile(ctx, clusterNodes(cfg), knownTunnelSlots(), image)
                } ?: clusterStatus
            }
            clusterStatus = clusterStatus?.let { current ->
                val shared = state.snapshot().clusterPangolin
                val license = ClusterAlerts.License(
                    configured = cfg.pangolin.licenseKey() != null,
                    activated = shared?.licenseActivated == true,
                    rejected = shared?.licenseRejected != null,
                )
                current.copy(alerts = ClusterAlerts.evaluate(current, meshStatus, license, nowMillis()))
            }

            // 5. DNS
            if (discoveryOk) {
                val bunny = cfg.bunny.apiKey.resolve()?.let { BunnyClient(externalHttp, cfg.bunny.apiUrl, it) }
                val records = servableDns(effective, present)
                // The zone download is the only external API call of the pass. Skip it while the desired
                // records are unchanged and the last sync succeeded recently.
                val key = Crypto.fnv64(records.toString() + pausedKeys.sorted() + state.snapshot().pauses + cfg.bunny)
                // A record a nameserver does not serve yet is checked again every pass until it does.
                val waitingForNameservers = dnsObs.any {
                    it.diff?.startsWith(DnsReconciler.NOT_ON_EVERY_NAMESERVER) == true
                }
                val syncedRecently = nowMillis() - dnsSyncedAt < cfg.reconcile.dnsCheck.inWholeMilliseconds
                val fresh = key == dnsKey && !waitingForNameservers && syncedRecently
                if (!fresh || trigger.contains("dashboard")) {
                    val dnsRec = DnsReconciler(ctx, bunny, nameserverCheck)
                    val observed = runDomain("dns", null) { dnsRec.reconcile(records, pausedKeys) }
                    // A zone that failed keeps the CAA records of its last read.
                    caaLive = caaLive.orEmpty() + dnsRec.lastCaa
                    dnsZoneErrors = dnsRec.lastZoneErrors
                    if (observed != null) {
                        dnsObs = observed
                        if (bunny != null) {
                            dnsKey = key
                            dnsSyncedAt = nowMillis()
                        }
                    } else {
                        dnsKey = null
                    }
                }
            }

            nodeObs = obs
            certProbes = (certProbes + obs.mapNotNull { (id, seen) -> seen.certProbes?.let { id to it } })
                .filterKeys { id -> cfg.nodes.any { it.id == id } }
            if (!dryRun) {
                val active = cfg.nodes.filter { !state.node(it.id).pendingDecommission }
                trackIsolation(active.mapNotNull { obs[it.id]?.reachable }, nowMillis())
            }
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
            lastSummary = ReconcileSummaryDto(
                startedAt = started,
                finishedAt = nowMillis(),
                trigger = trigger,
                ok = errors == 0,
                changes = changes,
                errors = errors,
            )
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
                ) + passTimes.drain(),
            )
            log.pass = null
            log.save()
        }
    }

    /** Stores how to reach each node, so that a teardown is possible after its removal from the config. */
    private suspend fun rememberNodeAccess(cfg: AppConfig) {
        for (node in cfg.nodes) {
            state.updateNode(node.id) {
                it.copy(facts = it.facts + nodeFacts(node), pendingDecommission = false, teardownError = null)
            }
        }
    }

    /** Marks each node that left the config. A decommission needs an explicit confirmation. */
    private suspend fun flagRemovedNodes(cfg: AppConfig) {
        val configured = cfg.nodes.map { it.id }.toSet()
        for (id in state.snapshot().nodes.keys - configured) {
            if (!state.node(id).pendingDecommission) {
                state.updateNode(id) { it.copy(pendingDecommission = true) }
                log.warn("nodes", "removed from config; confirm decommission in the dashboard to tear it down", id)
            }
        }
    }

    /** Whether the provisioning that [assessment] asks for starts in this pass, or why it waits. */
    private fun rolloutDecision(
        nodeId: String,
        assessment: NodeReconciler.Assessment,
        force: Boolean,
        servicesKnown: Boolean,
    ): RolloutPolicy.Decision {
        val isUpdate = assessment.kind == NodeReconciler.Kind.UPDATE
        return when {
            assessment.paused || assessment.kind == NodeReconciler.Kind.NONE -> RolloutPolicy.Decision.Start
            // Until the slot is checked against the live subnet, the rendered stack can be wrong.
            isUpdate && !state.node(nodeId).tunnelSlotConfirmed ->
                RolloutPolicy.Decision.Wait("confirming the node's tunnel slot")
            // Without one discovery, the plan has no services, and the stack loses their ports.
            isUpdate && !servicesKnown -> RolloutPolicy.Decision.Wait("waiting for the first service discovery")
            else -> RolloutPolicy.decide(
                nodeId = nodeId,
                kind = assessment.kind,
                hash = assessment.hash,
                force = force,
                states = state.snapshot().nodes,
                updating = jobs
                    .filter { (_, job) -> job.first == NodeReconciler.Kind.UPDATE && job.second.isActive }
                    .keys,
                now = nowMillis(),
            )
        }
    }

    /** The tunnel slot of each node that has one. */
    private fun knownTunnelSlots(): Map<String, Int> =
        state.snapshot().nodes
            .mapNotNull { (id, nodeState) -> nodeState.tunnelSlot?.let { id to it } }
            .toMap()

    /**
     * Keeps a node in a hostname's record set only while the Pangolin resource serving that
     * hostname exists on it (for example, a wildcard refused for lack of an EE license). Then DNS
     * never steers viewers to a node that answers 404. A node that this pass did not observe stays.
     */
    private fun servableDns(plan: DesiredState, present: Map<String, Set<String>>): List<DesiredDnsRecord> =
        plan.dns.filter { record ->
            val have = present[record.nodeId] ?: return@filter true
            val resources = plan.nodes[record.nodeId]?.publicResources ?: return@filter true
            // The front door of the domain of the record. A root or wildcard record has no other source.
            val frontDoor = plan.frontDoors.firstOrNull { it.hostname == record.fqdn.removePrefix("*.") }
            val frontDoorKey = frontDoor?.key
            val frontDoorWildcard = frontDoor?.let { "*.${it.hostname}" }
            val needed = when {
                record.purpose == "management" -> emptyList()
                record.purpose == "root" -> resources.filter {
                    it.serviceKey == frontDoorKey && it.fullDomain != frontDoorWildcard && !Planner.isHealth(it.niceId)
                }
                record.purpose == "wildcard" -> resources.filter {
                    it.serviceKey == frontDoorKey && (it.mode.wire == "tcp" || it.fullDomain == frontDoorWildcard)
                }
                record.purpose.startsWith("service:") -> resources.filter {
                    it.serviceKey == record.purpose.removePrefix("service:") && !Planner.isHealth(it.niceId)
                }
                else -> emptyList()
            }.filter { !it.paused }.map { it.niceId }
            val missing = needed.filter { it !in have }
            if (missing.isNotEmpty()) {
                log.warn(
                    "dns",
                    "not steering ${record.fqdn} to this node: ${missing.joinToString()} missing on it",
                    record.nodeId,
                )
            }
            missing.isEmpty()
        }

    /** When the homelab last lost contact with every node: (start, end or null while it lasts). */
    @Volatile private var isolation: Pair<Long, Long?>? = null

    /**
     * With two or more nodes, none reachable over SSH in a pass means the homelab itself lost its
     * way to them (two providers rarely fail at the same moment). The log has the start and the
     * end, and the dashboard shows the isolation.
     */
    private fun trackIsolation(reachable: List<Boolean>, now: Long) {
        val current = isolation
        val next = isolationAfter(current, reachable, now)
        if (next == current) return
        isolation = next
        val start = next?.first
        val end = next?.second
        if (start == null || end == null) {
            log.error(
                "controller",
                "the homelab can't reach any node: check the homelab's internet connection " +
                    "(the nodes keep serving, and fail the database over if this lasts)",
            )
        } else {
            val minutes = ((end - start) / 60_000).coerceAtLeast(1)
            log.change("controller", "the homelab reaches the nodes again after $minutes min without any")
        }
    }

    private fun <K, V> synchronizedPut(map: MutableMap<K, V>, key: K, value: V) {
        // The async blocks run on the single runBlocking event loop. A plain put is safe there.
        map[key] = value
    }

    private suspend fun <T> runDomain(domain: String, target: String?, block: suspend () -> T): T? =
        runCatchingCancellable { passTimes.measure(listOfNotNull(domain, target).joinToString(":")) { block() } }
            .onFailure { log.error(domain, it.message ?: it.toString(), target) }
            .getOrNull()

    private fun logDesired(plan: DesiredState) {
        val services = plan.services.joinToString {
            "${it.hostname}(${it.protocol.label}/${it.tls.label}/${it.access.label})"
        }
        log.info("discovery", "[dry-run] ${plan.services.size} labeled service(s): $services")
        plan.frontDoors.forEach {
            val wildcard = if (it.wildcard) " + *.${it.hostname}" else ""
            log.info(
                "discovery",
                "[dry-run] front door: ${it.containerName} claims ${it.hostname}$wildcard (${it.tls.label})",
            )
        }
        for ((id, nodePlan) in plan.nodes) {
            val parts = listOf(
                "public=${nodePlan.publicResources.map { it.niceId }}",
                "private=${nodePlan.privateResources.map { it.niceId }}",
                "sni=${nodePlan.sniRoutes.map { it.name }}",
                "raw=${nodePlan.rawPorts.map { it.entryPoint }}",
            )
            log.info("resources", "[dry-run] ${parts.joinToString(" ")}", id)
        }
        plan.dns.forEach {
            log.info(
                "dns",
                "[dry-run] ${it.type} ${it.fqdn} -> ${it.value} node=${it.nodeId} geo=${it.geo} monitor=${it.monitor}",
            )
        }
    }

    // ------------------------------------------------------------------ observability

    /** A node in one pass, with its API client and its site when the pass has them. */
    private class ObservedEdge(val node: NodeConfig, val ready: ReadyNode?, val site: PangolinSite?)

    /** The names that the observe script requests through the proxy of the node. */
    private class ProbeTargets(val serviceHosts: List<String>, val certNames: List<String>)

    private suspend fun observeNode(ctx: ReconcileContext, edge: ObservedEdge, targets: ProbeTargets): NodeObservation {
        if (ctx.dryRun) {
            return NodeObservation(
                reachable = false,
                checks = listOf(CheckDto("mode", Health.UNKNOWN, "dry-run: no remote access")),
                certificate = null,
                apiOk = false,
            )
        }
        val node = edge.node
        val checks = mutableListOf<CheckDto>()
        val serviceProbes = mutableMapOf<String, ServiceProbe>()
        var traffic: Pair<Double, Double>? = null
        val script = observeScript(ctx, node, targets)
        val result = runCatchingCancellable {
            ctx.ssh.session(node).runScript(script, OBSERVE_TIMEOUT_SECONDS)
        }.getOrNull()
        val reachable = result != null && result.ok
        val sshDetail = if (reachable) "" else result?.stderr?.trim()?.take(SSH_ERROR_LENGTH) ?: "unreachable"
        checks += CheckDto("ssh", if (reachable) Health.OK else Health.DOWN, sshDetail)
        var certificate: CertificateDto? = null
        if (reachable) {
            for (line in result.stdout.lines()) {
                val parts = line.split(' ', limit = 3)
                when (parts.firstOrNull()) {
                    "C" -> if (parts.size == 3) {
                        checks += containerCheck(name = parts[1], inspected = parts[2])
                    }
                    "D" -> {
                        val version = parts.getOrNull(1)
                        val health = if (version == "none") Health.DOWN else Health.OK
                        checks += CheckDto("docker", health, version.orEmpty())
                    }
                    "U" -> {
                        val listens = parts.getOrNull(1) == "ok"
                        checks += CheckDto("gerbil-udp", if (listens) Health.OK else Health.DOWN, "51820/udp")
                    }
                    "H" -> parseServiceProbe(parts)?.let { (host, probe) -> serviceProbes[host] = probe }
                    "M" -> {
                        val requests = parts.getOrNull(1)?.toDoubleOrNull()
                        val bytes = parts.getOrNull(2)?.toDoubleOrNull()
                        if (requests != null && bytes != null) traffic = requests to bytes
                    }
                    "P" -> {
                        val csv = parts.getOrNull(1)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { runCatching { Base64.decode(it).decodeToString() }.getOrNull() }
                        val history = proxyHistory.getOrPut(node.id) { mutableListOf() }
                        checks += ClusterProxy.check(csv, history, nowMillis(), clusterRec.plannedMoves)
                    }
                    "T" -> if (parts.size == 3) {
                        val (check, served) = tlsCheck(which = parts[1], probe = parts[2])
                        if (parts[1] == "mgmt" && served != null) certificate = served
                        checks += check
                    }
                }
            }
        }
        // The Pangolin API probe: a request through the edge to its Pangolin, timed.
        val ready = edge.ready
        val apiStarted = nowMillis()
        val apiOk = ready != null && runCatchingCancellable { ready.api.getOrg(ctx.config.pangolin.orgId) }.isSuccess
        val apiMs = (nowMillis() - apiStarted).toDouble()
        checks += apiCheck(node.id, apiOk, apiMs)
        licenseCheck(ctx, node)?.let { checks += it }
        val site = edge.site
        site?.newtVersion?.let { newtVersions[node.id] = it }
        checks += tunnelCheck(site)
        val certProbes = if (reachable) {
            val lines = result.stdout.lines()
            val installed = CertProbes.installed(lines)
            CertificateStatus.NodeProbes(nowMillis(), installed?.first, installed?.second, CertProbes.parse(lines))
        } else {
            null
        }
        return NodeObservation(
            reachable = reachable,
            checks = checks,
            certificate = certificate,
            apiOk = apiOk,
            serviceProbes = serviceProbes,
            traffic = traffic,
            certProbes = certProbes,
        )
    }

    /** The script that reads the containers, the certificates, the proxy and the traffic of [node]. */
    private fun observeScript(ctx: ReconcileContext, node: NodeConfig, targets: ProbeTargets): String {
        val mgmt = node.managementHost(ctx.config.domain)
        val root = ctx.config.domain
        val base = $$"""
            |for s in pangolin gerbil traefik; do echo "C $s $(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $s 2>/dev/null || echo 'missing none')"; done
            |echo "D $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)"
            |probe() { c=$(echo | timeout 6 openssl s_client -connect 127.0.0.1:443 -servername "$1" 2>/dev/null | openssl x509 -noout -enddate -subject 2>/dev/null) || { echo "none"; return; }; end=$(echo "$c" | sed -n 's/^notAfter=//p'); sub=$(echo "$c" | sed -n 's/^subject=//p'); echo "$(date -d "$end" +%s 2>/dev/null || echo 0)|$sub"; }
            |echo "T mgmt $(probe $${shQuote(mgmt)})"
            |echo "T root $(probe $${shQuote(root)})"
            |(ss -lun 2>/dev/null | grep -q ':51820 ') && echo "U ok" || echo "U missing"
        """.trimMargin()
        val proxy = if (ctx.config.cluster == null) "" else $$"\necho \"P $($${ClusterProxy.CSV_CMD} | base64 -w0)\""
        val dynamicTls = "${ctx.config.pangolin.installDir}/${NodeTemplates.DYNAMIC_TLS}"
        return listOf(
            base + proxy + serviceProbeScript(targets.serviceHosts),
            NodeTemplates.TRAFFIC_CMD,
            CertProbes.script(targets.certNames, dynamicTls),
        ).joinToString("\n")
    }

    /** The check of a container from one `C` line: [inspected] has its status and its health check. */
    private fun containerCheck(name: String, inspected: String): CheckDto {
        val fields = inspected.split(' ')
        val status = fields[0]
        val healthCheck = fields.getOrElse(1) { "none" }
        val health = when {
            status != "running" -> Health.DOWN
            healthCheck == "unhealthy" -> Health.DOWN
            healthCheck == "starting" -> Health.DEGRADED
            else -> Health.OK
        }
        return CheckDto(name, health, "$status${if (healthCheck != "none") " ($healthCheck)" else ""}")
    }

    /** The check of one `T` line, and the certificate if the node serves one. */
    private fun tlsCheck(which: String, probe: String): Pair<CheckDto, CertificateDto?> {
        if (probe == "none") {
            return CheckDto("tls-$which", Health.DOWN, "no certificate served") to null
        }
        val epoch = probe.substringBefore('|').toLongOrNull() ?: 0
        val subject = probe.substringAfter('|')
        val days = if (epoch > 0) ((epoch * 1000 - nowMillis()) / DAY_MS).toInt() else null
        // The nodes' own certificate renews on its own: it warns only when that renewal is late.
        val own = nodeCert.meta()?.takeIf { which == "mgmt" && it.notAfter == epoch }
        val late = own?.let { renewalIsLate(notAfter = it.notAfter, renewAt = nodeCert.renewAt(it), nowSeconds = nowMillis() / 1000) }
        val health = when {
            days == null -> Health.UNKNOWN
            days < 0 -> Health.DOWN
            late ?: (days < CERT_EXPIRY_WARNING_DAYS) -> Health.DEGRADED
            else -> Health.OK
        }
        val certificate = CertificateDto(
            subject = subject,
            notAfter = if (epoch > 0) epochToIso(epoch) else null,
            daysRemaining = days,
            source = which,
            health = health,
        )
        val detail = listOfNotNull(subject.ifEmpty { null }, days?.let { "$it days left" }).joinToString(", ")
        return CheckDto("tls-$which", health, detail) to certificate
    }

    /** The check of the Pangolin API probe of a node, which took [apiMs]. */
    private fun apiCheck(nodeId: String, apiOk: Boolean, apiMs: Double): CheckDto {
        // A far node is slow all the time, so the limit starts at the fastest probe of the node.
        if (apiOk) apiBest[nodeId] = minOf(apiMs, apiBest[nodeId] ?: apiMs)
        val limit = apiSlowLimit(apiBest[nodeId])
        if (!apiOk) {
            return CheckDto("pangolin-api", Health.DOWN, "not reachable", threshold = limit, unit = "ms")
        }
        val slow = apiMs > limit
        return CheckDto(
            name = "pangolin-api",
            health = if (slow) Health.DEGRADED else Health.OK,
            detail = if (slow) "slow" else "integration API",
            value = apiMs,
            threshold = limit,
            unit = "ms",
        )
    }

    private fun licenseCheck(ctx: ReconcileContext, node: NodeConfig): CheckDto? {
        if (ctx.config.cluster != null) {
            // One license for the whole cluster (stored in the shared database). A missing activation
            // is a cluster alert, not a problem with this node.
            if (ctx.config.pangolin.licenseKey() == null) return null
            val shared = state.snapshot().clusterPangolin
            return when {
                shared?.licenseActivated == true && node.id in shared.licenseRecheck ->
                    CheckDto("ee-license", Health.DEGRADED, "activated; this node has not re-read it yet")
                shared?.licenseActivated == true ->
                    CheckDto("ee-license", Health.OK, "the cluster's license is activated")
                shared?.licenseRejected != null -> CheckDto("ee-license", Health.DEGRADED, LICENSE_REJECTED)
                else -> null
            }
        }
        if (node.licenseKey(ctx.config.pangolin) == null) return null
        val nodeState = state.node(node.id)
        return when {
            nodeState.licenseActivated -> CheckDto("ee-license", Health.OK, "activated")
            nodeState.facts["licenseRejected"] != null -> CheckDto("ee-license", Health.DEGRADED, LICENSE_REJECTED)
            else -> CheckDto("ee-license", Health.UNKNOWN, "not activated")
        }
    }

    private fun tunnelCheck(site: PangolinSite?): CheckDto {
        val health = when (site?.online) {
            true -> Health.OK
            false -> Health.DOWN
            null -> Health.UNKNOWN
        }
        val detail = site?.let { "site ${it.niceId}${it.newtVersion?.let { version -> ", newt $version" }.orEmpty()}" }
        return CheckDto("tunnel", health, detail ?: "no site")
    }

    private fun serviceHealthFor(
        plan: DesiredState,
        nodeId: String,
        resources: List<PangolinResource>,
        site: PangolinSite?,
    ): Map<String, ServiceNodeHealthDto> {
        val nodePlan = plan.nodes[nodeId] ?: return emptyMap()
        val byNiceId = resources.associateBy { it.niceId }
        val healthByService = mutableMapOf<String, ServiceNodeHealthDto>()
        for (service in plan.services) {
            if (!service.servesNode(nodeId)) continue
            val wanted = nodePlan.publicResources
                .filter { it.serviceKey == service.key && !Planner.isHealth(it.niceId) }
            val privateWanted = nodePlan.privateResources.filter { it.serviceKey == service.key }
            if (wanted.isEmpty() && privateWanted.isEmpty()) continue
            val actual = wanted.mapNotNull { byNiceId[it.niceId] }
            healthByService[service.key] = when {
                site?.online == false -> ServiceNodeHealthDto(nodeId, Health.DOWN, "tunnel down")
                wanted.isNotEmpty() && actual.size < wanted.size ->
                    ServiceNodeHealthDto(nodeId, Health.DOWN, "resource missing")
                actual.any { it.targets.any { target -> target.healthStatus == "unhealthy" } } ->
                    ServiceNodeHealthDto(nodeId, Health.DEGRADED, "target unhealthy")
                site?.online == true -> {
                    val targetHealth = actual.firstOrNull()?.targets?.firstOrNull()?.healthStatus
                    ServiceNodeHealthDto(nodeId, Health.OK, targetHealth ?: "online")
                }
                else -> ServiceNodeHealthDto(nodeId, Health.UNKNOWN, "")
            }
        }
        return healthByService
    }

    /** Reads the state of the controller, so only code on the loop calls it. See [status]. */
    private fun snapshot(): StatusSnapshot {
        val cfg = config
        val stored = state.snapshot()
        val plan = desired
        val certificates = certificateStatus(cfg, plan)
        val waitingNodes = certificates.delivery.filter { it.state == "waiting" }.map { it.node }.toSet()
        val nodes = (cfg.nodes.map { it.id } + stored.nodes.keys)
            .distinct()
            .map { id -> nodeStatus(cfg, stored, id, waitingForCertificate = id in waitingNodes) }
        val services = plan?.services?.map { serviceStatus(cfg, stored, plan, it) }.orEmpty()
        val dns = dnsObs.map { dnsRecordStatus(it) }
        val tunnels = cfg.nodes.map { tunnelStatus(cfg, it) }
        return StatusSnapshot(
            generatedAt = nowMillis(),
            version = VERSION,
            dryRun = dryRun,
            project = cfg.project,
            domain = cfg.domain,
            domains = domainStatus(cfg, plan),
            paused = stored.pauses.global,
            lastReconcile = lastSummary,
            reconciling = reconciling,
            nodes = nodes,
            services = services,
            dns = dns,
            tunnels = tunnels,
            labelErrors = plan?.labelErrors?.map { LabelErrorDto(it.container, it.message) }.orEmpty(),
            pauses = PausesDto(
                global = stored.pauses.global,
                nodes = stored.pauses.nodes.sorted(),
                services = stored.pauses.services.sorted(),
            ),
            mesh = meshStatus.takeIf { cfg.cluster != null },
            cluster = clusterStatus.takeIf { cfg.cluster != null },
            homelab = cfg.homelab.location?.let { PlaceDto(it.latitude, it.longitude, it.name) },
            homelabIsolation = isolation?.let { (start, end) -> IsolationDto(start, end) },
            speedRun = speedRun,
            homelabContainers = homelabContainers,
            certificates = certificates,
        ).let { snapshot ->
            snapshot.copy(attention = attentionItems(snapshot, attentionSeen))
        }
    }

    private fun nodeStatus(
        cfg: AppConfig,
        stored: ControllerState,
        id: String,
        waitingForCertificate: Boolean,
    ): NodeStatusDto {
        val node = cfg.node(id)
        val nodeState = stored.nodes[id]
        val observation = nodeObs[id]
        val tunnel = TunnelFiles.readStatus(cfg.tunnelsDir, id)
        val worst = observation?.checks?.map { it.health }?.let { healths ->
            when {
                healths.contains(Health.DOWN) -> Health.DOWN
                healths.contains(Health.DEGRADED) -> Health.DEGRADED
                healths.all { it == Health.OK } -> Health.OK
                else -> Health.UNKNOWN
            }
        } ?: Health.UNKNOWN
        // The stored fact is from before the setup installed Docker.
        val dockerFact = observation?.checks
            ?.firstOrNull { it.name == "docker" && it.health == Health.OK }
            ?.let { mapOf("docker" to it.detail) }
            .orEmpty()
        val restartsFact = tunnel?.let { mapOf("newt_restarts" to it.restarts.toString()) }.orEmpty()
        return NodeStatusDto(
            id = id,
            region = node?.region.orEmpty(),
            provider = node?.provider.orEmpty(),
            host = node?.host.orEmpty(),
            managementHost = node?.managementHost(cfg.domain).orEmpty(),
            health = when {
                nodeState?.lastProvisionOk == false -> Health.DOWN
                // A new node is not down while it waits for an order with its name.
                waitingForCertificate && observation?.reachable == true && worst == Health.DOWN -> Health.DEGRADED
                else -> worst
            },
            reachable = observation?.reachable ?: false,
            checks = observation?.checks.orEmpty(),
            certificate = observation?.certificate,
            tunnelUp = siteOnline[id] == true,
            lastProvisionAt = nodeState?.lastProvisionAt,
            lastProvisionOk = nodeState?.lastProvisionOk,
            lastProvisionError = nodeState?.lastProvisionError,
            drift = nodeState?.drift.orEmpty(),
            paused = stored.pauses.global || id in stored.pauses.nodes,
            pendingDecommission = nodeState?.pendingDecommission == true || node == null,
            teardownError = nodeState?.teardownError,
            facts = nodeState?.facts.orEmpty().filterKeys { it in DASHBOARD_FACTS } + dockerFact + restartsFact,
            job = jobs[id]?.takeIf { it.second.isActive }?.let { (kind, _) ->
                if (kind == NodeReconciler.Kind.UPDATE) "updating" else "provisioning"
            },
            updateWaiting = waiting[id],
            update = nodeState?.update?.let { NodeUpdateDto(it.status, it.error, it.at, it.rolledBack) },
            stack = nodeState?.stackHash?.take(STACK_HASH_LENGTH),
            targetStack = targetStacks[id]?.takeIf { it != nodeState?.stackHash }?.take(STACK_HASH_LENGTH),
            jobStep = jobSteps[id],
            runs = nodeState?.runs.orEmpty().map { run ->
                NodeRunDto(
                    kind = run.kind,
                    startedAt = run.startedAt,
                    finishedAt = run.finishedAt,
                    ok = run.ok,
                    error = run.error,
                    stack = run.stack?.take(STACK_HASH_LENGTH),
                    rolledBack = run.rolledBack,
                )
            },
            location = node?.let { configured ->
                configured.location?.let { PlaceDto(it.latitude, it.longitude, it.name) }
                    ?: configured.effectiveLocation()?.let {
                        PlaceDto(it.latitude, it.longitude, name = null, estimated = true)
                    }
            },
            waitingForCertificate = waitingForCertificate,
        )
    }

    private fun serviceStatus(
        cfg: AppConfig,
        stored: ControllerState,
        plan: DesiredState,
        service: ServiceSpec,
    ): ServiceStatusDto = ServiceStatusDto(
        key = service.key,
        container = service.containerName,
        hostname = service.hostname,
        wildcard = service.wildcard,
        protocol = service.protocol.label,
        tls = if (service.isHttpLike()) service.tls.label else "—",
        access = service.access.label,
        auth = service.auth.label,
        regions = service.regions ?: cfg.nodes.map { it.id },
        kind = when {
            service in plan.frontDoors -> "front-door"
            service.access.label == "private" -> "private"
            service.protocol.isRaw -> "raw"
            else -> "dedicated"
        },
        paused = service.paused || service.key in stored.pauses.services,
        name = service.name,
        ports = service.ports.map { it.toString() },
        labels = service.labels,
        perNode = cfg.nodes.filter { service.servesNode(it.id) }.map { node ->
            val health = serviceHealth[node.id]?.get(service.key) ?: ServiceNodeHealthDto(node.id, Health.UNKNOWN)
            health.withProbe(nodeObs[node.id]?.serviceProbes?.get(service.hostname))
        },
    )

    private fun domainStatus(cfg: AppConfig, plan: DesiredState?): List<DomainStatusDto> =
        cfg.serviceDomains.map { domain ->
            val zone = cfg.zoneOf(domain)
            DomainStatusDto(
                domain = domain,
                zone = zone,
                frontDoor = plan?.frontDoors?.firstOrNull { it.hostname == domain }?.containerName,
                zoneError = dnsZoneErrors[zone],
            )
        }

    private fun dnsRecordStatus(observed: DnsReconciler.Observed): DnsRecordStatusDto {
        val record = observed.record
        return DnsRecordStatusDto(
            name = observed.fqdn,
            type = record.typeName,
            value = record.tag?.takeIf { it.isNotEmpty() }?.let { "$it ${record.value}" } ?: record.value,
            nodeId = observed.desired?.nodeId,
            geo = when (record.smartRoutingType) {
                BunnyRecord.ROUTING_GEO -> "geo ${record.latitude},${record.longitude}"
                BunnyRecord.ROUTING_LATENCY -> "latency ${record.latencyZone}"
                else -> null
            },
            monitor = MONITOR_TYPES.getOrElse(record.monitorType) { "?" },
            monitorStatus = BunnyRecord.MONITOR_STATUS.getOrElse(record.monitorStatus) { "unknown" },
            inSync = observed.inSync,
            diff = observed.diff,
            // The log names a record that nothing wants by its name in the zone.
            changedAt = log.lastRecordChange(observed.desired?.fqdn ?: record.name),
        )
    }

    private fun tunnelStatus(cfg: AppConfig, node: NodeConfig): TunnelStatusDto {
        val tunnel = TunnelFiles.readStatus(cfg.tunnelsDir, node.id)
        val fresh = tunnel != null && nowMillis() - tunnel.updatedAt < TUNNEL_STATUS_FRESH_MS
        return TunnelStatusDto(
            nodeId = node.id,
            siteNiceId = cfg.siteNiceId(node.id),
            endpoint = "https://${node.managementHost(cfg.domain)}",
            up = siteOnline[node.id] == true,
            processRunning = fresh && tunnel.running,
            siteOnline = siteOnline[node.id],
            restarts = tunnel?.restarts ?: 0,
            lastExit = tunnel?.lastExit,
            bytesIn = tunnel?.bytesIn,
            bytesOut = tunnel?.bytesOut,
            updatedAt = tunnel?.updatedAt,
            exits = tunnel?.exits.orEmpty().map { TunnelExitDto(it.at, it.reason) },
            newtVersion = newtVersions[node.id],
        )
    }

    private fun certificateStatus(cfg: AppConfig, plan: DesiredState?): CertificatesDto {
        val meta = nodeCert.meta()
        val now = nowMillis()
        return CertificateStatus.build(
            CertificateStatus.Inputs(
                config = cfg,
                desired = plan,
                meta = meta,
                info = nodeCert.info(),
                currentHash = nodeCert.current()?.hash,
                renewAt = meta?.let(nodeCert::renewAt),
                order = nodeCert.orders.state(),
                running = nodeCert.running,
                due = plan?.let { nodeCert.due(meta, CertNames.of(cfg, it), cfg.acme.directoryUrl(), now / 1000) },
                history = nodeCert.orders.history(),
                contact = nodeCert.email(cfg),
                probes = certProbes,
                reachable = nodeObs.mapValues { it.value.reachable },
                caaLive = caaLive,
                nowMs = now,
            ),
        )
    }

    /**
     * Orders the certificate in the next pass. With [replaceKey], the order uses a new key, and
     * HomeGAP revokes the old certificate.
     */
    suspend fun orderCertificate(replaceKey: Boolean): String = onLoop { orderCertificateOnLoop(replaceKey) }

    private fun orderCertificateOnLoop(replaceKey: Boolean): String {
        check(!dryRun) { "Dry run: HomeGAP orders no certificate" }
        check(!state.snapshot().pauses.global) { "Reconciliation is paused. Resume it to order a certificate." }
        nodeCert.request(replaceKey)?.let { throw IllegalStateException(it) }
        val request = if (replaceKey) "a new key was asked for" else "an order was asked for"
        log.change("certificates", "$request from the dashboard")
        trigger("certificate order")
        return if (replaceKey) {
            "A new key and certificate are ordered now. The old certificate is revoked once the new one is out."
        } else {
            "The certificate order starts now"
        }
    }

    /** Sends the current certificate to [nodeId] again in the next pass. */
    suspend fun resendCertificate(nodeId: String): String = onLoop { resendCertificateOnLoop(nodeId) }

    private suspend fun resendCertificateOnLoop(nodeId: String): String {
        requireNotNull(config.node(nodeId)) { "unknown node $nodeId" }
        check(!dryRun) { "Dry run: HomeGAP sends nothing to the nodes" }
        check(!state.snapshot().pauses.global && nodeId !in state.snapshot().pauses.nodes) {
            "$nodeId is paused. Resume it to send the certificate."
        }
        checkNotNull(nodeCert.current()) { "There is no certificate to send" }
        state.updateNode(nodeId) { it.copy(facts = it.facts - "routesHash" + (ResourceReconciler.CERT_RESEND to "1")) }
        trigger("certificate resend:$nodeId")
        return "The certificate goes to $nodeId in this pass"
    }

    /** Runs the TLS probes again: they run in each pass. */
    fun checkCertificates(): String {
        check(!dryRun) { "Dry run: HomeGAP does not reach the nodes" }
        trigger("certificate check")
        return "The TLS probes run again in this pass"
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
        val context = ReconcileContext(
            config = config,
            state = state,
            log = log,
            ssh = ssh,
            localHttp = localHttp,
            dryRun = true,
            diff = DiffCollector(),
        )
        val resourceRec = ResourceReconciler(context)
        val json = buildJsonObject {
            put("frontDoor", plan.frontDoor?.hostname)
            putJsonArray("services") {
                plan.services.forEach { service ->
                    addJsonObject {
                        put("key", service.key)
                        put("hostname", service.hostname)
                        put("container", service.containerName)
                        put("target", "${service.targetHost}:${service.port}")
                        put("protocol", service.protocol.label)
                        put("tls", service.tls.label)
                        put("access", service.access.label)
                        put("auth", service.auth.label)
                        put("wildcard", service.wildcard)
                        put("regions", service.regions?.joinToString(",") ?: "all")
                        put("paused", service.paused)
                    }
                }
            }
            putJsonObject("nodes") {
                plan.nodes.forEach { (id, nodePlan) ->
                    putJsonObject(id) {
                        put("managementHost", nodePlan.managementHost)
                        put(
                            "blueprint",
                            resourceRec.displayBlueprint(
                                nodePlan.publicResources.filter { !it.paused },
                                nodePlan.privateResources.filter { !it.paused },
                                config.siteNiceId(id),
                            ),
                        )
                        putJsonArray("sniRoutes") { nodePlan.sniRoutes.forEach { add(NodeTemplates.sniRule(it)) } }
                        putJsonArray("rawPorts") { nodePlan.rawPorts.forEach { add(it.entryPoint) } }
                    }
                }
            }
            putJsonArray("dns") {
                plan.dns.forEach { record ->
                    val geo = if (record.geo) " geo" else ""
                    val target = "${record.type} ${record.fqdn} -> ${record.value} [${record.nodeId}]"
                    add("$target ${record.purpose}$geo monitor=${record.monitor}")
                }
            }
        }
        return PRETTY_JSON.encodeToString(json)
    }

    // ------------------------------------------------------------------ speed tests

    fun speedOverview(): SpeedTestOverview {
        val cfg = config
        val speedtest = cfg.speedtest
        return SpeedTestOverview(
            enabled = speedtest.enabled,
            autoUrl = if (speedtest.enabled) "https://${speedtest.autoHost(cfg.domain)}/" else null,
            nodes = cfg.nodes.map { it.id },
            results = speedStore.recent(),
            streams = speedtest.streams,
        )
    }

    /** A device test link valid for `speedtest.link_ttl`. The token travels in the URL fragment. */
    fun createSpeedLink(): SpeedTestLinkDto {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        val (token, expiresAt) = speedTokens.issue(parseDuration(cfg.speedtest.linkTtl).inWholeMilliseconds)
        return SpeedTestLinkDto(
            autoUrl = "https://${cfg.speedtest.autoHost(cfg.domain)}/#t=$token",
            nodeUrls = cfg.nodes.associate {
                it.id to "https://${cfg.speedtest.nodeHost(cfg.domain, it.id)}/#t=$token"
            },
            expiresAt = expiresAt,
        )
    }

    /** The homelab ↔ node test that runs now, with its progress. Null when none runs. */
    fun speedLive(): SpeedTestLiveDto? = speedTokens.live.snapshot()

    /** Measures homelab ↔ node through the tunnel. The node runs the test over SSH. */
    suspend fun runTunnelSpeedTest(nodeId: String, streams: Int? = null): SpeedTestResultDto =
        onLoop { runTunnelSpeedTestOnLoop(nodeId, streams) }

    private suspend fun runTunnelSpeedTestOnLoop(nodeId: String, streams: Int?): SpeedTestResultDto {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        val node = requireNotNull(cfg.node(nodeId)) { "unknown node $nodeId" }
        check(!dryRun) { "speed tests need remote access (dry-run is on)" }
        check(speedRunning.add(nodeId)) { "a speed test for $nodeId is already running" }
        val (token, _) = speedTokens.issue(SPEED_TOKEN_TTL_MS, limited = false)
        speedTokens.live.start(token, nodeId)
        // Wakes the event streams, which then send the live gauge a few times a second.
        revision.value++
        try {
            val host = cfg.speedtest.nodeHost(cfg.domain, nodeId)
            val seconds = cfg.speedtest.phaseSeconds
            val streamCount = (streams ?: cfg.speedtest.streams).coerceIn(1, TunnelSpeedTest.MAX_STREAMS)
            val session = ssh.session(node)
            val script = TunnelSpeedTest.script(host, token, seconds, streamCount)
            val run = session.runScript(script, seconds * 2 + SPEED_SCRIPT_MARGIN_SECONDS)
            val parsed = TunnelSpeedTest.parse(run.stdout, host, streamCount)
            val (homelabDown, homelabUp) = speedTokens.live.hostCpu(token)
            val error = parsed.error
                ?: if (!run.ok) run.stderr.trim().take(SPEED_ERROR_LENGTH).ifEmpty { "exit ${run.exitCode}" } else null
            val mtu = cfg.tunnels.mtu ?: "default"
            val wireguard = if (cfg.tunnels.native) ", kernel wireguard" else ""
            val result = SpeedTestResultDto(
                id = newResultId(),
                at = nowMillis(),
                kind = "tunnel",
                nodeId = nodeId,
                pingMs = parsed.pingMs,
                jitterMs = parsed.jitterMs,
                downMbps = parsed.downMbps,
                upMbps = parsed.upMbps,
                pingSamples = parsed.samples,
                error = error,
                streams = streamCount,
                downStreamsMbps = parsed.downStreams,
                upStreamsMbps = parsed.upStreams,
                downCpu = parsed.downCpu,
                upCpu = parsed.upCpu,
                homelabDownCpu = homelabDown,
                homelabUpCpu = homelabUp,
                client = "node $nodeId (newt mtu $mtu$wireguard)",
            )
            speedStore.add(result)
            val measured = listOf(
                "ping ${oneDecimal(parsed.pingMs)} ms",
                "jitter ${oneDecimal(parsed.jitterMs)} ms",
                "down ${oneDecimal(parsed.downMbps)} Mbps",
                "up ${oneDecimal(parsed.upMbps)} Mbps",
            ).joinToString(", ")
            log.info(
                "speedtest",
                "tunnel: $measured ($streamCount streams)${error?.let { " ($it)" }.orEmpty()}",
                nodeId,
            )
            return result
        } finally {
            speedTokens.live.stop(token)
            speedTokens.revoke(token)
            speedRunning.remove(nodeId)
        }
    }

    /** [value] with one decimal, cut and not rounded, or `-` without a value. */
    private fun oneDecimal(value: Double?): String =
        value?.let { ((it * 10).toLong() / 10.0).toString() } ?: "-"

    @Volatile private var speedRun: SpeedRunDto? = null
    private var speedRunJob: Job? = null

    /**
     * Tests every node, one at a time, on the orchestrator: the run goes on if the dashboard is
     * closed, and its progress is in the snapshot ([StatusSnapshot.speedRun]).
     */
    suspend fun startSpeedRun(streams: Int?): String = onLoop { startSpeedRunOnLoop(streams) }

    private fun startSpeedRunOnLoop(streams: Int?): String {
        val cfg = config
        require(cfg.speedtest.enabled) { "speed tests are disabled (speedtest.enabled=false)" }
        check(speedRunJob?.isActive != true) { "a test of all nodes is already running" }
        val scope = checkNotNull(jobScope) { "the controller is not running" }
        val nodes = cfg.nodes.map { it.id }
        require(nodes.isNotEmpty()) { "there are no nodes to test" }
        val count = (streams ?: cfg.speedtest.streams).coerceIn(1, TunnelSpeedTest.MAX_STREAMS)
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
                        if (ok) {
                            run.copy(done = run.done + id)
                        } else {
                            run.copy(done = run.done + id, failed = run.failed + id)
                        }
                    }
                    revision.value++
                }
            } finally {
                speedRun = speedRun?.copy(
                    current = null,
                    finishedAt = nowMillis(),
                    cancelled = speedRun?.done?.size != nodes.size,
                )
                revision.value++
            }
        }
        log.info("speedtest", "testing all ${nodes.size} nodes, one at a time ($count streams)")
        return "Testing all ${nodes.size} nodes, one at a time"
    }

    /** Stops a run after the node being tested now. */
    suspend fun cancelSpeedRun(): String = onLoop { cancelSpeedRunOnLoop() }

    private fun cancelSpeedRunOnLoop(): String {
        val job = speedRunJob?.takeIf { it.isActive } ?: return "No test of all nodes is running"
        job.cancel()
        return "Stopped the test of all nodes"
    }

    // ------------------------------------------------------------------ dashboard actions

    suspend fun setPause(scope: String, id: String?, paused: Boolean): String =
        onLoop { setPauseOnLoop(scope, id, paused) }

    private suspend fun setPauseOnLoop(scope: String, id: String?, paused: Boolean): String {
        when (scope) {
            "global" -> state.update { it.copy(pauses = it.pauses.copy(global = paused)) }
            "node" -> {
                requireNotNull(id) { "node id required" }
                state.update {
                    val nodes = if (paused) it.pauses.nodes + id else it.pauses.nodes - id
                    it.copy(pauses = it.pauses.copy(nodes = nodes))
                }
            }
            "service" -> {
                requireNotNull(id) { "service key required" }
                state.update {
                    val services = if (paused) it.pauses.services + id else it.pauses.services - id
                    it.copy(pauses = it.pauses.copy(services = services))
                }
            }
            else -> throw IllegalArgumentException("scope must be global|node|service")
        }
        val target = "$scope${id?.let { " $it" }.orEmpty()}"
        log.change("controller", "${if (paused) "paused" else "resumed"} $target")
        if (!paused) trigger("resume")
        return "${if (paused) "Paused" else "Resumed"} $target"
    }

    /**
     * Changes the config file. The change starts from the file on disk and not from the copy in
     * memory, so an edit of the file by hand since the last pass stays.
     */
    private fun editConfig(change: (AppConfig) -> AppConfig): AppConfig {
        val current = try {
            ConfigLoader.load(configPath)
        } catch (e: Exception) {
            throw IllegalStateException("The config file has an error, so HomeGAP does not write it: ${e.message}")
        }
        require(current.dashboard.allowConfigEdits) { "config edits are disabled (dashboard.allow_config_edits=false)" }
        val next = change(current)
        ConfigLoader.save(configPath, next)
        config = next
        return next
    }

    suspend fun addNode(node: NodeConfig): String = onLoop { addNodeOnLoop(node) }

    private suspend fun addNodeOnLoop(node: NodeConfig): String {
        editConfig { current ->
            require(current.node(node.id) == null) { "node ${node.id} already exists" }
            current.copy(nodes = current.nodes + node)
        }
        state.updateNode(node.id) { it.copy(pendingDecommission = false, teardownError = null) }
        log.change("controller", "added node ${node.id} to config", node.id)
        trigger("config")
        return "Node ${node.id} added; provisioning will start on the next pass"
    }

    /** Sets (or with null, clears) where the homelab is, for the maps. Rewrites the config file. */
    suspend fun setHomelabLocation(place: PlaceDto?): String = onLoop { setHomelabLocationOnLoop(place) }

    private fun setHomelabLocationOnLoop(place: PlaceDto?): String {
        val location = place?.let {
            require(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0) { "coordinates are out of range" }
            GeoLocation(it.latitude, it.longitude, it.name?.trim()?.ifBlank { null })
        }
        editConfig { it.copy(homelab = it.homelab.copy(location = location)) }
        revision.value++
        val change = if (location == null) {
            "cleared the homelab's location"
        } else {
            "set the homelab's location${location.name?.let { " to $it" }.orEmpty()}"
        }
        log.change("controller", change)
        return if (location == null) "Homelab location cleared" else "Homelab location saved"
    }

    /**
     * Sets where node [id] is: its coordinates steer geo DNS and place it on the maps. Rewrites
     * its config entry and runs a pass so DNS follows.
     */
    suspend fun setNodeLocation(id: String, place: PlaceDto): String = onLoop { setNodeLocationOnLoop(id, place) }

    private fun setNodeLocationOnLoop(id: String, place: PlaceDto): String {
        require(place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0) { "coordinates are out of range" }
        val location = GeoLocation(place.latitude, place.longitude, place.name?.trim()?.ifBlank { null })
        editConfig { current ->
            requireNotNull(current.node(id)) { "unknown node $id" }
            current.copy(nodes = current.nodes.map { if (it.id == id) it.copy(location = location) else it })
        }
        val destination = location.name ?: "${location.latitude}, ${location.longitude}"
        log.change("controller", "moved $id to $destination", id, fields = mapOf("node" to id))
        trigger("config")
        return "$id is now in ${location.name ?: "the picked place"}; DNS follows on the next pass"
    }

    /** Things that can be undone for a while: an id each, with how to undo it. */
    private class Undoable(val expiresAt: Long, val undo: suspend () -> String)

    private val undoables = mutableMapOf<String, Undoable>()

    /** Keeps [undo] for [ttlMs] and returns the id that runs it ([undo]). */
    suspend fun offerUndo(ttlMs: Long, undo: suspend () -> String): String = onLoop { offerUndoOnLoop(ttlMs, undo) }

    private fun offerUndoOnLoop(ttlMs: Long, undo: suspend () -> String): String {
        val now = nowMillis()
        undoables.entries.removeAll { it.value.expiresAt < now }
        val id = Crypto.token(UNDO_ID_LENGTH)
        undoables[id] = Undoable(now + ttlMs, undo)
        return id
    }

    /** Undoes an earlier action, while it still can be. */
    suspend fun undo(id: String): String = onLoop { undoOnLoop(id) }

    private suspend fun undoOnLoop(id: String): String {
        val undoable = undoables.remove(id)?.takeIf { it.expiresAt >= nowMillis() }
            ?: throw IllegalArgumentException("that can no longer be undone")
        return undoable.undo()
    }

    /**
     * Removes [id] from the config (DNS drains at once). An undo can add it back for a day.
     * Returns the message and the undo id.
     */
    suspend fun removeNode(id: String): Pair<String, String> = onLoop { removeNodeOnLoop(id) }

    private suspend fun removeNodeOnLoop(id: String): Pair<String, String> {
        var removed: NodeConfig? = null
        editConfig { current ->
            val found = requireNotNull(current.node(id)) { "unknown node $id" }
            removed = found
            current.copy(nodes = current.nodes - found)
        }
        val node = removed!!
        state.updateNode(id) { it.copy(pendingDecommission = true, facts = it.facts + nodeFacts(node)) }
        log.change("controller", "removed node $id from config (pending decommission)", id)
        trigger("config")
        val undo = offerUndo(ttlMs = UNDO_NODE_MS) {
            check(state.node(id).pendingDecommission) { "$id was already decommissioned" }
            check(state.node(id).teardownError == null) { "the teardown of $id already started" }
            addNode(node)
            "Added $id back"
        }
        val message = "Node $id removed from config. " +
            "DNS drains on the next pass; confirm decommission to stop its stack."
        return message to undo
    }

    private fun nodeFacts(node: NodeConfig): Map<String, String> {
        val access = mapOf(
            "cfg.host" to node.host,
            "cfg.user" to node.sshUser,
            "cfg.port" to node.sshPort.toString(),
            "cfg.key" to node.sshKeyFile,
        )
        val addresses = listOfNotNull(
            node.ipv4()?.let { "cfg.ipv4" to it },
            node.publicIpv6?.let { "cfg.ipv6" to it },
        )
        return access + addresses
    }

    /** A node that is not in the config, from what the state knows about its SSH access. */
    private fun removedNode(id: String, nodeState: NodeState): NodeConfig? {
        val host = nodeState.facts["cfg.host"] ?: return null
        return NodeConfig(
            id = id,
            host = host,
            sshUser = nodeState.facts["cfg.user"] ?: "root",
            sshPort = nodeState.facts["cfg.port"]?.toIntOrNull() ?: 22,
            sshKeyFile = nodeState.facts["cfg.key"].orEmpty(),
            publicIpv4 = nodeState.facts["cfg.ipv4"] ?: "0.0.0.0",
            publicIpv6 = nodeState.facts["cfg.ipv6"],
        )
    }

    /**
     * The nodes of the mesh and the cluster: the nodes in the config, and each removed node whose
     * teardown did not start. A removed node stays in the cluster vote until then, so an undo of
     * the removal needs no new join.
     */
    private fun clusterNodes(cfg: AppConfig): List<NodeConfig> {
        val configured = cfg.nodes.map { it.id }.toSet()
        return cfg.nodes + state.snapshot().nodes.mapNotNull { (id, nodeState) ->
            // The pending flag is not a condition: a pass that began before an undo has the old config
            // and the new state, and it must not drop the node.
            if (id in configured || nodeState.teardownError != null) null else removedNode(id, nodeState)
        }
    }

    /**
     * Tears down a node already removed from config. The caller must give the node id to confirm.
     *
     * The node leaves the cluster vote first. If the teardown fails, the node stays in the state
     * with the error, because its stack can still run. A second call tries again. With [force],
     * HomeGAP forgets the node without a teardown.
     */
    suspend fun confirmDecommission(id: String, confirm: String, force: Boolean = false): String =
        onLoop { confirmDecommissionOnLoop(id, confirm, force) }

    private suspend fun confirmDecommissionOnLoop(id: String, confirm: String, force: Boolean): String {
        require(confirm == id) { "confirmation must equal the node id" }
        require(config.node(id) == null) { "node $id is still in the config; remove it first" }
        val ctx = ReconcileContext(
            config = config,
            state = state,
            log = log,
            ssh = ssh,
            localHttp = localHttp,
            dryRun = dryRun,
            diff = DiffCollector(),
        )
        val node = removedNode(id, state.node(id))
        if (node != null && !dryRun && !force) {
            state.updateNode(id) { it.copy(pendingDecommission = true, teardownError = TEARDOWN_STARTED) }
            if (config.cluster != null) {
                runCatchingCancellable { clusterRec.dropMember(ctx, id) }.onFailure {
                    log.warn(
                        "cluster",
                        "could not remove $id from the cluster vote before its teardown: ${it.message}",
                        id,
                    )
                }
            }
            try {
                NodeReconciler(ctx).decommission(node)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val reason = e.message ?: e.toString()
                state.updateNode(id) { it.copy(teardownError = reason) }
                log.error(
                    "nodes",
                    "teardown failed: $reason. " +
                        "The stack of the node can still run. Decommission the node again, or forget it",
                    id,
                )
                trigger("decommission")
                throw IllegalStateException("The teardown of $id failed: $reason")
            }
        }
        state.removeNode(id)
        TunnelReconciler(ctx).prune(config.nodes.map { it.id }.toSet())
        log.change("nodes", if (force) "forgotten without a teardown" else "decommissioned", id)
        trigger("decommission")
        return if (force) "Node $id forgotten" else "Node $id decommissioned"
    }
}

private fun epochToIso(epochSeconds: Long): String = Instant.fromEpochSeconds(epochSeconds).toString()

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
        !cut && ongoing -> current.first to now
        else -> current
    }
}

/** A Pangolin API probe this much slower than the fastest probe of its node is slow, and the node degraded. */
private const val API_PROBE_SLOW_MS = 300.0

/** The limit of the Pangolin API probe of a node whose fastest probe took [best] ms. */
internal fun apiSlowLimit(best: Double?): Double = (best ?: 0.0) + API_PROBE_SLOW_MS

/** Above this, a service request through an edge is slow. */
internal const val SERVICE_SLOW_MS = 1000.0

/**
 * Lines for the node's observe script: one request per hostname through the node's own proxy
 * (`--resolve` to 127.0.0.1), printing `H <host> <code> <seconds>`. Each waits at most 3 s.
 */
internal fun serviceProbeScript(hosts: List<String>): String = hosts.joinToString(separator = "") { host ->
    val quoted = shQuote(host)
    $$"\necho \"H $$host $(curl -s -o /dev/null --max-time 3 --resolve $$quoted:443:127.0.0.1 " +
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

private val STATUS_RECHECK = 5_000.milliseconds
private val STATUS_IDLE = 60_000.milliseconds
private val STATUS_SETTLE = 100.milliseconds
private val DOCKER_RECONNECT_DELAY = 5.seconds
private const val TEARDOWN_STARTED = "the teardown did not finish"

private const val OBSERVE_TIMEOUT_SECONDS = 60
private const val SSH_ERROR_LENGTH = 200
private const val DAY_MS = 86_400_000L

/** A certificate with fewer days left degrades the node. */
private const val CERT_EXPIRY_WARNING_DAYS = 14
private const val LICENSE_REJECTED = "rejected: key bound to another instance"

/** A tunnel status file older than this does not show a process that runs. */
private const val TUNNEL_STATUS_FRESH_MS = 60_000L
private const val STACK_HASH_LENGTH = 8

/** The facts of a node that the dashboard shows. */
private val DASHBOARD_FACTS =
    setOf("os", "arch", "kernel", "docker", "mem_kb", "vcpu", "disk_free_gb", "tcp_cc", "qdisc", "edge_tcp")

/** The names of the monitor types of Bunny, by their number. */
private val MONITOR_TYPES = listOf("none", "ping", "http", "monitor")

private const val SPEED_TOKEN_TTL_MS = 5 * 60_000L
private const val SPEED_SCRIPT_MARGIN_SECONDS = 120
private const val SPEED_ERROR_LENGTH = 300
private const val UNDO_ID_LENGTH = 12

/** How long removing a node can be undone (until it is decommissioned). */
private const val UNDO_NODE_MS = 24 * 3_600_000L

/** The trigger for one pass that applies changes while paused. */
internal const val APPLY_ONCE = "apply-once"
