package homegap.reconcile

import homegap.clients.SshPool
import homegap.config.AppConfig
import homegap.config.NodeConfig
import homegap.platform.runCatchingCancellable
import homegap.provision.FirewallRules
import homegap.provision.SshPort
import homegap.state.StateStore

/**
 * Moves the SSH server of a node to a new port when `nodes[].ssh_port` changes. The state keeps
 * the port where the server of each node listens ([FACT]). Until a move is complete, each part of
 * a pass uses that port, so the firewall never closes the port that the server still uses.
 *
 * The order of a move keeps a way in at each step:
 * 1. Over the old port, the server gets the new port too, and the firewall opens it.
 * 2. HomeGAP logs in on the new port.
 * 3. Over the new port, the server drops the old port. The firewall closes it in the same pass.
 *
 * If the login on the new port fails, the server gets its old ports back.
 */
class SshPortMove(
    private val state: StateStore,
    private val ssh: SshPool,
    private val log: ReconcileLog,
) {
    /** What stops a move in this pass: a dry run or a pause. Null if a move can run. */
    fun interface Hold {
        fun reason(nodeId: String): String?
    }

    /**
     * Returns [config] with each node at the port where its SSH server listens. It moves the
     * server of each node whose `ssh_port` changed, unless [hold] gives a reason to wait.
     */
    suspend fun settle(config: AppConfig, hold: Hold): AppConfig {
        val nodes = config.nodes.map { node -> node.copy(sshPort = settledPort(node, hold)) }
        return if (nodes == config.nodes) config else config.copy(nodes = nodes)
    }

    private suspend fun settledPort(node: NodeConfig, hold: Hold): Int {
        val current = state.node(node.id).facts[FACT]?.toIntOrNull()
        val waits = hold.reason(node.id)
        if (current == null) {
            // A node that HomeGAP sees for the first time: its server is at the port of the config.
            if (waits == null) remember(node.id, node.sshPort)
            return node.sshPort
        }
        if (current == node.sshPort) return current
        if (waits != null) {
            log.info(DOMAIN, "the SSH port stays $current, and moves to ${node.sshPort} after $waits", node.id)
            return current
        }
        val failure = runCatchingCancellable { move(node, from = current) }.exceptionOrNull()
        if (failure != null) {
            log.error(DOMAIN, "the SSH port stays $current: ${failure.message}", node.id)
            return current
        }
        remember(node.id, node.sshPort)
        log.change(DOMAIN, "moved the SSH server from port $current to port ${node.sshPort}", node.id)
        return node.sshPort
    }

    private suspend fun move(node: NodeConfig, from: Int) {
        val probe = ssh.probe(node)
        try {
            val old = ssh.session(node.copy(sshPort = from))
            val both = SshPort.listen(listOf(from, node.sshPort), FirewallRules.sshSpecs(node))
            val opened = runCatchingCancellable { old.runScriptOrThrow(STEP, both, SCRIPT_TIMEOUT_SECONDS) }
            if (opened.isFailure) {
                // A move that stopped after its last step left the server on the new port only.
                if (runCatchingCancellable { probe.runScriptOrThrow(STEP, CHECK) }.isSuccess) return
                throw opened.exceptionOrNull()!!
            }
            val reached = runCatchingCancellable { probe.runScriptOrThrow(STEP, CHECK) }
            if (reached.isFailure) {
                val back = SshPort.listen(listOf(from))
                runCatchingCancellable { old.runScriptOrThrow(STEP, back, SCRIPT_TIMEOUT_SECONDS) }
                val cause = reached.exceptionOrNull()?.message
                error("no login on port ${node.sshPort} ($cause). The server is on port $from again")
            }
            // The new port works. A failure here leaves both ports, and the next pass closes the old one.
            runCatchingCancellable {
                probe.runScriptOrThrow(STEP, SshPort.listen(listOf(node.sshPort)), SCRIPT_TIMEOUT_SECONDS)
            }.onFailure { log.warn(DOMAIN, "the SSH server still listens on port $from too: ${it.message}", node.id) }
        } finally {
            probe.close()
            ssh.drop(node.id)
        }
    }

    private suspend fun remember(nodeId: String, port: Int) {
        state.updateNode(nodeId) { it.copy(facts = it.facts + (FACT to port.toString())) }
    }

    companion object {
        /** The fact of a node with the port where its SSH server listens. */
        const val FACT = "ssh.port"
        private const val DOMAIN = "nodes"
        private const val STEP = "ssh-port"
        private const val CHECK = "true"
        private const val SCRIPT_TIMEOUT_SECONDS = 90
    }
}
