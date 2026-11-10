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
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import homegap.provision.ClusterProxy
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ClusterTemplates.Member
import homegap.provision.Mesh
import homegap.provision.MeshProbe
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

/**
 * Cluster domain: the database layer shared by the homelab and every node.
 *
 * - **Homelab member:** its containers are part of the homelab compose and wait for their config;
 *   HomeGAP writes it into the data directory (shared with them).
 * - **Nodes:** each runs its member as its own compose project over SSH.
 * - **etcd grows one learner at a time:** a node is added as a learner, started, and promoted once
 *   it has caught up, so the vote is never stalled by a member that is not running yet.
 * - **A node's database services start** once it is a voting etcd member and the cluster's
 *   database exists, so a node never bootstraps a second cluster.
 * - **Switch-back:** while the preferred member (the homelab) is a caught-up replica for
 *   `switch_back_after`, the PostgreSQL primary is switched back to it, then Valkey's.
 *
 * Long-lived (one per controller): it keeps the switch-back timer and logs a persistent error once.
 */
class ClusterReconciler(dataDir: String, private val http: HttpClient) {
    private val domain = "cluster"
    private val dir = "$dataDir/cluster"
    private val configDir = "$dir/config"
    private val secretsFile = "$dir/secrets.json"
    /** Written once the database has had a leader; a later outage never sends nodes back to etcd only. */
    private val initializedFile = "$dir/initialized"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lastErrors = mutableMapOf<String, String>()
    private var preferredCaughtUpSince: Long? = null
    /** Members whose database is up (the primary, or a streaming replica), as last seen. */
    @Volatile private var ready: Set<String> = emptySet()
    private val historyFile = "$dir/history.json"
    /** Why the next primary change happens, when HomeGAP asked for it (otherwise a failover). */
    private var pendingCause: String? = null
    private var switchBackDue: Long? = null

    /** When HomeGAP last moved a primary on purpose (epoch ms, the last hour), for the proxy check. */
    @Volatile var plannedMoves: List<Long> = emptyList()
        private set

    private fun notePlannedMove() {
        val now = nowMillis()
        plannedMoves = plannedMoves.filter { it > now - 3_600_000L } + now
    }
    /** The members and database view of the last pass, for a switchover from the dashboard. */
    @Volatile private var lastView: Pair<List<Member>, List<PatroniClient.Member>>? = null

    /** The node's member can serve its Pangolin: read by provisioning jobs between passes. */
    fun memberReady(id: String): Boolean = id in ready

