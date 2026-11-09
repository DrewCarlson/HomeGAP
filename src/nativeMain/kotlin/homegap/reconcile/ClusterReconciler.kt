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
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.ClusterTemplates.Member
import homegap.provision.Mesh
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.ListSerializer
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
    /** The members and database view of the last pass, for a switchover from the dashboard. */
    @Volatile private var lastView: Pair<List<Member>, List<PatroniClient.Member>>? = null

    /** The node's member can serve its Pangolin: read by provisioning jobs between passes. */
    fun memberReady(id: String): Boolean = id in ready

    suspend fun reconcile(ctx: ReconcileContext, nodes: List<NodeConfig>, slots: Map<String, Int>): ClusterStatusDto? {
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
        val initialized = Files.exists(initializedFile) ||
            (pg?.any { it.role == "leader" } == true).also { if (it) Files.writeAtomic(initializedFile, "") }

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
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(now, members), listOf("etcd"))
                        }
                        existing == null -> Unit // another node is joining; this one waits its turn
                        existing.learner -> {
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(etcdMembers, members), listOf("etcd"))
                            val promoted = runCatching { etcd.promote(existing.id) }.isSuccess
                            if (promoted) ctx.log.change(domain, "joined the cluster vote", node.id)
                        }
                        else -> {
                            val services = if (initialized) listOf("etcd", "db", "valkey", "sentinel", "haproxy") else listOf("etcd")
                            syncNode(ctx, node, cluster, member, members, secrets, etcdInitial(etcdMembers, members), services)
                        }
                    }
                    report(ctx, node.id, null)
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    report(ctx, node.id, e.message ?: e.toString())
                }
            }
        }

        if (pg != null) {
            lastView = members to pg
            recordPrimary(pg)
        }
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
        val target = pg.firstOrNull { it.name == to } ?: throw IllegalArgumentException("$to is not a database member")
        check(target.state == "streaming" && target.lagBytes == 0L) { "$to is not a caught-up replica (${target.state})" }
        val leaderMember = members.first { it.id == leader.name }
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

    private val historyJson = Json { ignoreUnknownKeys = true }

    fun history(): List<PrimaryChangeDto> = Files.readTextOrNull(historyFile)
        ?.let { runCatching { historyJson.decodeFromString(ListSerializer(PrimaryChangeDto.serializer()), it) }.getOrNull() }
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
        Files.writeAtomic(historyFile, historyJson.encodeToString(ListSerializer(PrimaryChangeDto.serializer()), next))
    }

    // ------------------------------------------------------------------ secrets and files

    fun secrets(): ClusterSecrets {
        Files.readTextOrNull(secretsFile)?.let { return json.decodeFromString(ClusterSecrets.serializer(), it) }
        val created = ClusterSecrets(Crypto.token(32), Crypto.token(32), Crypto.token(32), Crypto.token(32))
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        Files.writeAtomic(secretsFile, json.encodeToString(ClusterSecrets.serializer(), created), "600".toInt(8))
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
        val patroniChanged = write("$configDir/patroni.yml", ClusterTemplates.patroni(homelab, cluster.mesh, secrets))
        write("$configDir/valkey.env", ClusterTemplates.valkeyEnv(homelab, secrets, PREFERRED_PRIORITY, homelab.meshIp, members.map { it.meshIp }))
        write("$configDir/sentinel.conf", ClusterTemplates.sentinel(homelab, homelab.meshIp, secrets))
        if (patroniChanged) {
            runCatching { PatroniClient(http, ClusterTemplates.REST_USER, secrets.rest).reload(restUrl(homelab)) }
        }
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
    ) {
        val homelabIp = members.first { it.id == Mesh.HOMELAB }.meshIp
        val patroniYml = ClusterTemplates.patroni(member, cluster.mesh, secrets)
        val valkeyEnv = ClusterTemplates.valkeyEnv(member, secrets, NODE_PRIORITY, homelabIp, members.map { it.meshIp })
        val sentinelConf = ClusterTemplates.sentinel(member, homelabIp, secrets)
        val haproxyCfg = ClusterTemplates.haproxy(members, secrets)
        val compose = ClusterTemplates.nodeCompose(
            cluster.image,
            mapOf(
                "db" to hash(patroniYml),
                // The Sentinel list only matters at start; growing it must not restart Valkey.
                "valkey" to hash(valkeyEnv.lines().filterNot { it.startsWith("HG_SENTINELS=") }.joinToString("\n")),
                "sentinel" to hash(sentinelConf),
                "haproxy" to hash(haproxyCfg),
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
            appendLine(fileStep("$base/config/patroni.yml", patroniYml))
            appendLine(fileStep("$base/config/valkey.env", valkeyEnv))
            appendLine(fileStep("$base/config/sentinel.conf", sentinelConf))
            appendLine(fileStep("$base/config/haproxy.cfg", haproxyCfg))
            appendLine(fileStep("$base/compose.yml", compose, mode = "0644"))
            appendLine(SshSession.ownInstallDir(ctx.config.pangolin.installDir))
            appendLine("cd \"\$B\"")
            val wanted = services.joinToString(" ")
            appendLine("RUNNING=\$(docker compose ps --services --status running 2>/dev/null | sort | tr '\\n' ' ')")
            appendLine("WANT=\$(printf '%s\\n' $wanted | sort | tr '\\n' ' ')")
            appendLine("if [ \"\$CHANGED\" = yes ] || [ \"\$RUNNING\" != \"\$WANT\" ]; then docker compose up -d --remove-orphans $wanted >/dev/null 2>&1 || docker compose up -d --remove-orphans $wanted; echo \"CHANGED cluster member running: $wanted\"; fi")
        }
        val ssh = ctx.ssh.session(node)
        val out = ssh.runScriptOrThrow("cluster", "CHANGED=no\n$script", 900)
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
        val hold = cluster.primary.switchBackDelay.inWholeMilliseconds
        val decision = SwitchBack.evaluate(pg, cluster.primary.preferred, preferredCaughtUpSince, now, hold)
        preferredCaughtUpSince = decision.caughtUpSince
        switchBackDue = decision.caughtUpSince?.plus(hold)?.takeIf { decision.switchFrom == null }
        decision.switchFrom?.let { leader ->
            val leaderMember = members.firstOrNull { it.id == leader } ?: return decision.note
            try {
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
        /** Sentinel promotes the lowest priority: the homelab first. */
        const val PREFERRED_PRIORITY = 1
        const val NODE_PRIORITY = 100
        const val HISTORY = 20
    }
}

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
