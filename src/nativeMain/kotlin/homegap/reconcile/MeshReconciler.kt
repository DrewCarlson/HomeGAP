package homegap.reconcile

import homegap.api.MeshMemberDto
import homegap.api.MeshPeerDto
import homegap.api.MeshProbeDto
import homegap.api.MeshStatusDto
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.platform.nowMillis
import homegap.platform.runCatchingCancellable
import homegap.provision.ClusterTemplates
import homegap.provision.Mesh
import homegap.provision.MeshProbe
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Mesh domain: keeps the WireGuard mesh of the cluster ([Mesh]) in step with the configured nodes.
 *
 * In each pass, every node gets its setup script over SSH. The script creates the key of the node
 * once, writes the peer list and applies changes in place. The homelab interface follows. The
 * script output gives the public key of the node. If a pass finds a new key, a second round sends
 * it to the other members in the same pass. The reconciler only reads a paused node.
 *
 * The controller keeps one instance, so the log shows an error that stays only once.
 */
class MeshReconciler(private val homelab: HomelabMesh) {
    private val lastErrors = mutableMapOf<String, String>()
    private val nodeProbes = MeshProbe.History()
    private val containerProbes = MeshProbe.History()
    private val homelabProbe = HomelabProbe()

    /** The members of the mesh in one pass, and the address of each. */
    private class Layout(
        val mesh: MeshConfig,
        val members: List<NodeConfig>,
        val addresses: Map<String, String>,
        val homelabKey: String?,
    ) {
        val homelabAddress = Mesh.homelabAddress(mesh)
        val memberIds = members.map { it.id }.toSet()

        fun addressOf(node: NodeConfig): String = addresses.getValue(node.id)
    }

    /** The result of a pass for one member. [peers] is null if the member gives no answer. */
    private class MemberState(
        val id: String,
        val address: String,
        val peers: List<Mesh.PeerStatus>?,
        val error: String?,
        val probe: Map<String, MeshProbeDto> = emptyMap(),
        val containerProbe: Map<String, MeshProbeDto> = emptyMap(),
    ) {
        /** WireGuard names a peer by its public key. [idByKey] gives the member that has each key. */
        fun dto(idByKey: Map<String, String>) = MeshMemberDto(
            id = id,
            address = address,
            error = error,
            peers = peers.orEmpty().map { peerDto(it, idByKey[it.publicKey]) }.sortedBy { it.id },
        )

        private fun peerDto(peer: Mesh.PeerStatus, peerId: String?) = MeshPeerDto(
            id = peerId ?: "unknown",
            endpoint = peer.endpoint,
            lastHandshakeAt = peer.latestHandshake.takeIf { it > 0 }?.let { it * 1000 },
            rxBytes = peer.rxBytes,
            txBytes = peer.txBytes,
            probe = probe[peerId],
            containerProbe = containerProbe[peerId],
        )
    }

    /** Returns the status of the mesh, or null if the config has no cluster. */
    suspend fun reconcile(ctx: ReconcileContext, nodes: List<NodeConfig>, slots: Map<String, Int>): MeshStatusDto? {
        val mesh = ctx.config.cluster?.mesh ?: return null
        val members = nodes.filter { ctx.state.node(it.id).teardownError == null && slots[it.id] != null }
        val homelabKey = runCatchingCancellable { homelab.publicKey() }
        val layout = Layout(
            mesh = mesh,
            members = members,
            addresses = members.associate { it.id to Mesh.nodeAddress(mesh, slots.getValue(it.id)) },
            homelabKey = homelabKey.getOrNull(),
        )

        val nodeStates = configureNodes(ctx, layout)
        val homelabState = when (val failure = homelabKey.exceptionOrNull()) {
            null -> configureHomelab(ctx, layout)
            else -> homelabState(layout, peers = null, error = "homelab mesh key: ${failure.message}")
        }
        report(ctx, Mesh.HOMELAB, homelabState.error)
        if (homelabState.peers != null) {
            probeFromHomelab(ctx, layout)
        }

        nodeProbes.retain(layout.memberIds + Mesh.HOMELAB)
        containerProbes.retain(layout.memberIds + Mesh.HOMELAB)
        return status(ctx, layout, homelabState, nodeStates)
    }

    // ------------------------------------------------------------------ nodes

