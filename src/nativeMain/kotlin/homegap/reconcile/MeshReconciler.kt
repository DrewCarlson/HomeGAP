package homegap.reconcile

import homegap.api.MeshMemberDto
import homegap.api.MeshPeerDto
import homegap.api.MeshStatusDto
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.provision.Mesh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Mesh domain: keeps the cluster's WireGuard mesh ([Mesh]) in step with the configured nodes.
 *
 * Each pass, every node gets its setup script over SSH, which creates the node's key once, writes
 * the peer list and applies changes in place. The homelab interface follows. A node's public key is
 * learned from its script output; when new keys appear, a second round pushes them to the other
 * members in the same pass. Paused nodes are only read, never changed.
 *
 * Long-lived (one per controller) so a persistent error is logged once, not every pass.
 */
class MeshReconciler(private val homelab: HomelabMesh) {
    private val domain = "mesh"
    private val lastErrors = mutableMapOf<String, String>()

    private class NodeResult(val peers: List<Mesh.PeerStatus>?, val error: String?)

    suspend fun reconcile(ctx: ReconcileContext, nodes: List<NodeConfig>, slots: Map<String, Int>): MeshStatusDto? {
        val mesh = ctx.config.cluster?.mesh ?: return null
        val members = nodes.filter { !ctx.state.node(it.id).pendingDecommission && slots[it.id] != null }
        val homelabAddress = Mesh.homelabAddress(mesh)
        val addresses = members.associate { it.id to Mesh.nodeAddress(mesh, slots.getValue(it.id)) }

        var homelabError: String? = null
        val homelabKey = try {
            homelab.publicKey()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            homelabError = "homelab mesh key: ${e.message}"
            null
        }

        fun peersFor(selfId: String): List<Mesh.Peer> = buildList {
            if (selfId != Mesh.HOMELAB && homelabKey != null) add(Mesh.Peer(Mesh.HOMELAB, homelabKey, homelabAddress, null))
            for (n in members) {
                if (n.id == selfId) continue
                val key = ctx.state.node(n.id).meshPublicKey ?: continue
                add(Mesh.Peer(n.id, key, addresses.getValue(n.id), n.endpoint()))
            }
        }

        val results = mutableMapOf<String, NodeResult>()
        for (round in 1..2) {
            val known = members.associate { it.id to ctx.state.node(it.id).meshPublicKey }
            coroutineScope {
                members.map { n -> async { n.id to configureNode(ctx, n, mesh, addresses.getValue(n.id), peersFor(n.id)) } }.awaitAll()
            }.forEach { (id, r) -> results[id] = r }
            if (members.all { ctx.state.node(it.id).meshPublicKey == known[it.id] }) break
        }

        var homelabPeers: List<Mesh.PeerStatus>? = null
        if (homelabKey != null) {
            try {
                homelab.apply(mesh, homelabAddress, peersFor(Mesh.HOMELAB))
                homelabPeers = homelab.peers()
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                homelabError = e.message ?: e.toString()
            }
        }
        report(ctx, Mesh.HOMELAB, homelabError)

        val names = buildMap {
            homelabKey?.let { put(it, Mesh.HOMELAB) }
            members.forEach { n -> ctx.state.node(n.id).meshPublicKey?.let { put(it, n.id) } }
        }
        fun view(id: String, address: String, peers: List<Mesh.PeerStatus>?, error: String?) = MeshMemberDto(
            id = id,
            address = address,
            error = error,
            peers = peers.orEmpty().map { p ->
                MeshPeerDto(
                    id = names[p.publicKey] ?: "unknown",
                    endpoint = p.endpoint,
                    lastHandshakeAt = p.latestHandshake.takeIf { it > 0 }?.let { it * 1000 },
                    rxBytes = p.rxBytes,
                    txBytes = p.txBytes,
                )
            }.sortedBy { it.id },
        )
        return MeshStatusDto(
            subnet = mesh.subnet,
            members = listOf(view(Mesh.HOMELAB, homelabAddress, homelabPeers, homelabError)) +
                members.map { n -> results[n.id].let { r -> view(n.id, addresses.getValue(n.id), r?.peers, r?.error) } },
        )
    }

    private suspend fun configureNode(ctx: ReconcileContext, node: NodeConfig, mesh: MeshConfig, address: String, peers: List<Mesh.Peer>): NodeResult {
        val paused = ctx.nodePaused(node.id)
        return try {
            val ssh = ctx.ssh.session(node)
            val script = if (paused) READ_ONLY else Mesh.nodeScript(mesh, address, Mesh.nodeConfig(mesh, address, peers), node.firewall)
            val r = ssh.runScript(script, 300)
            if (!r.ok) throw IllegalStateException(r.stderr.trim().ifEmpty { r.stdout.trim() }.takeLast(400).ifEmpty { "exit ${r.exitCode}" })
            r.stdout.lines().filter { it.startsWith("CHANGED ") }.forEach { ctx.log.change(domain, it.removePrefix("CHANGED "), node.id) }
            r.stdout.lines().firstOrNull { it.startsWith("PUBKEY=") }?.removePrefix("PUBKEY=")?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                if (ctx.state.node(node.id).meshPublicKey != key) {
                    ctx.state.updateNode(node.id) { it.copy(meshPublicKey = key) }
                    ctx.log.change(domain, "joined the mesh at $address", node.id)
                }
            }
            report(ctx, node.id, null)
            NodeResult(Mesh.parseDump(Mesh.dumpSection(r.stdout)), null)
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val message = e.message ?: e.toString()
            report(ctx, node.id, message)
            NodeResult(null, message)
        }
    }

    /** Logs a member's error when it first appears or changes, and its recovery once. */
    private fun report(ctx: ReconcileContext, id: String, error: String?) {
        val previous = lastErrors[id]
        when {
            error != null && error != previous -> {
                lastErrors[id] = error
                ctx.log.error(domain, error, id)
            }
            error == null && previous != null -> {
                lastErrors.remove(id)
                ctx.log.info(domain, "mesh is healthy again", id)
            }
        }
    }

    private fun NodeConfig.endpoint(): String = ipv4() ?: publicIpv6 ?: host

    private companion object {
        /** Status only, for paused nodes. */
        val READ_ONLY = "echo DUMP-BEGIN; wg show ${Mesh.INTERFACE} dump 2>/dev/null || true; echo DUMP-END"
    }
}
