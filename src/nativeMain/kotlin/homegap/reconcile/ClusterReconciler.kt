package homegap.reconcile

import homegap.api.ClusterMemberDto
import homegap.api.ClusterStatusDto
import homegap.api.PrimaryChangeDto
import homegap.clients.EtcdClient
import homegap.clients.PatroniClient
import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.clients.ValkeyCli
import homegap.config.ClusterConfig
import homegap.config.NodeConfig
import homegap.config.homelabVotesOnly
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.ClusterProxy
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ClusterTemplates.Member
import homegap.provision.ExitNodes
import homegap.provision.Mesh
import homegap.provision.MeshProbe
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Cluster domain: the database layer that the homelab and each node share.
 *
 * - **Homelab member:** its containers are part of the homelab compose and wait for their config.
 *   HomeGAP writes the config into the data directory, which the containers share.
 * - **Nodes:** each node runs its member as its own compose project over SSH.
 * - **etcd grows one learner at a time:** HomeGAP adds a node as a learner, starts it, and promotes it
 *   when it is level with the others. A member that does not run yet thus never stalls the vote.
 * - **The database services of a node start** when the node is a voting etcd member and the database
 *   of the cluster exists. A node thus never bootstraps a second cluster.
 * - **Switch-back:** when the preferred member (the homelab) is a caught-up replica for
 *   `switch_back_after`, HomeGAP switches the PostgreSQL primary back to it, then the Valkey primary.
 *
 * The controller keeps one instance. The instance holds the switch-back timer, and the log shows an
 * error that stays only once.
 */