    /**
     * Runs the setup script on each member. A node that joins gets its key in the first round, and
     * the other members do not have that key in their peer list yet. A second round sends it to them.
     */
    private suspend fun configureNodes(ctx: ReconcileContext, layout: Layout): List<MemberState> {
        val keysBefore = memberKeys(ctx, layout)
        val states = configureEachNode(ctx, layout)
        val newKeys = memberKeys(ctx, layout) != keysBefore
        return if (newKeys) configureEachNode(ctx, layout) else states
    }

    private suspend fun configureEachNode(ctx: ReconcileContext, layout: Layout): List<MemberState> =
        coroutineScope {
            layout.members
                .map { node -> async { configureNode(ctx, layout, node) } }
                .awaitAll()
        }

    private suspend fun configureNode(ctx: ReconcileContext, layout: Layout, node: NodeConfig): MemberState {
        val address = layout.addressOf(node)
        val result = runCatchingCancellable {
            val output = runNodeScript(ctx, layout, node)
            output.lines()
                .filter { it.startsWith(CHANGED) }
                .forEach { ctx.log.change(DOMAIN, it.removePrefix(CHANGED), node.id) }
            publicKeyIn(output)?.let { key -> rememberKey(ctx, node, address, key) }
            memberState(node, address, output)
        }
        val error = result.exceptionOrNull()?.let { it.message ?: it.toString() }
        report(ctx, node.id, error)
        return result.getOrNull() ?: MemberState(node.id, address, peers = null, error = error)
    }

    /** Returns the output of the script. If the script fails, throws with the end of its error text. */
    private suspend fun runNodeScript(ctx: ReconcileContext, layout: Layout, node: NodeConfig): String {
        val address = layout.addressOf(node)
        val setup = when {
            ctx.nodePaused(node.id) -> READ_ONLY
            else -> Mesh.nodeScript(
                mesh = layout.mesh,
                address = address,
                config = Mesh.nodeConfig(layout.mesh, address, peersOf(ctx, layout, node.id)),
                openFirewall = node.firewall,
            )
        }
        val probes = listOf(
            "echo $PROBE_BEGIN",
            MeshProbe.READ_CMD,
            "echo $PROBE_END",
            "echo $CONTAINER_PROBE_BEGIN",
            MeshProbe.containerReadCmd(ctx.config.pangolin.installDir),
            "echo $CONTAINER_PROBE_END",
        ).joinToString("; ")
        val result = ctx.ssh.session(node).runScript("$setup\n$probes", SCRIPT_TIMEOUT_SECONDS)
        check(result.ok) {
            result.stderr.trim()
                .ifEmpty { result.stdout.trim() }
                .takeLast(ERROR_TEXT_LENGTH)
                .ifEmpty { "exit ${result.exitCode}" }
        }
        return result.stdout
    }

    private fun publicKeyIn(output: String): String? =
        output.lines()
            .firstOrNull { it.startsWith(PUBLIC_KEY) }
            ?.removePrefix(PUBLIC_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private suspend fun rememberKey(ctx: ReconcileContext, node: NodeConfig, address: String, key: String) {
        if (ctx.state.node(node.id).meshPublicKey == key) return
        ctx.state.updateNode(node.id) { it.copy(meshPublicKey = key) }
        ctx.log.change(DOMAIN, "joined the mesh at $address", node.id)
    }

    private fun memberState(node: NodeConfig, address: String, output: String): MemberState {
        val now = nowMillis()
        val probeText = output.substringAfter(PROBE_BEGIN, "").substringBefore(PROBE_END)
        val containerText = output.substringAfter(CONTAINER_PROBE_BEGIN, "").substringBefore(CONTAINER_PROBE_END)
        return MemberState(
            id = node.id,
            address = address,
            peers = Mesh.parseDump(Mesh.dumpSection(output)),
            error = null,
            probe = nodeProbes.record(node.id, MeshProbe.parse(probeText), now),
            containerProbe = containerProbes.record(node.id, MeshProbe.parseContainer(containerText, now), now),
        )
    }

    // ------------------------------------------------------------------ homelab

    private suspend fun configureHomelab(ctx: ReconcileContext, layout: Layout): MemberState {
        val peers = runCatchingCancellable {
            homelab.apply(layout.mesh, layout.homelabAddress, peersOf(ctx, layout, Mesh.HOMELAB))
            homelab.peers()
        }
        val error = peers.exceptionOrNull()?.let { it.message ?: it.toString() }
        return homelabState(layout, peers.getOrNull(), error)
    }

    private fun homelabState(layout: Layout, peers: List<Mesh.PeerStatus>?, error: String?) =
        MemberState(Mesh.HOMELAB, layout.homelabAddress, peers, error)

    /**
     * Sends one timed request from the homelab to the Patroni of each member. The request uses
     * plain HTTP, because the mesh encrypts it.
     */
    @Suppress("HttpUrlsUsage")
    private suspend fun probeFromHomelab(ctx: ReconcileContext, layout: Layout) {
        homelabProbe.retain(layout.memberIds)
        coroutineScope {
            layout.members.map { node ->
                async {
                    val url = "http://${layout.addressOf(node)}:${ClusterTemplates.PATRONI_PORT}/liveness"
                    val started = nowMillis()
                    val answered = runCatchingCancellable {
                        ctx.localHttp.get(url) { timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS } }
                    }.isSuccess
                    val now = nowMillis()
                    homelabProbe.record(
                        peer = node.id,
                        at = now,
                        rttMs = if (answered) (now - started).toDouble() else null,
                    )
                }
            }.awaitAll()
        }
    }

