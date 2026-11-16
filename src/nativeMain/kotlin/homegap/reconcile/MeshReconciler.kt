package homegap.reconcile

import homegap.api.MeshMemberDto
import homegap.api.MeshPeerDto
import homegap.api.MeshStatusDto
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.platform.runCatchingCancellable
import homegap.provision.Mesh
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Mesh domain: keeps the WireGuard mesh ([Mesh]) in step with the configured nodes.
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

    /** The members of the mesh in one pass, and the address of each. */
    private class Layout(
        val mesh: MeshConfig,
        val members: List<NodeConfig>,
        val addresses: Map<String, String>,
        val homelabKey: String?,
        val forwards: MeshForwards.Result,
    ) {
        val homelabAddress = Mesh.homelabAddress(mesh)

        fun addressOf(node: NodeConfig): String = addresses.getValue(node.id)
    }

    /** The result of a pass for one member. [peers] is null if the member gives no answer. */
    private class MemberState(
        val id: String,
        val address: String,
        val peers: List<Mesh.PeerStatus>?,
        val error: String?,
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
        )
    }

    /** Returns the status of the mesh. */
    suspend fun reconcile(
        ctx: ReconcileContext,
        nodes: List<NodeConfig>,
        slots: Map<String, Int>,
        forwards: MeshForwards.Result,
    ): MeshStatusDto {
        val mesh = ctx.config.mesh
        val members = nodes.filter { ctx.state.node(it.id).teardownError == null && slots[it.id] != null }
        val homelabKey = runCatchingCancellable { homelab.publicKey() }
        val layout = Layout(
            mesh = mesh,
            members = members,
            addresses = members.associate { it.id to Mesh.nodeAddress(mesh, slots.getValue(it.id)) },
            homelabKey = homelabKey.getOrNull(),
            forwards = forwards,
        )

        val nodeStates = ctx.times.measure("mesh.nodes") { configureNodes(ctx, layout) }
        val homelabState = when (val failure = homelabKey.exceptionOrNull()) {
            null -> ctx.times.measure("mesh.homelab") { configureHomelab(ctx, layout) }
            else -> homelabState(layout, peers = null, error = "homelab mesh key: ${failure.message}")
        }
        report(ctx, Mesh.HOMELAB, homelabState.error)
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
            else -> {
                val ports = layout.forwards.nodePorts[node.id].orEmpty()
                val agentPorts = layout.forwards.agentPorts[node.id].orEmpty()
                val forwards = Mesh.forwardStep(Mesh.forwardScript(layout.homelabAddress, ports, agentPorts))
                val mesh = Mesh.nodeScript(
                    mesh = layout.mesh,
                    address = address,
                    config = Mesh.nodeConfig(layout.mesh, address, peersOf(ctx, layout, node.id)),
                    openFirewall = node.firewall,
                )
                "$forwards\n$mesh"
            }
        }
        val result = ctx.ssh.session(node).runScript(setup, SCRIPT_TIMEOUT_SECONDS)
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

    private fun memberState(node: NodeConfig, address: String, output: String): MemberState =
        MemberState(node.id, address, Mesh.parseDump(Mesh.dumpSection(output)), error = null)

    // ------------------------------------------------------------------ homelab

    private suspend fun configureHomelab(ctx: ReconcileContext, layout: Layout): MemberState {
        val peers = runCatchingCancellable {
            homelab.apply(layout.mesh, layout.homelabAddress, peersOf(ctx, layout, Mesh.HOMELAB), layout.forwards)
            homelab.peers()
        }
        val error = peers.exceptionOrNull()?.let { it.message ?: it.toString() }
        return homelabState(layout, peers.getOrNull(), error)
    }

    private fun homelabState(layout: Layout, peers: List<Mesh.PeerStatus>?, error: String?) =
        MemberState(Mesh.HOMELAB, layout.homelabAddress, peers, error)

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
        return MeshStatusDto(
            subnet = layout.mesh.subnet,
            members = (listOf(homelabState) + nodeStates).map { it.dto(idByKey) },
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
        const val ERROR_TEXT_LENGTH = 400

        /** The script for a paused node. It reads the status and changes nothing. */
        const val READ_ONLY = "echo DUMP-BEGIN; wg show ${Mesh.INTERFACE} dump 2>/dev/null || true; echo DUMP-END"

        // Lines and sections in the output of the node script.
        const val CHANGED = "CHANGED "
        const val PUBLIC_KEY = "PUBKEY="
    }
}