class ClusterReconciler(
    dataDir: String,
    private val http: HttpClient,
    /** Where the homelab member's containers read their config (see `AppConfig.clusterConfigDir`). */
    private val configDir: String = "$dataDir/cluster/config",
    /** Whether the state shows a cluster that already runs (a node on the mesh, a bootstrapped Pangolin). */
    private val inUse: () -> Boolean = { false },
) {
    private val dir = "$dataDir/cluster"
    private val secretsFile = "$dir/secrets.json"

    /**
     * HomeGAP writes this file when the database has a leader for the first time. A later outage
     * never sends the nodes back to etcd only.
     */
    private val initializedFile = "$dir/initialized"
    private val roleFile = "$dir/homelab-role"
    private val backupDir = "$dir/backups"
    private val historyFile = "$dir/history.json"
    private val maintenanceFile = "$dir/maintenance"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val historyJson = Json { ignoreUnknownKeys = true }

    @Volatile private var loadedSecrets: ClusterSecrets? = null

    /** The homelab only votes (`cluster.homelab_role: vote`), as of the last pass. */
    @Volatile private var voteOnly = false
    private val lastErrors = mutableMapOf<String, String>()

    /** The members whose database serves (the primary, or a streaming replica), as of the last pass. */
    @Volatile private var ready: Set<String> = emptySet()

    /** The members and the database view of the last pass, for a switchover from the dashboard. */
    @Volatile private var lastView: Pair<List<Member>, List<PatroniClient.Member>>? = null

    private var preferredCaughtUpSince: Long? = null
    private var switchBackDue: Long? = null

    /** The cause of the next change of primary, when HomeGAP asks for the change. If null, a failover. */
    private var pendingCause: String? = null

    /** When HomeGAP last told a Sentinel to forget the members that left (epoch ms). */
    private var sentinelResetAt = 0L
    private var backupTriedAt = 0L
    private var exitPrunedAt = 0L

    /** The number of passes in a row in which each node took its cluster config. See [ensureEtcdAuth]. */
    private var credentialPasses = 0
    private var authWaitLogged = false

    /** The node whose member moves to a new image now, if any. This node never becomes a primary. */
    @Volatile private var updatingNode: String? = null

    /** For each node, the last reason why its image update waits. The log shows each new reason once. */
    private val imageWaitNoted = mutableMapOf<String, String>()

    /** For each node, the image whose pull last failed there, and the time of the failure. */
    private val pullFailed = mutableMapOf<String, Pair<MemberImage, Long>>()

    /** The times in the last hour when HomeGAP moved a primary on purpose (epoch ms), for the proxy check. */
    @Volatile var plannedMoves: List<Long> = emptyList()
        private set

    /** A node that takes part in the cluster, and its member. */
    private data class NodeMember(val node: NodeConfig, val member: Member)

    /** The values that stay the same during one pass. */
    private class Pass(
        val ctx: ReconcileContext,
        val cluster: ClusterConfig,
        val homelab: Member,
        val nodeMembers: List<NodeMember>,
        val secrets: ClusterSecrets,
        /** The cluster image that the member of the homelab runs, or null if HomeGAP cannot read it. */
        val image: MemberImage?,
    ) {
        /** Each member votes. */
        val members = listOf(homelab) + nodeMembers.map { it.member }
    }

    /** What a pass reads from the cluster before it changes a member. A null list means no answer. */
    private class Snapshot(
        val etcdMembers: List<EtcdClient.Member>?,
        val database: List<PatroniClient.Member>?,
        /** The database of the cluster exists. */
        val initialized: Boolean,
    )

    /** How a node runs its member: the etcd members that it joins, its services and their image. */
    private class MemberSetup(
        val etcdPeers: List<Member>,
        val services: List<String>,
        val image: String,
        /** Restart the services one at a time, for a new image. */
        val rolling: Boolean = false,
    )

    /** What one member says about itself. */
    private class MemberProbe(val etcdHealthy: Boolean, val valkeyRole: String?)

    /** The member of the node can serve its Pangolin. The provisioning jobs read this between passes. */
    fun memberReady(id: String): Boolean = id in ready

    /**
     * [image] is the cluster image that the member of the homelab runs, or null if HomeGAP cannot
     * read it. Each pass moves one node to that image, see [updateNodeImage].
     */
    suspend fun reconcile(
        ctx: ReconcileContext,
        nodes: List<NodeConfig>,
        slots: Map<String, Int>,
        image: MemberImage? = null,
    ): ClusterStatusDto? {
        val cluster = ctx.config.cluster ?: return null
        voteOnly = cluster.homelabVotesOnly
        keepRole(cluster)
        val secrets = secrets()
        val pass = Pass(
            ctx = ctx,
            cluster = cluster,
            homelab = Member(Mesh.HOMELAB, Mesh.homelabAddress(cluster.mesh)),
            nodeMembers = nodeMembers(ctx, cluster, nodes, slots),
            secrets = secrets,
            image = image,
        )
        // Each member votes. The database and Valkey are on [data], without the homelab when it only votes.
        val data = data(pass.members)
        writeHomelab(pass)

        val etcd = EtcdClient(
            http = http,
            base = etcdUrl(pass.homelab.meshIp),
            user = ClusterTemplates.ETCD_USER,
            password = secrets.etcd,
        )
        val patroni = PatroniClient(http, ClusterTemplates.REST_USER, secrets.rest)
        val etcdMembers = runCatchingCancellable { etcd.members() }.getOrNull()
        report(ctx, "etcd", if (etcdMembers == null) "the homelab's etcd is not reachable yet" else null)
        val database = databaseView(patroni, data)
        if (database != null) {
            ready = database
                .filter { (it.role == LEADER && it.state == "running") || it.state == STREAMING }
                .map { it.name }
                .toSet()
            // Recorded now, not after the sync of the nodes, which can take minutes (for example
            // while a node pulls an image). Planned maintenance needs only this view.
            lastView = data to database
        }
        val snapshot = Snapshot(etcdMembers, database, initialized = markInitialized(database))

        syncNodes(pass, etcd, snapshot, imageWaitAtStart = imageUpdateWait(snapshot, pass.members, data))

        if (snapshot.initialized) {
            forgetDeparted(ctx, ValkeyCli(secrets.valkey, secrets.sentinel), pass.members)
        }
        if (database != null) {
            recordPrimary(database)
        }
        // Without a database at home there is no member to switch back to. The primary stays where
        // the cluster elects it.
        val switchBack = if (voteOnly) null else switchBack(pass, patroni, database)
        if (voteOnly && snapshot.initialized) {
            backupHome(pass)
        }
        return status(pass, snapshot, switchBack)
    }

    /**
     * A node takes part after it joins the mesh. A node that left the config stays a member until
     * its teardown starts.
     */
    private fun nodeMembers(
        ctx: ReconcileContext,
        cluster: ClusterConfig,
        nodes: List<NodeConfig>,
        slots: Map<String, Int>,
    ): List<NodeMember> =
        nodes
            .filter { ctx.state.node(it.id).teardownError == null && ctx.state.node(it.id).meshPublicKey != null }
            .mapNotNull { node ->
                slots[node.id]?.let { slot -> NodeMember(node, Member(node.id, Mesh.nodeAddress(cluster.mesh, slot))) }
            }

    /** Returns whether the database of the cluster exists, and records the first time it has a leader. */
    private fun markInitialized(database: List<PatroniClient.Member>?): Boolean {
        if (Files.exists(initializedFile)) return true
        val hasLeader = database?.leader() != null
        if (hasLeader) {
            Files.writeAtomic(initializedFile, "")
        }
        return hasLeader
    }

    /**
     * The reason why no node moves to a new image in this pass, or null. The member of a node moves
     * only while the whole cluster is healthy (each member votes and serves the database) and no
     * maintenance runs. Only one node moves in each pass, so at most one member restarts at a time.
     */
    private fun imageUpdateWait(snapshot: Snapshot, members: List<Member>, data: List<Member>): String? {
        val etcdMembers = snapshot.etcdMembers
        val notServing = data.map { it.id }.filter { it !in ready }
        val notAllVote = etcdMembers == null || etcdMembers.any { it.learner } || etcdMembers.size != members.size
        return when {
            maintenanceUntil() != null -> "planned maintenance of the homelab is under way"
            snapshot.database == null -> "the database can't be read"
            notAllVote -> "not every member is in the cluster vote"
            notServing.isNotEmpty() -> {
                val verb = if (notServing.size == 1) "is" else "are"
                "${notServing.joinToString(", ")} $verb not serving the database"
            }
            else -> null
        }
    }

    /**
     * Brings the member of each node in step, one node after the other. Only one node joins the vote
     * at a time, and only one node moves to a new image in a pass. Then etcd gets its authentication.
     */
    private suspend fun syncNodes(pass: Pass, etcd: EtcdClient, snapshot: Snapshot, imageWaitAtStart: String?) {
        val ctx = pass.ctx
        val etcdMembers = snapshot.etcdMembers ?: return
        if (!ctx.pauses().global) {
            removeDeparted(ctx, etcd, etcdMembers, pass.nodeMembers.map { it.member })
        }
        var imageWait = imageWaitAtStart
        var joining = etcdMembers.any { it.learner }
        val synced = mutableSetOf<String>()
        for (nodeMember in pass.nodeMembers) {
            val (node, member) = nodeMember
            if (ctx.nodePaused(node.id)) continue
            val existing = etcdMembers.firstOrNull { it.belongsTo(member) }
            runCatchingCancellable {
                when {
                    // Docker comes with the provisioning of the node. Until then the node can't run a member.
                    existing == null && !joining && !hasDocker(ctx, node) -> Unit
                    existing == null && !joining -> {
                        etcd.addLearner(ClusterTemplates.peerUrl(member))
                        joining = true
                        ctx.log.change(DOMAIN, "joining the cluster vote (etcd learner)", node.id)
                        val etcdPeers = etcdInitial(etcd.members(), pass.members)
                        syncNode(pass, nodeMember, MemberSetup(etcdPeers, ETCD_ONLY, nodeImage(pass, node)))
                    }
                    // A different node joins now. This node waits for its turn.
                    existing == null -> Unit
                    existing.learner -> {
                        val etcdPeers = etcdInitial(etcdMembers, pass.members)
                        syncNode(pass, nodeMember, MemberSetup(etcdPeers, ETCD_ONLY, nodeImage(pass, node)))
                        val promoted = runCatchingCancellable { etcd.promote(existing.id) }.isSuccess
                        if (promoted) {
                            ctx.log.change(DOMAIN, "joined the cluster vote", node.id)
                        }
                    }
                    else -> {
                        val etcdPeers = etcdInitial(etcdMembers, pass.members)
                        // The homelab creates the database when it holds one. If it does not, the nodes
                        // create it: Patroni lets exactly one of them do so, and the others follow it.
                        val services = if (snapshot.initialized || voteOnly) ALL_SERVICES else ETCD_ONLY
                        syncNode(pass, nodeMember, MemberSetup(etcdPeers, services, nodeImage(pass, node)))
                        val canUpdate = pass.image != null && snapshot.initialized
                        if (canUpdate && updateNodeImage(pass, nodeMember, etcdPeers, imageWait)) {
                            imageWait = "another node is being updated"
                        }
                    }
                }
                report(ctx, node.id, null)
                synced += node.id
            }.onFailure { report(ctx, node.id, it.message ?: it.toString()) }
        }
        if (!ctx.pauses().global) {
            runCatchingCancellable {
                ensureEtcdAuth(ctx, etcd, allSynced = pass.nodeMembers.all { it.node.id in synced })
            }.onFailure { ctx.log.warn(DOMAIN, "cannot turn on the authentication of etcd: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ switchover and maintenance

    /**
     * Makes [to] the PostgreSQL primary now (a planned switchover, which loses no writes). [to] must
     * be a caught-up replica. If the primary moves away from the preferred member, the switch-back
     * returns it after the preferred member is caught up for the hold.
     */
    suspend fun switchover(to: String): String {
        val (members, database) = checkNotNull(lastView) { "the cluster has not been read yet" }
        val leader = checkNotNull(database.leader()) { "there is no primary right now" }
        require(to != leader.name) { "$to is already the primary" }
        check(to != updatingNode) { "$to is being updated to a new cluster image; try again in a minute" }
        val target = requireNotNull(database.firstOrNull { it.name == to }) { "$to is not a database member" }
        check(target.state == STREAMING && target.lagBytes == 0L) {
            "$to is not a caught-up replica (${target.state})"
        }
        val leaderMember = members.first { it.id == leader.name }
        notePlannedMove()
        runCatchingCancellable {
            PatroniClient(http, ClusterTemplates.REST_USER, secrets().rest)
                .switchover(restUrl(leaderMember), leader.name, to)
        }.onFailure { e ->
            // Patroni refuses when the primary moved after the last pass.
            val leaderMoved = e.message?.contains("leader name does not match") == true
            check(!leaderMoved) {
                "The primary changed a moment ago. Wait for the dashboard to refresh and try again."
            }
            throw e
        }
        // The view is stale until the next pass. A second request waits for that pass.
        lastView = null
        pendingCause = "manual"
        preferredCaughtUpSince = null
        return "Switched the database primary from ${leader.name} to $to"
    }

    private fun notePlannedMove() {
        val now = nowMillis()
        plannedMoves = plannedMoves.filter { it > now - PLANNED_MOVES_KEPT_MS } + now
    }

    /**
     * The time until which the primaries stay off the homelab for planned maintenance (epoch ms), or
     * null. A file keeps the time, because an upgrade restarts the orchestrator itself.
     */
    fun maintenanceUntil(now: Long = nowMillis()): Long? =
        Files.readTextOrNull(maintenanceFile)?.trim()?.toLongOrNull()?.takeIf { it > now }

    /**
     * Prepares the member of the homelab for a restart without an outage. Moves the PostgreSQL primary
     * (a planned switchover, which loses no writes) and the Valkey primary (a Sentinel failover) to
     * the nodes, and waits until the two primaries are there. Then no switch-back occurs until
     * [endMaintenance], or for [holdMs] at most, so a script that never ends cannot keep them away.
     * After that, the usual switch-back applies.
     */
    suspend fun beginMaintenance(cluster: ClusterConfig, holdMs: Long): String {
        if (cluster.homelabVotesOnly) {
            return "Maintenance: the homelab only votes, so there is no primary to move off it."
        }
        val start = nowMillis()
        Files.writeAtomic(maintenanceFile, (start + holdMs).toString(), MAINTENANCE_FILE_MODE)
        preferredCaughtUpSince = null
        try {
            return movePrimariesForMaintenance(start, holdMs)
        } catch (e: Exception) {
            // The start failed, so there is no hold. The switch-back must not wait for it.
            Files.delete(maintenanceFile)
            throw e
        }
    }

    private suspend fun movePrimariesForMaintenance(start: Long, holdMs: Long): String {
        // The member of the homelab restarts soon. A node that restarts its own member ends first,
        // and no new restart starts after the hold is on file, so two members are never down together.
        check(waitFor(NODE_UPDATE_WAIT_MS) { updatingNode == null }) {
            "node $updatingNode is still updating its cluster member after 10 minutes"
        }
        // The view comes from the last pass. Right after the orchestrator starts, there is possibly none.
        var view = lastView
        while (view == null && nowMillis() - start < FIRST_VIEW_WAIT_MS) {
            delay(2.seconds)
            view = lastView
        }
        val (members, _) = checkNotNull(view) { "the cluster has not been read yet; try again in a minute" }

        val notes = movePrimariesOff(Mesh.HOMELAB, members, "maintenance")
        lastView = null
        return "Maintenance: ${notes.joinToString("; ")}. Switch-back held for up to ${holdMs / 60_000} min."
    }

    /** Ends planned maintenance. The switch-back continues after the usual caught-up delay. */
    fun endMaintenance(): String {
        val wasInProgress = maintenanceUntil() != null
        Files.delete(maintenanceFile)
        preferredCaughtUpSince = null
        if (!wasInProgress) return "No maintenance was in progress"
        return "Maintenance ended; the primaries return to the homelab once it has been caught up " +
            "for the switch-back delay"
    }

    /**
     * Moves the PostgreSQL primary and the Valkey primary off member [off], if it holds them, and
     * waits until the two primaries are on other members. Returns a note for each primary.
     */
    private suspend fun movePrimariesOff(
        off: String,
        members: List<Member>,
        cause: String,
        prefer: String? = null,
    ): List<String> = listOf(
        moveDatabaseOff(off, members, cause, prefer),
        moveValkeyOff(off, members),
    )

    /**
     * A planned switchover, which loses no writes. The database goes to [prefer] if that member is a
     * caught-up replica. If it is not, the database goes to the replica with the smallest lag.
     */
    private suspend fun moveDatabaseOff(off: String, members: List<Member>, cause: String, prefer: String?): String {
        val patroni = PatroniClient(http, ClusterTemplates.REST_USER, secrets().rest)
        val database = checkNotNull(databaseView(patroni, members)) { "no database member answers" }
        val leader = database.leader()
        if (leader?.name != off) return "database primary already on ${leader?.name ?: "no member"}"

        val replicas = database.filter { it.name != off && it.name != updatingNode && it.state == STREAMING }
        val target = checkNotNull(
            replicas.firstOrNull { it.name == prefer && it.lagBytes == 0L }
                ?: replicas.minByOrNull { it.lagBytes ?: Long.MAX_VALUE },
        ) { "no caught-up replica can take over the database from $off" }
        notePlannedMove()
        patroni.switchover(restUrl(members.first { it.id == off }), off, target.name)
        pendingCause = cause
        val moved = waitFor(MOVE_TIMEOUT_MS) {
            val primary = databaseView(patroni, members)?.leader()?.name
            primary != null && primary != off
        }
        check(moved) { "the database primary did not move to ${target.name} within a minute" }
        return "database primary moved to ${target.name}"
    }

    /** A Sentinel failover. Sentinel selects the new Valkey primary, the homelab first. */
    private suspend fun moveValkeyOff(off: String, members: List<Member>): String {
        fun name(address: String?) = members.firstOrNull { it.meshIp == address }?.id ?: address ?: "no member"
        val valkey = secrets().let { ValkeyCli(it.valkey, it.sentinel) }
        val offAddress = members.first { it.id == off }.meshIp
        val master = sentinelMaster(valkey, members)
        if (master != offAddress) return "Valkey primary already on ${name(master)}"

        notePlannedMove()
        check(members.any { valkey.sentinelFailover(it.meshIp, ClusterTemplates.SCOPE) }) {
            "Sentinel refused the Valkey failover"
        }
        val moved = waitFor(MOVE_TIMEOUT_MS) {
            val current = sentinelMaster(valkey, members)
            current != null && current != offAddress
        }
        check(moved) { "the Valkey primary did not move off $off within a minute" }
        return "Valkey primary moved to ${name(sentinelMaster(valkey, members))}"
    }

    private suspend fun waitFor(timeoutMs: Long, check: suspend () -> Boolean): Boolean {
        val start = nowMillis()
        while (nowMillis() - start < timeoutMs) {
            if (check()) return true
            delay(1.seconds)
        }
        return check()
    }

    // ------------------------------------------------------------------ image update

    /** The image that the member of a node must run: the last image that HomeGAP gave it, or the configured one. */
    private fun nodeImage(pass: Pass, node: NodeConfig): String =
        pass.ctx.state.node(node.id).facts[CLUSTER_IMAGE_FACT] ?: pass.cluster.image

    /**
     * Moves the member of a node to the cluster image of the homelab, if the member runs a different
     * one. The node pulls the image first, and the member serves during the pull. Then the primaries
     * move off the node, and the services restart one at a time. Each service serves again before the
     * next one restarts. Returns whether an update started, so that the caller starts no other one in
     * this pass.
     */
    private suspend fun updateNodeImage(
        pass: Pass,
        nodeMember: NodeMember,
        etcdPeers: List<Member>,
        wait: String?,
    ): Boolean {
        val ctx = pass.ctx
        val image = pass.image ?: return false
        val (node, member) = nodeMember
        val ssh = ctx.ssh.session(node)
        val running = runningImages(ssh, memberDir(ctx))
        if (running.isEmpty() || running.all { it == image.created }) {
            imageWaitNoted.remove(node.id)
            return false
        }
        val what = "${image.ref} (built ${image.created.take(19).replace('T', ' ')} UTC)"
        if (wait != null) {
            // The log shows each reason once, not in every pass.
            if (imageWaitNoted.put(node.id, wait) != wait) {
                ctx.log.info(DOMAIN, "cluster image update to $what waits: $wait", node.id)
            }
            return false
        }
        imageWaitNoted.remove(node.id)
        // A pull that failed gets its next try after a while, not in every pass.
        pullFailed[node.id]?.let { (failed, at) ->
            if (failed == image && nowMillis() - at < PULL_RETRY_MS) return false
        }
        if (pulledImage(ssh, image) != image.created) {
            pullFailed[node.id] = image to nowMillis()
            val retryMinutes = PULL_RETRY_MS / 60_000
            ctx.log.warn(
                DOMAIN,
                "could not get the cluster image $what onto this node; " +
                    "its member keeps its current image (retried in $retryMinutes min)",
                node.id,
            )
            return false
        }
        pullFailed.remove(node.id)
        // A second check right before the start, because the pull can take minutes. Planned
        // maintenance can start in that time.
        if (maintenanceUntil() != null) return false
        ctx.log.change(DOMAIN, "updating the cluster member to $what", node.id)
        updatingNode = node.id
        try {
            movePrimariesOff(member.id, pass.members, "maintenance", prefer = Mesh.HOMELAB)
                .forEach { ctx.log.info(DOMAIN, it, node.id) }
            syncNode(pass, nodeMember, MemberSetup(etcdPeers, ALL_SERVICES, image.ref, rolling = true))
        } finally {
            updatingNode = null
        }
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (CLUSTER_IMAGE_FACT to image.ref)) }
        ctx.log.change(DOMAIN, "cluster member updated to $what", node.id)
        lastView = null
        return true
    }

    /** The build times (`Created`) of the images that the member in [base] runs. */
    private suspend fun runningImages(ssh: SshSession, base: String): List<String> {
        val script = listOf(
            "cd ${shQuote(base)} && docker compose ps -q",
            "xargs -r docker inspect -f '{{.Image}}'",
            "sort -u",
            IMAGE_CREATED,
            "sort -u",
        ).joinToString(" | ")
        return ssh.runScriptOrThrow("cluster-image", script, IMAGE_READ_TIMEOUT_SECONDS)
            .lines()
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
    }

    /** Pulls [image] and returns the build time of the image that the node then has, or an empty text. */
    private suspend fun pulledImage(ssh: SshSession, image: MemberImage): String {
        val ref = shQuote(image.ref)
        val script = listOf(
            "docker pull -q $ref >/dev/null 2>&1 || true",
            "echo $ref | $IMAGE_CREATED 2>/dev/null || true",
        ).joinToString("; ")
        return ssh.runScriptOrThrow("cluster-image", script, IMAGE_PULL_TIMEOUT_SECONDS).trim().trim('"')
    }

    // ------------------------------------------------------------------ history

    fun history(): List<PrimaryChangeDto> = Files.readTextOrNull(historyFile)
        ?.let { runCatching { historyJson.decodeFromString<List<PrimaryChangeDto>>(it) }.getOrNull() }
        .orEmpty()

    /** Adds a change of primary to the history. The data directory keeps the history, newest first. */
    private fun recordPrimary(database: List<PatroniClient.Member>) {
        val leader = database.leader()?.name ?: return
        val past = history()
        val last = past.firstOrNull()?.to
        if (leader == last) return
        val cause = pendingCause ?: if (last == null) "initial" else "failover"
        pendingCause = null
        val change = PrimaryChangeDto(at = nowMillis(), from = last, to = leader, cause = cause)
        Files.writeAtomic(historyFile, historyJson.encodeToString((listOf(change) + past).take(HISTORY)))
    }

    // ------------------------------------------------------------------ departed nodes

    /**
     * Removes what a node that HomeGAP no longer knows left in the Pangolin of the cluster. The API
     * of [ready] removes the homelab site and the exit site of the node. The exit node that its Gerbil
     * registered goes straight from the database, through the node of [ready], because the API of
     * Pangolin cannot remove one. [known] is each configured node and each node that still waits for
     * its decommission.
     *
     * HomeGAP touches only what it creates: a site whose niceId and name both follow its patterns, and
     * a Gerbil exit node that has a region (`app.region` of Pangolin, the node id). HomeGAP checks the
     * database after it removes a site, and at least every 6 hours.
     */
    suspend fun pruneDeparted(ctx: ReconcileContext, ready: ReadyNode, known: Set<String>) {
        val org = ctx.config.pangolin.orgId
        val siteName = ctx.config.pangolin.siteName
        var removed = false
        for ((siteId, niceId, name) in ready.api.listSites(org)) {
            val departed = Departed.siteOwner(niceId, name, siteName) ?: continue
            if (departed in known) continue
            ready.api.deleteSite(siteId)
            ctx.log.change(DOMAIN, "removed site '$niceId' left by departed node $departed")
            removed = true
        }
        if (!removed && nowMillis() - exitPrunedAt < EXIT_PRUNE_EVERY_MS) return
        val sql = Departed.exitNodeSql(known)
        val base = memberDir(ctx)
        val script = $$"""
            |B=$${shQuote(base)}
            |(umask 077; printf 'PGPASSWORD=%s\n' $${shQuote(secrets().pangolin)} > "$B/pg.env")
            |trap 'rm -f "$B/pg.env"' EXIT
            |docker run --rm --network $${ClusterTemplates.NETWORK} --env-file "$B/pg.env" --entrypoint psql $${shQuote(ctx.config.cluster!!.image)} \
            |  -h $${ClusterTemplates.PROXY_HOST} -p $${ClusterTemplates.PROXY_POSTGRES_PORT} -U $${ClusterTemplates.PANGOLIN_USER} -At -F ' ' -c $${shQuote(sql)} $${ClusterTemplates.PANGOLIN_DB}
        """.trimMargin()
        val output = ctx.ssh.session(ready.node).runScriptOrThrow("exit-nodes", script, EXIT_PRUNE_TIMEOUT_SECONDS)
        exitPrunedAt = nowMillis()
        Departed.parseDeleted(output).forEach { (id, region) ->
            ctx.log.change(DOMAIN, "removed exit node $id left by departed node $region")
        }
    }

    /** Pure helpers for [pruneDeparted]. */
    object Departed {
        private val ID = Regex("^[a-z0-9][a-z0-9-]*$")

        /** The node that owns a site that HomeGAP made, or null for each other site. */
        fun siteOwner(niceId: String, name: String?, siteName: String): String? {
            val homelabSiteOwner = niceId.removePrefix("$siteName-")
                .takeIf { it != niceId && name == "$siteName via $it" }
            if (homelabSiteOwner != null) return homelabSiteOwner
            return niceId.removePrefix("exit-")
                .takeIf { it != niceId && name == ExitNodes.siteName(it) }
        }

        /** Deletes each Gerbil exit node whose region isn't one of [known]. Prints `id region` for each. */
        fun exitNodeSql(known: Set<String>): String {
            val keep = known.filter { ID.matches(it) }.joinToString(",") { "'$it'" }.ifEmpty { "''" }
            return "DELETE FROM \"exitNodes\" WHERE type = 'gerbil' AND region IS NOT NULL AND region <> '' " +
                "AND region NOT IN ($keep) RETURNING \"exitNodeId\", region"
        }

        fun parseDeleted(out: String): List<Pair<Int, String>> = out.lines().mapNotNull { line ->
            val fields = line.trim().split(' ')
            if (fields.size == 2) fields[0].toIntOrNull()?.let { it to fields[1] } else null
        }
    }

    // ------------------------------------------------------------------ secrets and files

    /**
     * The passwords of the cluster. HomeGAP creates them once, before the database exists, and then
     * only reads them. The database keeps the passwords from its creation, so a new set locks each
     * member out. If the file is not there or not readable while the cluster is in use, that is an
     * error. It is never a reason to create new passwords.
     */
    fun secrets(): ClusterSecrets {
        loadedSecrets?.let { return it }
        if (Files.exists(secretsFile)) {
            val read = runCatching { json.decodeFromString<ClusterSecrets>(Files.readText(secretsFile)) }.getOrElse {
                throw IllegalStateException(
                    "The cluster secrets file $secretsFile cannot be read (${it.message}). " +
                        "HomeGAP does not replace it: the cluster database keeps the passwords in that file.",
                )
            }
            return read.also { loadedSecrets = it }
        }
        check(!Files.exists(initializedFile) && !inUse()) {
            "The cluster secrets file $secretsFile is missing, but the cluster is in use. " +
                "HomeGAP does not create new passwords: the cluster database keeps the old ones. " +
                "Restore the file from a backup of the data directory."
        }
        val created = ClusterSecrets(
            postgres = Crypto.token(SECRET_BYTES),
            replication = Crypto.token(SECRET_BYTES),
            valkey = Crypto.token(SECRET_BYTES),
            rest = Crypto.token(SECRET_BYTES),
            pangolin = Crypto.token(SECRET_BYTES),
            rewind = Crypto.token(SECRET_BYTES),
            etcd = Crypto.token(SECRET_BYTES),
            sentinel = Crypto.token(SECRET_BYTES),
        )
        Files.mkdirs(dir)
        Files.chmodPath(dir, PRIVATE_DIR_MODE)
        Files.writeAtomic(secretsFile, json.encodeToString(created), PRIVATE_FILE_MODE)
        return created.also { loadedSecrets = it }
    }

    /**
     * Writes the files of the homelab member. The settings of etcd only matter on its first start, so
     * HomeGAP writes them once.
     */
    private fun writeHomelab(pass: Pass) {
        val ctx = pass.ctx
        val homelab = pass.homelab
        val secrets = pass.secrets
        Files.mkdirs(configDir)
        Files.chmodPath(configDir, PRIVATE_DIR_MODE)
        write("$configDir/mesh.env", ClusterTemplates.meshEnv(homelab))
        if (Files.readTextOrNull("$configDir/etcd.env").isNullOrBlank()) {
            write("$configDir/etcd.env", ClusterTemplates.etcdEnv(homelab, listOf(homelab), "new"))
            ctx.log.change(DOMAIN, "started the cluster vote (first etcd member)", Mesh.HOMELAB)
        }
        if (voteOnly) {
            // Only the vote: etcd above, and a Sentinel after a node has a Valkey for it to watch.
            // HomeGAP writes no config for the database or Valkey, so those containers stay idle.
            data(pass.members).firstOrNull()?.let { first ->
                write("$configDir/sentinel.conf", ClusterTemplates.sentinel(homelab, first.meshIp, secrets))
            }
            return
        }
        // Before patroni.yml: the db container starts when that file is there, and needs this one.
        write("$configDir/roles.sql", ClusterTemplates.rolesSql(secrets))
        // The db container of the homelab sees a change of patroni.yml itself. Patroni then reads it again.
        if (write("$configDir/patroni.yml", ClusterTemplates.patroni(homelab, pass.cluster.mesh, secrets))) {
            ctx.log.change(DOMAIN, "updated Patroni settings (applied by reload within seconds)", Mesh.HOMELAB)
        }
        val valkeyEnv = ClusterTemplates.valkeyEnv(
            member = homelab,
            secrets = secrets,
            priority = PREFERRED_PRIORITY,
            initialMaster = homelab.meshIp,
            sentinels = pass.members.map { it.meshIp },
        )
        write("$configDir/valkey.env", valkeyEnv)
        write("$configDir/sentinel.conf", ClusterTemplates.sentinel(homelab, homelab.meshIp, secrets))
    }

    /** Writes [content] to [path] if it is different. Returns whether the file changed. */
    private fun write(path: String, content: String): Boolean {
        if (Files.readTextOrNull(path) == content) return false
        Files.writeAtomic(path, content, PRIVATE_FILE_MODE)
        return true
    }

    // ------------------------------------------------------------------ nodes

    /**
     * The initial cluster of etcd for a node that joins: each current member by name. The new member
     * has no name yet.
     */
    private fun etcdInitial(etcdMembers: List<EtcdClient.Member>, known: List<Member>): List<Member> =
        etcdMembers.mapNotNull { etcdMember -> known.firstOrNull { etcdMember.belongsTo(it) } }

    private fun EtcdClient.Member.belongsTo(member: Member): Boolean =
        name == member.id || ClusterTemplates.peerUrl(member) in peerUrls

    /** Runs the member script on the node and logs each change that the script reports. */
    private suspend fun syncNode(pass: Pass, nodeMember: NodeMember, setup: MemberSetup) {
        val (node, member) = nodeMember
        val script = memberScript(pass, member, setup)
        val timeout = if (setup.rolling) ROLLING_TIMEOUT_SECONDS else SYNC_TIMEOUT_SECONDS
        val output = pass.ctx.ssh.session(node).runScriptOrThrow("cluster", "CHANGED=no\n$script", timeout)
        output.lines()
            .filter { it.startsWith(CHANGED) }
            .forEach { pass.ctx.log.change(DOMAIN, it.removePrefix(CHANGED), node.id) }
    }

    /** The script that writes the config files of [member] and starts or restarts its services. */
    private fun memberScript(pass: Pass, member: Member, setup: MemberSetup): String {
        val members = pass.members
        val secrets = pass.secrets
        // Where Valkey starts when no Sentinel knows a primary yet: the homelab, or the first node
        // when the homelab only votes.
        val initialMaster = data(members).first().meshIp
        val patroniYml = ClusterTemplates.patroni(member, pass.cluster.mesh, secrets)
        val valkeyEnv = ClusterTemplates.valkeyEnv(
            member = member,
            secrets = secrets,
            priority = NODE_PRIORITY,
            initialMaster = initialMaster,
            sentinels = members.map { it.meshIp },
        )
        val sentinelConf = ClusterTemplates.sentinel(member, initialMaster, secrets)
        val haproxyCfg = ClusterTemplates.haproxy(data(members), secrets, member.id)
        val probeTargets = MeshProbe.targets(member.id, members)
        val compose = ClusterTemplates.nodeCompose(
            setup.image,
            mapOf(
                // The Sentinel list and password only matter at start. A change must not restart Valkey.
                "valkey" to hash(valkeyEnv.lines().filterNot { it.startsWith("HG_SENTINEL") }.joinToString("\n")),
                "sentinel" to hash(sentinelConf),
                MeshProbe.SERVICE to hash(MeshProbe.SCRIPT + probeTargets),
            ),
        )
        val base = memberDir(pass.ctx)
        val config = "$base/config"
        val etcdEnv = ClusterTemplates.etcdEnv(member, setup.etcdPeers, "existing")
        val wanted = setup.services.joinToString(" ")
        return buildString {
            // Docker comes with the provisioning of the node.
            appendLine("type -P docker >/dev/null 2>&1 || { echo WAITING-FOR-DOCKER; exit 0; }")
            appendLine("set -e")
            appendLine("B=${shQuote(base)}")
            appendLine($$"install -d -m 0755 \"$B\" \"$B/etcd\" \"$B/pgdata\" \"$B/valkey\" \"$B/sentinel\"")
            appendLine($$"install -d -m 0700 \"$B/config\"")
            appendLine(fileStep("$config/mesh.env", ClusterTemplates.meshEnv(member)))
            appendLine(fileStep("$config/etcd.env", etcdEnv, writeOnce = true))
            // Patroni reads its settings again. A change is never a reason to restart the database.
            appendLine("PATRONI=no")
            appendLine(fileStep("$config/roles.sql", ClusterTemplates.rolesSql(secrets)).replace(FILE_CHANGED, ":"))
            appendLine(fileStep("$config/patroni.yml", patroniYml).replace(FILE_CHANGED, "$FILE_CHANGED; PATRONI=yes"))
            appendLine(fileStep("$config/valkey.env", valkeyEnv))
            appendLine(fileStep("$config/sentinel.conf", sentinelConf))
            // HAProxy reloads its config in place, with no refused connections. It never restarts for it.
            appendLine("HAPROXY=no")
            appendLine(fileStep("$config/haproxy.cfg", haproxyCfg).replace(FILE_CHANGED, "HAPROXY=yes"))
            appendLine(fileStep("$config/probe.py", MeshProbe.SCRIPT))
            appendLine($$"rm -f \"$B/config/probe.sh\"")
            appendLine(fileStep("$config/probe.targets", probeTargets))
            appendLine(fileStep("$base/compose.yml", compose, mode = "0644"))
            appendLine(SshSession.ownInstallDir(pass.ctx.config.pangolin.installDir))
            appendLine($$"cd \"$B\"")
            appendLine("RUNNING=$(docker compose ps --services --status running 2>/dev/null | sort | tr '\\n' ' ')")
            appendLine($$"WANT=$(printf '%s\\n' $$wanted | sort | tr '\\n' ' ')")
            if (setup.rolling) {
                // One service at a time. Each service serves again before the next one restarts.
                appendLine(ROLL_FUNCTION)
                for ((service, check) in ROLL_ORDER) appendLine("roll $service $check")
                val rolled = ROLL_ORDER.joinToString(" ") { it.first }
                appendLine("echo \"CHANGED cluster member restarted one service at a time: $rolled\"")
            } else {
                appendLine(startServicesStep(wanted))
            }
            appendLine(reloadProxyStep())
            appendLine(reloadPatroniStep())
        }
    }

    /** Starts the wanted services if a file changed, or if different services run. */
    private fun startServicesStep(wanted: String): String {
        val up = "docker compose up -d --remove-orphans $wanted"
        return listOf(
            $$"if [ \"$CHANGED\" = yes ] || [ \"$RUNNING\" != \"$WANT\" ]",
            "then $up >/dev/null 2>&1 || $up",
            "echo \"CHANGED cluster member running: $wanted\"",
            "fi",
        ).joinToString("; ")
    }

    /** The reload has a time limit, so the container cannot stop the pass. A failure is the error of the node. */
    private fun reloadProxyStep(): String = listOf(
        $$"if [ \"$HAPROXY\" = yes ] && $${runsTest("haproxy")}",
        "then timeout 30 docker compose exec -T haproxy ${ClusterTemplates.HAPROXY_RELOAD} </dev/null >/dev/null",
        "echo 'CHANGED reloaded the cluster proxy'",
        "fi",
    ).joinToString("; ")

    /**
     * The image of the member copies patroni.yml in at its start (and, in newer images, after a
     * change). This step copies it now, and Patroni reads it again.
     */
    private fun reloadPatroniStep(): String {
        val reload = "docker compose exec -T db sh -c ${shQuote(ClusterTemplates.PATRONI_RELOAD)} </dev/null"
        return listOf(
            $$"if [ \"$PATRONI\" = yes ] && $${runsTest("db")}",
            "then $reload && echo 'CHANGED reloaded Patroni settings'",
            "fi",
        ).joinToString("; ")
    }

    /** The shell test that is true while [service] of the member runs. */
    private fun runsTest(service: String): String =
        $$"[ -n \"$(docker compose ps -q --status running $$service 2>/dev/null)\" ]"

    private suspend fun hasDocker(ctx: ReconcileContext, node: NodeConfig): Boolean {
        val script = "type -P docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 && echo $DOCKER_OK"
        return ctx.ssh.session(node).runScript(script, DOCKER_CHECK_TIMEOUT_SECONDS).stdout.contains(DOCKER_OK)
    }

    /**
     * The shell step that writes [content] to [path] and sets `CHANGED=yes` if the file changed. With
     * [writeOnce], the step keeps a file that is there and not empty.
     */
    private fun fileStep(path: String, content: String, mode: String = "0600", writeOnce: Boolean = false): String {
        val encoded = Base64.encode(content.encodeToByteArray())
        val file = shQuote(path)
        val writeNew = "(umask 077; printf '%s' '$encoded' | base64 -d > $file.new)"
        if (writeOnce) {
            return "[ -s $file ] || { $writeNew && chmod $mode $file.new && mv -f $file.new $file && $FILE_CHANGED; }"
        }
        return listOf(
            writeNew,
            "if cmp -s $file.new $file",
            "then rm -f $file.new",
            "else chmod $mode $file.new",
            "mv -f $file.new $file",
            FILE_CHANGED,
            "fi",
        ).joinToString("; ")
    }

    /**
     * Removes the etcd member of [nodeId] before the teardown of the node. etcd needs a majority
     * to remove a member, and with two members the stop of one leaves no majority.
     */
    suspend fun dropMember(ctx: ReconcileContext, nodeId: String) {
        val cluster = ctx.config.cluster ?: return
        val etcd = EtcdClient(
            http = http,
            base = etcdUrl(Mesh.homelabAddress(cluster.mesh)),
            user = ClusterTemplates.ETCD_USER,
            password = secrets().etcd,
        )
        val member = etcd.members().firstOrNull { it.name == nodeId } ?: return
        etcd.remove(member.id)
        ctx.log.change(DOMAIN, "removed $nodeId from the cluster vote")
    }

    /**
     * Enables the authentication of etcd when each member has the password. Patroni signs in
     * when etcd asks for it, but a Patroni without the password loses etcd and demotes its
     * database. So each node must take its config in two passes in a row first: the second pass
     * shows that Patroni had the time to read the config of the first.
     */
    private suspend fun ensureEtcdAuth(ctx: ReconcileContext, etcd: EtcdClient, allSynced: Boolean) {
        if (etcd.authEnabled()) {
            credentialPasses = 0
            return
        }
        if (!allSynced) {
            credentialPasses = 0
            if (!authWaitLogged) {
                ctx.log.info(DOMAIN, "etcd gets its authentication once each node has the password")
            }
            authWaitLogged = true
            return
        }
        if (++credentialPasses < AUTH_PASSES) return
        etcd.enableAuth()
        ctx.log.change(DOMAIN, "etcd now asks for a password")
    }

    /** Removes each etcd member whose node left the cluster (never the homelab). */
    private suspend fun removeDeparted(
        ctx: ReconcileContext,
        etcd: EtcdClient,
        etcdMembers: List<EtcdClient.Member>,
        nodes: List<Member>,
    ) {
        for (etcdMember in etcdMembers) {
            if (etcdMember.name == Mesh.HOMELAB) continue
            val wanted = nodes.any { etcdMember.belongsTo(it) }
            if (wanted) continue
            val label = etcdMember.name.ifEmpty { etcdMember.peerUrls.joinToString() }
            runCatchingCancellable { etcd.remove(etcdMember.id) }
                .onSuccess { ctx.log.change(DOMAIN, "removed $label from the cluster vote") }
                .onFailure { ctx.log.warn(DOMAIN, "could not remove etcd member ${etcdMember.name}: ${it.message}") }
        }
    }

    /**
     * Tells a Sentinel that still knows a departed member to forget it. A Sentinel never forgets a
     * replica or a Sentinel by itself. A member whose mesh address has no WireGuard peer fails at once
     * ("No route to host"), and the Sentinel of Valkey then uses a full CPU. `SENTINEL RESET` makes the
     * Sentinel forget them all, and it finds the live ones again within seconds. HomeGAP resets one
     * Sentinel at a time, at least [DepartedSentinels.SPACING_MS] apart and never during a failover,
     * so the other Sentinels always keep the quorum.
     */
    private suspend fun forgetDeparted(ctx: ReconcileContext, valkey: ValkeyCli, members: List<Member>) {
        if (nowMillis() - sentinelResetAt < DepartedSentinels.SPACING_MS) return
        val views = members.associateWith { valkey.sentinelView(it.meshIp, ClusterTemplates.SCOPE) }
        if (views.values.any { it != null && "failover_in_progress" in it.flags }) return
        val (member, departed) = DepartedSentinels.stale(members, views.mapKeys { it.key.id }) ?: return
        if (valkey.sentinelReset(member.meshIp, ClusterTemplates.SCOPE)) {
            sentinelResetAt = nowMillis()
            ctx.log.change(
                DOMAIN,
                "Sentinel forgot departed ${departed.sorted().joinToString(", ")}",
                member.id.takeIf { it != Mesh.HOMELAB },
            )
        }
    }

    // ------------------------------------------------------------------ switch-back and status

    /** The database as the first member that answers sees it (the homelab first). */
    private suspend fun databaseView(patroni: PatroniClient, members: List<Member>): List<PatroniClient.Member>? {
        for (member in members) {
            val view = runCatchingCancellable { patroni.cluster(restUrl(member)) }.getOrNull()
            if (view != null) return view
        }
        return null
    }

    /** Returns the database primary, then the Valkey primary, to the preferred member when it is time. */
    private suspend fun switchBack(pass: Pass, patroni: PatroniClient, database: List<PatroniClient.Member>?): String? {
        if (database == null) return null
        val primary = pass.cluster.primary
        val now = nowMillis()
        val maintenanceUntil = maintenanceUntil(now)
        if (maintenanceUntil != null) {
            preferredCaughtUpSince = null
            switchBackDue = null
            val minutesLeft = minutesRoundedUp(maintenanceUntil - now)
            return "held off the homelab for maintenance (at most $minutesLeft more min)"
        }
        val hold = primary.switchBackDelay.inWholeMilliseconds
        val decision = SwitchBack.evaluate(database, primary.preferred, preferredCaughtUpSince, now, hold)
        preferredCaughtUpSince = decision.caughtUpSince
        switchBackDue = decision.caughtUpSince?.plus(hold)?.takeIf { decision.switchFrom == null }
        val leader = decision.switchFrom
        if (leader != null) {
            switchDatabaseBack(pass, patroni, leader)
            return decision.note
        }
        // Valkey follows when PostgreSQL is back on the preferred member.
        if (database.leader()?.name == primary.preferred) {
            switchValkeyBack(pass)
        }
        return decision.note
    }

    private suspend fun switchDatabaseBack(pass: Pass, patroni: PatroniClient, leader: String) {
        val preferred = pass.cluster.primary.preferred
        val leaderMember = pass.members.firstOrNull { it.id == leader } ?: return
        runCatchingCancellable {
            notePlannedMove()
            patroni.switchover(restUrl(leaderMember), leader, preferred)
            pendingCause = "switch-back"
            switchBackDue = null
            pass.ctx.log.change(DOMAIN, "switched the database primary back from $leader to $preferred")
            preferredCaughtUpSince = null
        }.onFailure { pass.ctx.log.error(DOMAIN, "switch-back from $leader failed: ${it.message}") }
    }

    private suspend fun switchValkeyBack(pass: Pass) {
        val homelab = pass.homelab
        val valkey = ValkeyCli(pass.secrets.valkey, pass.secrets.sentinel)
        val master = sentinelMaster(valkey, pass.members)
        val homelabLinked = valkey.replication(homelab.meshIp)?.get("master_link_status") == "up"
        if (master != null && master != homelab.meshIp && homelabLinked) {
            notePlannedMove()
            if (valkey.sentinelFailover(homelab.meshIp, ClusterTemplates.SCOPE)) {
                val preferred = pass.cluster.primary.preferred
                pass.ctx.log.change(DOMAIN, "switched the Valkey primary back to $preferred")
            }
        }
    }

    /** The members that hold the database and Valkey: all of them, or the nodes when the homelab only votes. */
    private fun data(members: List<Member>): List<Member> =
        if (voteOnly) members.filter { it.id != Mesh.HOMELAB } else members

    /**
     * The role of the homelab is fixed at the creation of the cluster. A later change leaves a
     * database at home that the nodes no longer follow, or the nodes expect one that never was there.
     */
    private fun keepRole(cluster: ClusterConfig) {
        val recorded = Files.readTextOrNull(roleFile)?.trim().orEmpty()
        if (recorded.isEmpty()) {
            Files.mkdirs(dir)
            Files.writeAtomic(roleFile, cluster.homelabRole + "\n", PRIVATE_FILE_MODE)
            return
        }
        check(recorded == cluster.homelabRole) {
            "cluster.homelab_role is '${cluster.homelabRole}', but this cluster was created with '$recorded'. " +
                "HomeGAP does not change the role of a cluster that runs: set it back to '$recorded'."
        }
    }

    /**
     * Keeps a copy of the database at home when the homelab holds no database. Each day, a node makes
     * a dump, and the data directory of the orchestrator keeps the newest [BACKUPS_KEPT] dumps. After
     * a failure, the next try comes 5 minutes later.
     */
    private suspend fun backupHome(pass: Pass) {
        val ctx = pass.ctx
        val cluster = pass.cluster
        val secrets = pass.secrets
        val now = nowMillis()
        val newest = dumps()
            .mapNotNull { it.removePrefix(BACKUP_PREFIX).removeSuffix(DUMP_SUFFIX).toLongOrNull() }
            .maxOrNull()
            ?: 0L
        if (now - newest < BACKUP_EVERY_MS || now - backupTriedAt < BACKUP_RETRY_MS) return
        val node = pass.nodeMembers
            .map { it.node }
            .firstOrNull { !ctx.nodePaused(it.id) && memberReady(it.id) }
            ?: return
        backupTriedAt = now
        val base = memberDir(ctx)
        val script = $$"""
            |B=$${shQuote(base)}
            |(umask 077; printf 'PGPASSWORD=%s\n' $${shQuote(secrets.pangolin)} > "$B/pg.env")
            |trap 'rm -f "$B/pg.env"' EXIT
            |docker run --rm --network $${ClusterTemplates.NETWORK} --env-file "$B/pg.env" --entrypoint pg_dump $${shQuote(cluster.image)} \
            |  -h $${ClusterTemplates.PROXY_HOST} -p $${ClusterTemplates.PROXY_POSTGRES_PORT} -U $${ClusterTemplates.PANGOLIN_USER} -Fc $${ClusterTemplates.PANGOLIN_DB} | base64 -w0
        """.trimMargin()
        runCatchingCancellable {
            val output = ctx.ssh.session(node).runScriptOrThrow("backup", script, BACKUP_TIMEOUT_SECONDS)
            val bytes = Base64.decode(output.trim())
            check(bytes.size > MIN_DUMP_BYTES) { "the dump is empty" }
            Files.mkdirs(backupDir)
            Files.chmodPath(backupDir, PRIVATE_DIR_MODE)
            Files.writeAtomicBytes("$backupDir/$BACKUP_PREFIX$now$DUMP_SUFFIX", bytes, PRIVATE_FILE_MODE)
            for (old in dumps().sortedDescending().drop(BACKUPS_KEPT)) {
                Files.delete("$backupDir/$old")
            }
            val size = "${bytes.size / 1024} KiB"
            ctx.log.change(DOMAIN, "saved a dump of the cluster database at home ($size, taken on ${node.id})")
            report(ctx, "backup", null)
        }.onFailure {
            report(ctx, "backup", "the daily dump of the cluster database failed: ${it.message?.take(300)}")
        }
    }

    private fun dumps(): List<String> =
        Files.list(backupDir).filter { it.startsWith(BACKUP_PREFIX) && it.endsWith(DUMP_SUFFIX) }

    private suspend fun sentinelMaster(valkey: ValkeyCli, members: List<Member>): String? {
        // The Sentinel of the homelab first (it is in members too), then the others.
        for (member in members.sortedByDescending { it.id == Mesh.HOMELAB }) {
            valkey.sentinelMaster(member.meshIp, ClusterTemplates.SCOPE)?.let { return it }
        }
        return null
    }

    private suspend fun status(pass: Pass, snapshot: Snapshot, switchBack: String?): ClusterStatusDto {
        val members = pass.members
        val valkey = ValkeyCli(pass.secrets.valkey, pass.secrets.sentinel)
        val valkeyMaster = sentinelMaster(valkey, members)
        val probes = probeMembers(valkey, members)
        val replayLag = replayLag(pass, snapshot.database)
        val quorum = quorum(snapshot.etcdMembers, members, probes)
        return ClusterStatusDto(
            primary = snapshot.database?.leader()?.name,
            valkeyPrimary = members.firstOrNull { it.meshIp == valkeyMaster }?.id ?: valkeyMaster,
            preferred = pass.cluster.primary.preferred.takeUnless { voteOnly },
            switchBack = switchBack,
            switchBackAt = switchBackDue,
            quorum = quorum,
            history = history(),
            members = members.map { memberDto(it, snapshot, probes[it.id], replayLag[it.id]) },
        )
    }

    /** The view of each member itself: the health of its etcd and the role of its Valkey. */
    private suspend fun probeMembers(valkey: ValkeyCli, members: List<Member>): Map<String, MemberProbe> =
        coroutineScope {
            members
                .map { member ->
                    async {
                        val etcdHealthy = EtcdClient(http, etcdUrl(member.meshIp)).healthy()
                        member.id to MemberProbe(etcdHealthy, valkey.replication(member.meshIp)?.get("role"))
                    }
                }
                .awaitAll()
                .toMap()
        }

    /** For each replica that is behind: the time since it replayed a transaction (ms). */
    private suspend fun replayLag(pass: Pass, database: List<PatroniClient.Member>?): Map<String, Long?> {
        val patroni = PatroniClient(http, ClusterTemplates.REST_USER, pass.secrets.rest)
        return coroutineScope {
            pass.members
                .filter { member -> database?.firstOrNull { it.name == member.id }?.isBehind() == true }
                .map { member -> async { member.id to patroni.replayedAt(restUrl(member))?.let { nowMillis() - it } } }
                .awaitAll()
                .toMap()
        }
    }

    private fun PatroniClient.Member.isBehind(): Boolean = role != LEADER && (lagBytes ?: 0L) > 0

    /** True if more than half of the voters have a healthy etcd. Null if etcd gives no answer. */
    private fun quorum(
        etcdMembers: List<EtcdClient.Member>?,
        members: List<Member>,
        probes: Map<String, MemberProbe>,
    ): Boolean? {
        val voters = etcdMembers?.filter { !it.learner } ?: return null
        val healthy = voters.count { voter ->
            members.firstOrNull { voter.belongsTo(it) }?.let { probes[it.id]?.etcdHealthy } == true
        }
        return healthy * 2 > voters.size
    }

    private fun memberDto(
        member: Member,
        snapshot: Snapshot,
        probe: MemberProbe?,
        replayLagMs: Long?,
    ): ClusterMemberDto {
        val etcdMember = snapshot.etcdMembers?.firstOrNull { it.belongsTo(member) }
        val databaseMember = snapshot.database?.firstOrNull { it.name == member.id }
        return ClusterMemberDto(
            id = member.id,
            address = member.meshIp,
            vote = when {
                snapshot.etcdMembers == null -> "unknown"
                etcdMember == null -> "not joined"
                etcdMember.learner -> "joining"
                else -> "voter"
            },
            role = databaseMember?.role,
            state = databaseMember?.state,
            lagBytes = databaseMember?.lagBytes,
            lagMs = replayLagMs?.coerceAtLeast(0),
            timeline = databaseMember?.timeline,
            valkeyRole = probe?.valkeyRole,
            etcdHealthy = probe?.etcdHealthy,
            voteOnly = voteOnly && member.id == Mesh.HOMELAB,
        )
    }

    /**
     * Logs an error the first time and after each change. Logs the recovery once. An error that
     * stays counts in each pass.
     */
    private fun report(ctx: ReconcileContext, id: String, error: String?) {
        val previous = lastErrors[id]
        val target = id.takeIf { it != "etcd" && it != "backup" }
        when {
            error == null -> if (previous != null) {
                lastErrors.remove(id)
                ctx.log.info(DOMAIN, "recovered", target)
            }
            error != previous -> {
                lastErrors[id] = error
                ctx.log.error(DOMAIN, error, target)
            }
            else -> ctx.log.stillFailing()
        }
    }

    /** The directory of the cluster member on a node. */
    private fun memberDir(ctx: ReconcileContext): String = "${ctx.config.pangolin.installDir}/cluster"

    /** The Patroni of a member. Plain HTTP on purpose: the request stays on the mesh, which WireGuard encrypts. */
    @Suppress("HttpUrlsUsage")
    private fun restUrl(member: Member) = "http://${member.meshIp}:${ClusterTemplates.PATRONI_PORT}"

    /** The etcd of a member. Plain HTTP on purpose: the request stays on the mesh, which WireGuard encrypts. */
    @Suppress("HttpUrlsUsage")
    private fun etcdUrl(meshIp: String) = "http://$meshIp:${ClusterTemplates.ETCD_CLIENT_PORT}"

    private fun hash(text: String): String = Crypto.fnv64(text)

    private companion object {
        const val DOMAIN = "cluster"

        /** The node fact that holds the cluster image that HomeGAP last gave to the member of the node. */
        const val CLUSTER_IMAGE_FACT = "clusterImage"

        /** The time that a node waits before it tries again to pull an image. */
        const val PULL_RETRY_MS = 30 * 60_000L

        const val BACKUP_PREFIX = "homegap-"
        const val DUMP_SUFFIX = ".dump"
        const val BACKUPS_KEPT = 7
        const val BACKUP_EVERY_MS = 24 * 3_600_000L

        /** Short, so that the first dump of a new cluster does not wait long for a proxy that was not ready. */
        const val BACKUP_RETRY_MS = 5 * 60_000L
        const val BACKUP_TIMEOUT_SECONDS = 600
        const val MIN_DUMP_BYTES = 100

        /** Sentinel promotes the lowest priority: the homelab first. */
        const val PREFERRED_PRIORITY = 1
        const val NODE_PRIORITY = 100
        const val HISTORY = 20

        /** The interval between two checks of the database for exit nodes that departed nodes left. */
        const val EXIT_PRUNE_EVERY_MS = 6 * 3_600_000L
        const val EXIT_PRUNE_TIMEOUT_SECONDS = 120

        const val PLANNED_MOVES_KEPT_MS = 3_600_000L

        /** The time that a primary has to move to a different member. */
        const val MOVE_TIMEOUT_MS = 60_000L

        /** The time that maintenance waits for a node that restarts its own member. */
        const val NODE_UPDATE_WAIT_MS = 10 * 60_000L

        /** The time that maintenance waits for the first view of the cluster after the orchestrator starts. */
        const val FIRST_VIEW_WAIT_MS = 90_000L

        /** The number of passes in a row with the password on each node before etcd asks for it. */
        const val AUTH_PASSES = 2

        const val SECRET_BYTES = 32
        val PRIVATE_DIR_MODE = "700".toInt(8)
        val PRIVATE_FILE_MODE = "600".toInt(8)
        val MAINTENANCE_FILE_MODE = "644".toInt(8)

        const val SYNC_TIMEOUT_SECONDS = 900
        const val ROLLING_TIMEOUT_SECONDS = 1800
        const val IMAGE_READ_TIMEOUT_SECONDS = 60
        const val IMAGE_PULL_TIMEOUT_SECONDS = 1800
        const val DOCKER_CHECK_TIMEOUT_SECONDS = 60

        // Markers in the member script and in its output.
        const val CHANGED = "CHANGED "
        const val FILE_CHANGED = "CHANGED=yes"
        const val DOCKER_OK = "DOCKER-OK"

        /** Reads the build time (`Created`) of each image id on its input. */
        const val IMAGE_CREATED = "xargs -r docker image inspect -f '{{json .Created}}'"

        val ETCD_ONLY = listOf("etcd")
        val ALL_SERVICES = listOf("etcd", "db", "valkey", "sentinel", "haproxy", MeshProbe.SERVICE)

        /**
         * The restart order for an image update, with the check of each service: an hg-member role,
         * `proxy`, or `-`.
         */
        val ROLL_ORDER = listOf(
            "etcd" to "etcd",
            "sentinel" to "sentinel",
            "valkey" to "valkey",
            "db" to "patroni",
            "haproxy" to "proxy",
            MeshProbe.SERVICE to "-",
        )

        /**
         * Node scripts reach bash on stdin, so each `docker compose exec` in them reads from
         * /dev/null. Without that, the command swallows the rest of the script. The image is new, so
         * the function creates each service again, also if its compose entry reads the same.
         */
        val ROLL_FUNCTION = $$"""
            |roll() {
            |  local svc=$1 check=$2 n=0
            |  docker compose up -d --no-deps --force-recreate "$svc" >/dev/null 2>&1 || docker compose up -d --no-deps --force-recreate "$svc"
            |  case "$check" in
            |    -) ;;
            |    proxy)
            |      until curl -fsS -m 2 http://127.0.0.1:$${ClusterProxy.STATS_PORT}/stats >/dev/null 2>&1; do
            |        n=$((n + 1)); [ $n -lt 30 ] || { echo "$svc did not come back within a minute" >&2; return 1; }; sleep 2
            |      done ;;
            |    *)
            |      until docker compose exec -T "$svc" hg-member ready "$check" </dev/null >/dev/null 2>&1; do
            |        n=$((n + 1)); [ $n -lt 150 ] || { echo "$svc was not serving after 5 minutes" >&2; return 1; }; sleep 2
            |      done ;;
            |  esac
            |}
        """.trimMargin()
    }
}

private const val LEADER = "leader"
private const val STREAMING = "streaming"

private fun List<PatroniClient.Member>.leader(): PatroniClient.Member? = firstOrNull { it.role == LEADER }

/** Whole minutes, with a part of a minute counted as one. */
private fun minutesRoundedUp(milliseconds: Long): Long = (milliseconds + 59_999) / 60_000

/**
 * The cluster image that the member of the homelab runs: a reference that other hosts can pull, and
 * its build time (`Created`). The build time identifies the image on each host, because image ids
 * differ between the stores of Docker.
 */
data class MemberImage(val ref: String, val created: String)

/** Finds the Sentinels that still know members that left the cluster (see `forgetDeparted`). */
object DepartedSentinels {
    /** The time between two resets, so that a Sentinel finds the others again before the next reset. */
    const val SPACING_MS = 30_000L

    /** The first member whose Sentinel knows addresses outside [members], with those addresses. */
    fun stale(members: List<Member>, views: Map<String, ValkeyCli.SentinelView?>): Pair<Member, Set<String>>? {
        val addresses = members.map { it.meshIp }.toSet()
        for (member in members) {
            val departed = views[member.id]?.known?.minus(addresses).orEmpty()
            if (departed.isNotEmpty()) return member to departed
        }
        return null
    }
}

/** Decides when to give the database primary back to the preferred member. */
object SwitchBack {
    /**
     * [caughtUpSince] is the time when the preferred member first was a caught-up replica (null if
     * it is not one). [switchFrom] names the leader to switch away from after the hold.
     */
    data class Decision(val caughtUpSince: Long?, val switchFrom: String?, val note: String?)

    fun evaluate(
        members: List<PatroniClient.Member>,
        preferred: String,
        caughtUpSince: Long?,
        now: Long,
        holdMs: Long,
    ): Decision {
        val nothing = Decision(caughtUpSince = null, switchFrom = null, note = null)
        val leader = members.leader() ?: return nothing
        if (leader.name == preferred) return nothing
        val preferredMember = members.firstOrNull { it.name == preferred }
        val caughtUp = preferredMember != null &&
            preferredMember.state == STREAMING &&
            preferredMember.lagBytes == 0L
        if (!caughtUp) {
            val note = "$preferred is not a caught-up replica yet; the primary stays on ${leader.name}"
            return Decision(caughtUpSince = null, switchFrom = null, note = note)
        }
        val since = caughtUpSince ?: now
        val remaining = holdMs - (now - since)
        if (remaining <= 0) {
            return Decision(since, leader.name, "switching the primary back to $preferred")
        }
        val note = "$preferred caught up; switching back in ${minutesRoundedUp(remaining)} min"
        return Decision(since, switchFrom = null, note = note)
    }
}