    // ------------------------------------------------------------------ peers and status

    private fun memberKeys(ctx: ReconcileContext, layout: Layout): Map<String, String?> =
        layout.members.associate { it.id to ctx.state.node(it.id).meshPublicKey }

    /** The peers of member [selfId]: the homelab and each node that has a key, but not the member itself. */
    private fun peersOf(ctx: ReconcileContext, layout: Layout, selfId: String): List<Mesh.Peer> = buildList {
        if (selfId != Mesh.HOMELAB && layout.homelabKey != null) {
            add(Mesh.Peer(Mesh.HOMELAB, layout.homelabKey, layout.homelabAddress, endpoint = null))
        }
        for (node in layout.members) {
            if (node.id == selfId) continue
            val key = ctx.state.node(node.id).meshPublicKey ?: continue
            add(Mesh.Peer(node.id, key, layout.addressOf(node), node.endpoint()))
        }
    }

    private fun status(
        ctx: ReconcileContext,
        layout: Layout,
        homelabState: MemberState,
        nodeStates: List<MemberState>,
    ): MeshStatusDto {
        val idByKey = buildMap {
            layout.homelabKey?.let { put(it, Mesh.HOMELAB) }
            memberKeys(ctx, layout).forEach { (id, key) -> if (key != null) put(key, id) }
        }
        // The probe of the homelab has the readings of this pass only now.
        val homelabView = MemberState(
            id = homelabState.id,
            address = homelabState.address,
            peers = homelabState.peers,
            error = homelabState.error,
            probe = homelabProbe.view(),
        )
        return MeshStatusDto(
            subnet = layout.mesh.subnet,
            members = (listOf(homelabView) + nodeStates).map { it.dto(idByKey) },
        )
    }

    // ------------------------------------------------------------------ errors

    /**
     * Logs the error of a member the first time and after each change. Logs the recovery once.
     * An error that stays counts in each pass.
     */
    private fun report(ctx: ReconcileContext, id: String, error: String?) {
        val previous = lastErrors[id]
        when {
            error == null -> if (previous != null) {
                lastErrors.remove(id)
                ctx.log.info(DOMAIN, "mesh is healthy again", id)
            }
            error != previous -> {
                lastErrors[id] = error
                ctx.log.error(DOMAIN, error, id)
            }
            else -> ctx.log.stillFailing()
        }
    }

    private fun NodeConfig.endpoint(): String = ipv4() ?: publicIpv6 ?: host

    private companion object {
        const val DOMAIN = "mesh"
        const val SCRIPT_TIMEOUT_SECONDS = 300
        const val PROBE_TIMEOUT_MS = 3_000L
        const val ERROR_TEXT_LENGTH = 400

        /** The script for a paused node. It reads the status and changes nothing. */
        const val READ_ONLY = "echo DUMP-BEGIN; wg show ${Mesh.INTERFACE} dump 2>/dev/null || true; echo DUMP-END"

        // Lines and sections in the output of the node script.
        const val CHANGED = "CHANGED "
        const val PUBLIC_KEY = "PUBKEY="
        const val PROBE_BEGIN = "PROBE-BEGIN"
        const val PROBE_END = "PROBE-END"
        const val CONTAINER_PROBE_BEGIN = "CPROBE-BEGIN"
        const val CONTAINER_PROBE_END = "CPROBE-END"
    }
}