    /**
     * [image] is the cluster image the homelab's member runs (null when it can't be read): nodes
     * are moved to it one per pass, see [updateNodeImage].
     */
    suspend fun reconcile(ctx: ReconcileContext, nodes: List<NodeConfig>, slots: Map<String, Int>, image: MemberImage? = null): ClusterStatusDto? {
        val cluster = ctx.config.cluster ?: return null
        val secrets = secrets()
        val homelab = Member(Mesh.HOMELAB, Mesh.homelabAddress(cluster.mesh))
        // Nodes take part once they have joined the mesh.
        val nodeMembers = nodes
            .filter { !ctx.state.node(it.id).pendingDecommission && ctx.state.node(it.id).meshPublicKey != null }
            .mapNotNull { n -> slots[n.id]?.let { n to Member(n.id, Mesh.nodeAddress(cluster.mesh, it)) } }
        val members = listOf(homelab) + nodeMembers.map { it.second }

        writeHomelab(ctx, cluster, homelab, members, secrets)

        val etcd = EtcdClient(http, "http://127.0.0.1:${ClusterTemplates.ETCD_CLIENT_PORT}")
        val patroni = PatroniClient(http, ClusterTemplates.REST_USER, secrets.rest)
        val etcdMembers = runCatching { etcd.members() }.getOrNull()
        report(ctx, "etcd", if (etcdMembers == null) "the homelab's etcd is not reachable yet" else null)
        val pg = databaseView(patroni, members)
        if (pg != null) ready = pg.filter { (it.role == "leader" && it.state == "running") || it.state == "streaming" }.map { it.name }.toSet()
        // Recorded now, not after the node syncs below (which can take minutes, e.g. while a node
        // pulls an image): planned maintenance needs only this view.
        if (pg != null) lastView = members to pg
        val initialized = Files.exists(initializedFile) ||
            (pg?.any { it.role == "leader" } == true).also { if (it) Files.writeAtomic(initializedFile, "") }

        // A node's member moves to a new image only while the whole cluster is healthy (every
        // member in the vote and serving the database) and no maintenance is under way, and only
        // one node per pass, so at most one member is ever restarting.
        val notServing = members.map { it.id }.filter { it !in ready }
        var imageWait: String? = when {
            maintenanceUntil() != null -> "planned maintenance of the homelab is under way"
            pg == null -> "the database can't be read"
            etcdMembers == null || etcdMembers.any { it.learner } || etcdMembers.size != members.size -> "not every member is in the cluster vote"
            notServing.isNotEmpty() -> "${notServing.joinToString(", ")} ${if (notServing.size == 1) "is" else "are"} not serving the database"
            else -> null
        }
        if (etcdMembers != null) {
            removeDeparted(ctx, etcd, etcdMembers, nodeMembers.map { it.second })
            var joining = etcdMembers.any { it.learner }
            for ((node, member) in nodeMembers) {
                if (ctx.nodePaused(node.id)) continue
                val existing = etcdMembers.firstOrNull { it.name == member.id || ClusterTemplates.peerUrl(member) in it.peerUrls }
                try {
                    when {
                        // Docker comes with the node's own provisioning; until then it can't run a member.
                        existing == null && !joining && !hasDocker(ctx, node) -> Unit
                        existing == null && !joining -> {
                            etcd.addLearner(ClusterTemplates.peerUrl(member))
                            joining = true
                            ctx.log.change(domain, "joining the cluster vote (etcd learner)", node.id)
                            val now = etcd.members()
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(now, members), listOf("etcd"), nodeImage(ctx, node, cluster))
                        }
                        existing == null -> Unit // another node is joining; this one waits its turn
                        existing.learner -> {
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(etcdMembers, members), listOf("etcd"), nodeImage(ctx, node, cluster))
                            val promoted = runCatching { etcd.promote(existing.id) }.isSuccess
                            if (promoted) ctx.log.change(domain, "joined the cluster vote", node.id)
                        }
                        else -> {
                            val services = if (initialized) listOf("etcd", "db", "valkey", "sentinel", "haproxy", MeshProbe.SERVICE) else listOf("etcd")
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(etcdMembers, members), services, nodeImage(ctx, node, cluster))
                            if (image != null && initialized) {
                                if (updateNodeImage(ctx, node, cluster, member, members, secrets, etcdInitial(etcdMembers, members), image, imageWait)) {
                                    imageWait = "another node is being updated"
                                }
                            }
                        }
                    }
                    report(ctx, node.id, null)
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    report(ctx, node.id, e.message ?: e.toString())
                }
            }
        }

        if (pg != null) recordPrimary(pg)
        val switchBack = switchBack(ctx, cluster, patroni, pg, homelab, members, secrets)
        return status(cluster, members, etcdMembers, pg, secrets, switchBack)
    }

    /**
     * Makes [to] the PostgreSQL primary now (a planned switchover: no writes are lost). [to] must be
     * a caught-up replica. Moving it away from the preferred member is undone by the switch-back
     * once the preferred member has been caught up for the hold.
     */
    suspend fun switchover(to: String): String {
        val (members, pg) = lastView ?: throw IllegalStateException("the cluster has not been read yet")
        val leader = pg.firstOrNull { it.role == "leader" } ?: throw IllegalStateException("there is no primary right now")
        require(to != leader.name) { "$to is already the primary" }
        check(to != updatingNode) { "$to is being updated to a new cluster image; try again in a minute" }
        val target = pg.firstOrNull { it.name == to } ?: throw IllegalArgumentException("$to is not a database member")
        check(target.state == "streaming" && target.lagBytes == 0L) { "$to is not a caught-up replica (${target.state})" }
        val leaderMember = members.first { it.id == leader.name }
        notePlannedMove()
        try {
            PatroniClient(http, ClusterTemplates.REST_USER, secrets().rest).switchover(restUrl(leaderMember), leader.name, to)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            // Patroni refuses when the primary moved since the last pass.
            if (e.message?.contains("leader name does not match") == true) {
                throw IllegalStateException("The primary changed a moment ago. Wait for the dashboard to refresh and try again.")
            }
            throw e
        }
        // The view is stale until the next pass: a second request waits for it.
        lastView = null
        pendingCause = "manual"
        preferredCaughtUpSince = null
        return "Switched the database primary from ${leader.name} to $to"
    }

    private val maintenanceFile = "$dir/maintenance"

    /**
     * Until when the primaries are kept off the homelab for planned maintenance (epoch ms), or null.
     * Kept in a file: the orchestrator itself is restarted during an upgrade.
     */
    fun maintenanceUntil(now: Long = nowMillis()): Long? =
        Files.readTextOrNull(maintenanceFile)?.trim()?.toLongOrNull()?.takeIf { it > now }

    /**
     * Prepares the homelab's member to be restarted without an outage: moves the PostgreSQL
     * primary (a planned switchover, no writes lost) and the Valkey primary (a Sentinel failover)
     * to the nodes and waits until both have moved, then holds them there (no switch-back) until
     * [endMaintenance] or for [holdMs] at most, so a script that never finishes cannot keep them
     * away for good. Afterwards the usual switch-back applies, once the homelab has been caught
     * up for the configured delay.
     */
    suspend fun beginMaintenance(cluster: ClusterConfig, holdMs: Long): String {
        val start = nowMillis()
        Files.writeAtomic(maintenanceFile, (start + holdMs).toString(), "644".toInt(8))
        preferredCaughtUpSince = null
        // The homelab's member is about to restart: let a node already restarting its own finish
        // first (no new one starts once the hold is written), so two are never down together.
        check(waitFor(10 * 60_000) { updatingNode == null }) { "node ${updatingNode} is still updating its cluster member after 10 minutes" }
        val homelabId = Mesh.HOMELAB
        // The view comes from the last pass; right after the orchestrator starts there may be none yet.
        var view = lastView
        while (view == null && nowMillis() - start < 90_000) { delay(2_000); view = lastView }
        val (members, _) = view ?: throw IllegalStateException("the cluster has not been read yet; try again in a minute")

        val notes = movePrimariesOff(homelabId, members, "maintenance")
        lastView = null
        return "Maintenance: ${notes.joinToString("; ")}. Switch-back held for up to ${holdMs / 60_000} min."
    }

    /** Ends planned maintenance: the switch-back resumes (after the usual caught-up delay). */
    fun endMaintenance(): String {
        val was = maintenanceUntil() != null
        Files.delete(maintenanceFile)
        preferredCaughtUpSince = null
        return if (was) "Maintenance ended; the primaries return to the homelab once it has been caught up for the switch-back delay" else "No maintenance was in progress"
    }

    /**
     * Moves the PostgreSQL primary (a planned switchover, no writes lost) and the Valkey primary
     * (a Sentinel failover) off member [off], if it holds them, and waits until both have moved.
     * The database goes to [prefer] when that is a caught-up replica, otherwise to the most caught-up
     * one; Sentinel picks Valkey's (the homelab first). Returns what was done.
     */
    private suspend fun movePrimariesOff(off: String, members: List<Member>, cause: String, prefer: String? = null): List<String> {
        fun name(ip: String?) = members.firstOrNull { it.meshIp == ip }?.id ?: ip ?: "no member"
        val notes = mutableListOf<String>()
        val patroni = PatroniClient(http, ClusterTemplates.REST_USER, secrets().rest)
        val pg = databaseView(patroni, members) ?: throw IllegalStateException("no database member answers")
        val leader = pg.firstOrNull { it.role == "leader" }
        if (leader?.name == off) {
            val replicas = pg.filter { it.name != off && it.name != updatingNode && it.state == "streaming" }
            val target = replicas.firstOrNull { it.name == prefer && it.lagBytes == 0L } ?: replicas.minByOrNull { it.lagBytes ?: Long.MAX_VALUE }
                ?: throw IllegalStateException("no caught-up replica can take over the database from $off")
            notePlannedMove()
            patroni.switchover(restUrl(members.first { it.id == off }), off, target.name)
            pendingCause = cause
            val moved = waitFor(60_000) { databaseView(patroni, members)?.firstOrNull { it.role == "leader" }?.name.let { it != null && it != off } }
            check(moved) { "the database primary did not move to ${target.name} within a minute" }
            notes += "database primary moved to ${target.name}"
        } else {
            notes += "database primary already on ${leader?.name ?: "no member"}"
        }
        val valkey = ValkeyCli(secrets().valkey)
        val offIp = members.first { it.id == off }.meshIp
        val master = sentinelMaster(valkey, members)
        if (master == offIp) {
            notePlannedMove()
            check(valkey.sentinelFailover("127.0.0.1", ClusterTemplates.SCOPE)) { "Sentinel refused the Valkey failover" }
            val moved = waitFor(60_000) { sentinelMaster(valkey, members).let { it != null && it != offIp } }
            check(moved) { "the Valkey primary did not move off $off within a minute" }
            notes += "Valkey primary moved to ${name(sentinelMaster(valkey, members))}"
        } else {
            notes += "Valkey primary already on ${name(master)}"
        }
        return notes
    }

    /** The node whose member is being moved to a new image right now, if any: never made a primary. */
    @Volatile private var updatingNode: String? = null

    /** Per node, why its image update last waited (logged when it changes). */
    private val imageWaitNoted = mutableMapOf<String, String>()

    /** Per node, the image whose pull last failed there, and when. */
    private val pullFailed = mutableMapOf<String, Pair<MemberImage, Long>>()

    /** The image a node's member is set to run: the last one rolled out to it, or the configured one. */
    private fun nodeImage(ctx: ReconcileContext, node: NodeConfig, cluster: ClusterConfig): String =
        ctx.state.node(node.id).facts[CLUSTER_IMAGE_FACT] ?: cluster.image

    /**
     * Moves a node's member to the homelab's cluster [image] if it runs another one. The image is
     * pulled first (the member keeps serving meanwhile), the primaries are moved off the node, and
     * the services are restarted one at a time, each waited for before the next, so the node is
     * never more than one restarting service away from serving. Returns whether it started an
     * update (so the caller lets no other node start one this pass).
     */
    private suspend fun updateNodeImage(
        ctx: ReconcileContext,
        node: NodeConfig,
        cluster: ClusterConfig,
        member: Member,
        members: List<Member>,
        secrets: ClusterSecrets,
        initial: List<Member>,
        image: MemberImage,
        wait: String?,
    ): Boolean {
        val ssh = ctx.ssh.session(node)
        val base = "${ctx.config.pangolin.installDir}/cluster"
        val created = "xargs -r docker image inspect -f '{{json .Created}}'"
        val running = ssh.runScriptOrThrow("cluster-image", "cd ${shQuote(base)} && docker compose ps -q | xargs -r docker inspect -f '{{.Image}}' | sort -u | $created | sort -u", 60)
            .lines().map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        if (running.isEmpty() || running.all { it == image.created }) {
            imageWaitNoted.remove(node.id)
            return false
        }
        val what = "${image.ref} (built ${image.created.take(19).replace('T', ' ')} UTC)"
        if (wait != null) {
            // Said once per reason, not on every pass.
            if (imageWaitNoted.put(node.id, wait) != wait) ctx.log.info(domain, "cluster image update to $what waits: $wait", node.id)
            return false
        }
        imageWaitNoted.remove(node.id)
        // A pull that failed is retried after a while, not on every pass.
        pullFailed[node.id]?.let { (failed, at) -> if (failed == image && nowMillis() - at < PULL_RETRY_MS) return false }
        val have = ssh.runScriptOrThrow(
            "cluster-image",
            "docker pull -q ${shQuote(image.ref)} >/dev/null 2>&1 || true; echo ${shQuote(image.ref)} | $created 2>/dev/null || true",
            1800,
        ).trim().trim('"')
        if (have != image.created) {
            pullFailed[node.id] = image to nowMillis()
            ctx.log.warn(domain, "could not get the cluster image $what onto this node; its member keeps its current image (retried in ${PULL_RETRY_MS / 60_000} min)", node.id)
            return false
        }
        pullFailed.remove(node.id)
        // Checked again right before starting (after the pull, which can take minutes): planned
        // maintenance may have begun since this pass started.
        if (maintenanceUntil() != null) return false
        ctx.log.change(domain, "updating the cluster member to $what", node.id)
        updatingNode = node.id
        try {
            movePrimariesOff(member.id, members, "maintenance", prefer = Mesh.HOMELAB).forEach { ctx.log.info(domain, it, node.id) }
            val services = listOf("etcd", "db", "valkey", "sentinel", "haproxy", MeshProbe.SERVICE)
            syncNode(ctx, node, cluster, member, members, secrets, initial, services, image.ref, rolling = true)
        } finally {
            updatingNode = null
        }
        ctx.state.updateNode(node.id) { it.copy(facts = it.facts + (CLUSTER_IMAGE_FACT to image.ref)) }
        ctx.log.change(domain, "cluster member updated to $what", node.id)
        lastView = null
        return true
    }

    private suspend fun waitFor(timeoutMs: Long, check: suspend () -> Boolean): Boolean {
        val start = nowMillis()
        while (nowMillis() - start < timeoutMs) {
            if (check()) return true
            delay(1_000)
        }
        return check()
    }

    private val historyJson = Json { ignoreUnknownKeys = true }

    fun history(): List<PrimaryChangeDto> = Files.readTextOrNull(historyFile)
        ?.let { runCatching { historyJson.decodeFromString<List<PrimaryChangeDto>>(it) }.getOrNull() }
        .orEmpty()

    /** Appends a change of primary to the history (kept in the data directory, newest first). */
    private fun recordPrimary(pg: List<PatroniClient.Member>) {
        val leader = pg.firstOrNull { it.role == "leader" }?.name ?: return
        val past = history()
        val last = past.firstOrNull()?.to
        if (leader == last) return
        val cause = pendingCause ?: if (last == null) "initial" else "failover"
        pendingCause = null
        val next = (listOf(PrimaryChangeDto(nowMillis(), last, leader, cause)) + past).take(HISTORY)
        Files.writeAtomic(historyFile, historyJson.encodeToString(next))
    }

    // ------------------------------------------------------------------ departed nodes

    private var exitPrunedAt = 0L

    /**
     * Removes what nodes no longer known to HomeGAP left in the cluster's Pangolin: their homelab
     * and exit sites (through [ready]'s API) and the exit node their Gerbil registered (straight
     * from the database through [ready]'s node, since Pangolin's API has no way to remove one).
     * [known] is every configured node and every node still waiting for its decommission.
     *
     * Only what HomeGAP itself creates is touched: sites whose niceId and name both follow its
     * patterns, and Gerbil exit nodes whose region (Pangolin's `app.region`, the node id) is set.
     * The database is checked when a site was removed and otherwise every few hours.
     */
    suspend fun pruneDeparted(ctx: ReconcileContext, ready: ReadyNode, known: Set<String>) {
        val org = ctx.config.pangolin.orgId
        val siteName = ctx.config.pangolin.siteName
        var removed = false
        for (site in ready.api.listSites(org)) {
            val departed = Departed.siteOwner(site.niceId, site.name, siteName) ?: continue
            if (departed in known) continue
            ready.api.deleteSite(site.siteId)
            ctx.log.change(domain, "removed site '${site.niceId}' left by departed node $departed")
            removed = true
        }
        if (!removed && nowMillis() - exitPrunedAt < EXIT_PRUNE_EVERY_MS) return
        val sql = Departed.exitNodeSql(known)
        val base = "${ctx.config.pangolin.installDir}/cluster"
        val script = $$"""
            |B=$${shQuote(base)}
            |(umask 077; printf 'PGPASSWORD=%s\n' $${shQuote(secrets().postgres)} > "$B/pg.env")
            |trap 'rm -f "$B/pg.env"' EXIT
            |docker run --rm --network $${ClusterTemplates.NETWORK} --env-file "$B/pg.env" --entrypoint psql $${shQuote(ctx.config.cluster!!.image)} \
            |  -h $${ClusterTemplates.PROXY_HOST} -p $${ClusterTemplates.PROXY_POSTGRES_PORT} -U postgres -At -F ' ' -c $${shQuote(sql)} postgres
        """.trimMargin()
        val out = ctx.ssh.session(ready.node).runScriptOrThrow("exit-nodes", script, 120)
        exitPrunedAt = nowMillis()
        Departed.parseDeleted(out).forEach { (id, region) ->
            ctx.log.change(domain, "removed exit node $id left by departed node $region")
        }
    }

    /** Pure helpers for [pruneDeparted]. */
    object Departed {
        private val ID = Regex("^[a-z0-9][a-z0-9-]*$")

        /** The node a HomeGAP-made site belongs to, or null for any other site. */
        fun siteOwner(niceId: String, name: String?, siteName: String): String? {
            niceId.removePrefix("$siteName-").takeIf { it != niceId && name == "$siteName via $it" }?.let { return it }
            niceId.removePrefix("exit-").takeIf { it != niceId && name == homegap.provision.ExitNodes.siteName(it) }?.let { return it }
            return null
        }

        /** Deletes Gerbil exit nodes whose region isn't one of [known]; prints `id region` for each. */
        fun exitNodeSql(known: Set<String>): String {
            val keep = known.filter { ID.matches(it) }.joinToString(",") { "'$it'" }.ifEmpty { "''" }
            return "DELETE FROM \"exitNodes\" WHERE type = 'gerbil' AND region IS NOT NULL AND region <> '' " +
                "AND region NOT IN ($keep) RETURNING \"exitNodeId\", region"
        }

        fun parseDeleted(out: String): List<Pair<Int, String>> = out.lines().mapNotNull { line ->
            val f = line.trim().split(' ')
            if (f.size == 2) f[0].toIntOrNull()?.let { it to f[1] } else null
        }
    }

    // ------------------------------------------------------------------ secrets and files

    fun secrets(): ClusterSecrets {
        Files.readTextOrNull(secretsFile)?.let { return json.decodeFromString(it) }
        val created = ClusterSecrets(Crypto.token(32), Crypto.token(32), Crypto.token(32), Crypto.token(32))
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        Files.writeAtomic(secretsFile, json.encodeToString(created), "600".toInt(8))
        return created
    }

    /** The homelab member's files. etcd's settings only matter on first start, so they are written once. */
    private suspend fun writeHomelab(ctx: ReconcileContext, cluster: ClusterConfig, homelab: Member, members: List<Member>, secrets: ClusterSecrets) {
        Files.mkdirs(configDir)
        Files.chmodPath(configDir, "700".toInt(8))
        write("$configDir/mesh.env", ClusterTemplates.meshEnv(homelab))
        if (Files.readTextOrNull("$configDir/etcd.env").isNullOrBlank()) {
            write("$configDir/etcd.env", ClusterTemplates.etcdEnv(homelab, listOf(homelab), "new"))
            ctx.log.change(domain, "started the cluster vote (first etcd member)", Mesh.HOMELAB)
        }
        // The homelab's db container notices a changed patroni.yml itself and has Patroni re-read it.
        if (write("$configDir/patroni.yml", ClusterTemplates.patroni(homelab, cluster.mesh, secrets))) {
            ctx.log.change(domain, "updated Patroni settings (applied by reload within seconds)", Mesh.HOMELAB)
        }
        write("$configDir/valkey.env", ClusterTemplates.valkeyEnv(homelab, secrets, PREFERRED_PRIORITY, homelab.meshIp, members.map { it.meshIp }))
        write("$configDir/sentinel.conf", ClusterTemplates.sentinel(homelab, homelab.meshIp, secrets))
    }

    /** Writes [content] to [path] when it differs; returns whether it changed. */
    private fun write(path: String, content: String): Boolean {
        if (Files.readTextOrNull(path) == content) return false
        Files.writeAtomic(path, content, "600".toInt(8))
        return true
    }

    // ------------------------------------------------------------------ nodes

    /** etcd's initial cluster for a joining node: every current member by name (the newcomer has none yet). */
    private fun etcdInitial(etcdMembers: List<EtcdClient.Member>, known: List<Member>): List<Member> =
        etcdMembers.mapNotNull { m -> known.firstOrNull { it.id == m.name || ClusterTemplates.peerUrl(it) in m.peerUrls } }

    private suspend fun syncNode(
        ctx: ReconcileContext,
        node: NodeConfig,
        cluster: ClusterConfig,
        member: Member,
        members: List<Member>,
        secrets: ClusterSecrets,
        initial: List<Member>,
        services: List<String>,
        image: String,
        rolling: Boolean = false,
    ) {
        val homelabIp = members.first { it.id == Mesh.HOMELAB }.meshIp
        val patroniYml = ClusterTemplates.patroni(member, cluster.mesh, secrets)
        val valkeyEnv = ClusterTemplates.valkeyEnv(member, secrets, NODE_PRIORITY, homelabIp, members.map { it.meshIp })
        val sentinelConf = ClusterTemplates.sentinel(member, homelabIp, secrets)
        val haproxyCfg = ClusterTemplates.haproxy(members, secrets, member.id)
        val probeTargets = MeshProbe.targets(member.id, members)
        val compose = ClusterTemplates.nodeCompose(
            image,
            mapOf(
                // The Sentinel list only matters at start; growing it must not restart Valkey.
                "valkey" to hash(valkeyEnv.lines().filterNot { it.startsWith("HG_SENTINELS=") }.joinToString("\n")),
                "sentinel" to hash(sentinelConf),
                MeshProbe.SERVICE to hash(MeshProbe.SCRIPT + probeTargets),
            ),
        )
        val base = "${ctx.config.pangolin.installDir}/cluster"
        val script = buildString {
            // Docker comes with the node's own provisioning.
            appendLine("type -P docker >/dev/null 2>&1 || { echo WAITING-FOR-DOCKER; exit 0; }")
            appendLine("set -e")
            appendLine("B=${shQuote(base)}")
            appendLine("install -d -m 0755 \"\$B\" \"\$B/etcd\" \"\$B/pgdata\" \"\$B/valkey\" \"\$B/sentinel\"")
            appendLine("install -d -m 0700 \"\$B/config\"")
            appendLine(fileStep("$base/config/mesh.env", ClusterTemplates.meshEnv(member)))
            appendLine(fileStep("$base/config/etcd.env", ClusterTemplates.etcdEnv(member, initial, "existing"), writeOnce = true))
            // Patroni settings are reloaded, never a reason to restart the database.
            appendLine("PATRONI=no")
            appendLine(fileStep("$base/config/patroni.yml", patroniYml).replace("CHANGED=yes", "CHANGED=yes; PATRONI=yes"))
            appendLine(fileStep("$base/config/valkey.env", valkeyEnv))
            appendLine(fileStep("$base/config/sentinel.conf", sentinelConf))
            // HAProxy reloads its config in place (no refused connections), never restarts for it.
            appendLine("HAPROXY=no")
            appendLine(fileStep("$base/config/haproxy.cfg", haproxyCfg).replace("CHANGED=yes", "HAPROXY=yes"))
            appendLine(fileStep("$base/config/probe.py", MeshProbe.SCRIPT))
            appendLine("rm -f \"\$B/config/probe.sh\"")
            appendLine(fileStep("$base/config/probe.targets", probeTargets))
            appendLine(fileStep("$base/compose.yml", compose, mode = "0644"))
            appendLine(SshSession.ownInstallDir(ctx.config.pangolin.installDir))
            appendLine("cd \"\$B\"")
            val wanted = services.joinToString(" ")
            appendLine("RUNNING=\$(docker compose ps --services --status running 2>/dev/null | sort | tr '\\n' ' ')")
            appendLine("WANT=\$(printf '%s\\n' $wanted | sort | tr '\\n' ' ')")
            if (rolling) {
                // One service at a time, each serving again before the next is restarted.
                appendLine(ROLL_FUNCTION)
                for ((svc, check) in ROLL_ORDER) appendLine("roll $svc $check")
                appendLine("echo \"CHANGED cluster member restarted one service at a time: ${ROLL_ORDER.joinToString(" ") { it.first }}\"")
            } else {
                appendLine("if [ \"\$CHANGED\" = yes ] || [ \"\$RUNNING\" != \"\$WANT\" ]; then docker compose up -d --remove-orphans $wanted >/dev/null 2>&1 || docker compose up -d --remove-orphans $wanted; echo \"CHANGED cluster member running: $wanted\"; fi")
            }
            // Bounded, so nothing in the container can hold up the pass; a failure is the node's error.
            appendLine("if [ \"\$HAPROXY\" = yes ] && [ -n \"\$(docker compose ps -q --status running haproxy 2>/dev/null)\" ]; then timeout 30 docker compose exec -T haproxy ${ClusterTemplates.HAPROXY_RELOAD} </dev/null >/dev/null; echo 'CHANGED reloaded the cluster proxy'; fi")
            // The member's image copies patroni.yml in at start (and, in newer images, on change):
            // copy it now and have Patroni re-read it.
            appendLine("if [ \"\$PATRONI\" = yes ] && [ -n \"\$(docker compose ps -q --status running db 2>/dev/null)\" ]; then docker compose exec -T db sh -c ${shQuote(ClusterTemplates.PATRONI_RELOAD)} </dev/null && echo 'CHANGED reloaded Patroni settings'; fi")
        }
        val ssh = ctx.ssh.session(node)
        val out = ssh.runScriptOrThrow("cluster", "CHANGED=no\n$script", if (rolling) 1800 else 900)
        out.lines().filter { it.startsWith("CHANGED ") }.forEach { ctx.log.change(domain, it.removePrefix("CHANGED "), node.id) }
    }

    private suspend fun hasDocker(ctx: ReconcileContext, node: NodeConfig): Boolean =
        ctx.ssh.session(node).runScript("type -P docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 && echo DOCKER-OK", 60).stdout.contains("DOCKER-OK")

    private fun fileStep(path: String, content: String, mode: String = "0600", writeOnce: Boolean = false): String {
        val b64 = Base64.encode(content.encodeToByteArray())
        val q = shQuote(path)
        val write = "printf '%s' '$b64' | base64 -d > $q.new && chmod $mode $q.new && mv -f $q.new $q && CHANGED=yes"
        return if (writeOnce) "[ -s $q ] || { $write; }"
        else "printf '%s' '$b64' | base64 -d > $q.new; if cmp -s $q.new $q; then rm -f $q.new; else chmod $mode $q.new; mv -f $q.new $q; CHANGED=yes; fi"
    }

    /** Removes etcd members whose node left the cluster (never the homelab). */
    private suspend fun removeDeparted(ctx: ReconcileContext, etcd: EtcdClient, etcdMembers: List<EtcdClient.Member>, nodes: List<Member>) {
        for (m in etcdMembers) {
            if (m.name == Mesh.HOMELAB) continue
            val wanted = nodes.any { it.id == m.name || ClusterTemplates.peerUrl(it) in m.peerUrls }
            if (wanted) continue
            runCatching { etcd.remove(m.id) }
                .onSuccess { ctx.log.change(domain, "removed ${m.name.ifEmpty { m.peerUrls.joinToString() }} from the cluster vote") }
                .onFailure { ctx.log.warn(domain, "could not remove etcd member ${m.name}: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ switch-back and status

    /** The database as any reachable member sees it (the homelab first). */
    private suspend fun databaseView(patroni: PatroniClient, members: List<Member>): List<PatroniClient.Member>? {
        for (m in members) {
            val view = runCatching { patroni.cluster(restUrl(m)) }.getOrNull()
            if (view != null) return view
        }
        return null
    }

    private suspend fun switchBack(
        ctx: ReconcileContext,
        cluster: ClusterConfig,
        patroni: PatroniClient,
        pg: List<PatroniClient.Member>?,
        homelab: Member,
        members: List<Member>,
        secrets: ClusterSecrets,
    ): String? {
        if (pg == null) return null
        val now = nowMillis()
        maintenanceUntil(now)?.let { until ->
            preferredCaughtUpSince = null
            switchBackDue = null
            return "held off the homelab for maintenance (at most ${(until - now + 59_999) / 60_000} more min)"
        }
        val hold = cluster.primary.switchBackDelay.inWholeMilliseconds
        val decision = SwitchBack.evaluate(pg, cluster.primary.preferred, preferredCaughtUpSince, now, hold)
        preferredCaughtUpSince = decision.caughtUpSince
        switchBackDue = decision.caughtUpSince?.plus(hold)?.takeIf { decision.switchFrom == null }
        decision.switchFrom?.let { leader ->
            val leaderMember = members.firstOrNull { it.id == leader } ?: return decision.note
            try {
                notePlannedMove()
                patroni.switchover(restUrl(leaderMember), leader, cluster.primary.preferred)
                pendingCause = "switch-back"
                switchBackDue = null
                ctx.log.change(domain, "switched the database primary back from $leader to ${cluster.primary.preferred}")
                preferredCaughtUpSince = null
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                ctx.log.error(domain, "switch-back from $leader failed: ${e.message}")
            }
            return decision.note
        }
        // Valkey follows once PostgreSQL is back on the preferred member.
        if (pg.firstOrNull { it.role == "leader" }?.name == cluster.primary.preferred) {
            val valkey = ValkeyCli(secrets.valkey)
            val master = sentinelMaster(valkey, members)
            val homelabLinked = valkey.replication(homelab.meshIp)?.get("master_link_status") == "up"
            if (master != null && master != homelab.meshIp && homelabLinked) {
                notePlannedMove()
                if (valkey.sentinelFailover("127.0.0.1", ClusterTemplates.SCOPE)) {
                    ctx.log.change(domain, "switched the Valkey primary back to ${cluster.primary.preferred}")
                }
            }
        }
        return decision.note
    }

    private suspend fun sentinelMaster(valkey: ValkeyCli, members: List<Member>): String? {
        valkey.sentinelMaster("127.0.0.1", ClusterTemplates.SCOPE)?.let { return it }
        for (m in members) valkey.sentinelMaster(m.meshIp, ClusterTemplates.SCOPE)?.let { return it }
        return null
    }

    private suspend fun status(
        cluster: ClusterConfig,
        members: List<Member>,
        etcdMembers: List<EtcdClient.Member>?,
        pg: List<PatroniClient.Member>?,
        secrets: ClusterSecrets,
        switchBack: String?,
    ): ClusterStatusDto {
        val valkey = ValkeyCli(secrets.valkey)
        val valkeyMaster = sentinelMaster(valkey, members)
        // Each member's own view: its etcd's health and its Valkey role.
        val probes = coroutineScope {
            members.map { m ->
                async {
                    val etcdOk = EtcdClient(http, "http://${m.meshIp}:${ClusterTemplates.ETCD_CLIENT_PORT}").healthy()
                    m.id to (etcdOk to valkey.replication(m.meshIp)?.get("role"))
                }
            }.awaitAll().toMap()
        }
        val voters = etcdMembers?.filter { !it.learner }
        val quorum = voters?.let { vs ->
            val healthy = vs.count { v -> members.firstOrNull { it.id == v.name || ClusterTemplates.peerUrl(it) in v.peerUrls }?.let { probes[it.id]?.first } == true }
            healthy * 2 > vs.size
        }
        return ClusterStatusDto(
            primary = pg?.firstOrNull { it.role == "leader" }?.name,
            valkeyPrimary = members.firstOrNull { it.meshIp == valkeyMaster }?.id ?: valkeyMaster,
            preferred = cluster.primary.preferred,
            switchBack = switchBack,
            switchBackAt = switchBackDue,
            quorum = quorum,
            history = history(),
            members = members.map { m ->
                val e = etcdMembers?.firstOrNull { it.name == m.id || ClusterTemplates.peerUrl(m) in it.peerUrls }
                val p = pg?.firstOrNull { it.name == m.id }
                ClusterMemberDto(
                    id = m.id,
                    address = m.meshIp,
                    vote = when {
                        etcdMembers == null -> "unknown"
                        e == null -> "not joined"
                        e.learner -> "joining"
                        else -> "voter"
                    },
                    role = p?.role,
                    state = p?.state,
                    lagBytes = p?.lagBytes,
                    timeline = p?.timeline,
                    valkeyRole = probes[m.id]?.second,
                    etcdHealthy = probes[m.id]?.first,
                )
            },
        )
    }

    private fun report(ctx: ReconcileContext, id: String, error: String?) {
        val previous = lastErrors[id]
        when {
            error != null && error != previous -> {
                lastErrors[id] = error
                ctx.log.error(domain, error, id.takeIf { it != "etcd" })
            }
            error == null && previous != null -> {
                lastErrors.remove(id)
                ctx.log.info(domain, "recovered", id.takeIf { it != "etcd" })
            }
        }
    }

    private fun restUrl(m: Member) = "http://${m.meshIp}:${ClusterTemplates.PATRONI_PORT}"

    private fun hash(s: String): String = Crypto.fnv64(s)

    private companion object {
        /** The node fact holding the cluster image last rolled out to its member. */
        const val CLUSTER_IMAGE_FACT = "clusterImage"

        /** How long a node waits before trying an image it could not pull again. */
        const val PULL_RETRY_MS = 30 * 60_000L

        /** Restart order for an image update, with how each is checked: an hg-member role, `proxy`, or `-`. */
        val ROLL_ORDER = listOf("etcd" to "etcd", "sentinel" to "sentinel", "valkey" to "valkey", "db" to "patroni", "haproxy" to "proxy", MeshProbe.SERVICE to "-")

        /**
         * Node scripts reach bash on stdin, so every `docker compose exec` in them reads from
         * /dev/null: otherwise it would swallow the rest of the script. The image changed, so each
         * service is recreated even though its compose entry may read the same.
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

        /** Sentinel promotes the lowest priority: the homelab first. */
        const val PREFERRED_PRIORITY = 1
        const val NODE_PRIORITY = 100
        const val HISTORY = 20
        /** How often the database is checked for exit nodes left by departed nodes. */
        const val EXIT_PRUNE_EVERY_MS = 6 * 3_600_000L
    }
}

/**
 * The cluster image the homelab's member runs: a reference other hosts can pull, and when it was
 * built (`Created`), which identifies it on any host (image ids differ between Docker's stores).
 */
data class MemberImage(val ref: String, val created: String)

/** When to hand the database primary back to the preferred member. */
object SwitchBack {
    /**
     * [caughtUpSince] is when the preferred member was first seen as a caught-up replica (null when
     * it is not); [switchFrom] names the leader to switch away from once the hold has passed.
     */
    data class Decision(val caughtUpSince: Long?, val switchFrom: String?, val note: String?)

    fun evaluate(members: List<PatroniClient.Member>, preferred: String, caughtUpSince: Long?, now: Long, holdMs: Long): Decision {
        val leader = members.firstOrNull { it.role == "leader" } ?: return Decision(null, null, null)
        if (leader.name == preferred) return Decision(null, null, null)
        val p = members.firstOrNull { it.name == preferred }
        val caughtUp = p != null && p.state == "streaming" && p.lagBytes == 0L
        if (!caughtUp) return Decision(null, null, "$preferred is not a caught-up replica yet; the primary stays on ${leader.name}")
        val since = caughtUpSince ?: now
        val remaining = holdMs - (now - since)
        return if (remaining <= 0) Decision(since, leader.name, "switching the primary back to $preferred")
        else Decision(since, null, "$preferred caught up; switching back in ${(remaining + 59_999) / 60_000} min")
    }
}
