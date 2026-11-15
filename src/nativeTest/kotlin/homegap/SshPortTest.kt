package homegap

import homegap.clients.SshPool
import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.model.Planner
import homegap.provision.FirewallRules
import homegap.provision.SshPort
import homegap.reconcile.ReconcileLog
import homegap.reconcile.SshPortMove
import homegap.state.StateStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshPortTest {
    private val git = container("forgejo", "homegap.expose" to "git.test.example.net", "homegap.port" to "22/tcp")

    @Test
    fun aServiceCannotUseTheSshPortOfANodeThatServesIt() {
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK, git))
        val refusal = plan.labelErrors.single().message
        assertTrue(refusal.startsWith("public port 22/tcp is the SSH port of node na1"), refusal)
        assertFalse(plan.services.any { it.hostname == "git.test.example.net" })
    }

    @Test
    fun port22IsFreeWhenNoNodeUsesItForSsh() {
        val moved = ConfigLoader.parse(TEST_CONFIG.replace("ssh_key_file:", "ssh_port: 2222\n    ssh_key_file:"))
        assertEquals(setOf(2222), moved.nodes.map { it.sshPort }.toSet())
        val plan = Planner.plan(moved, listOf(TRAEFIK, git))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        assertTrue(plan.services.any { it.hostname == "git.test.example.net" })

        val onThePort = container("x", "homegap.expose" to "x.test.example.net", "homegap.port" to "2222/tcp")
        val clash = Planner.plan(moved, listOf(TRAEFIK, onThePort)).labelErrors.single().message
        assertTrue(clash.startsWith("public port 2222/tcp is the SSH port of node"), clash)
        // UDP is a different port, and a private service has no public port.
        val udp = container("u", "homegap.expose" to "u.test.example.net", "homegap.port" to "2222/udp")
        assertTrue(Planner.plan(moved, listOf(TRAEFIK, udp)).labelErrors.isEmpty())
    }

    @Test
    fun theScriptNamesThePortsInOneFileAndPutsTheOldFileBack() {
        val script = SshPort.listen(listOf(22, 2222), listOf("allow 2222/tcp"))
        assertTrue($$"printf 'Port 22\\nPort 2222\\n' > \"$CONF.tmp\"" in script, script)
        assertTrue("  ufw allow 2222/tcp >/dev/null" in script)
        assertTrue("for port in 22 2222; do" in script)
        assertTrue("restore; apply" in script, "a server that does not listen gets its old file back")
        assertTrue("a different file sets the SSH port" in script)
        assertFalse("ufw allow" in SshPort.listen(listOf(2222)))
        assertFailsWith<IllegalArgumentException> { SshPort.listen(emptyList()) }
        assertFailsWith<IllegalArgumentException> { SshPort.listen(listOf(70000)) }
    }

    @Test
    fun theFirewallOpensTheSshPortOfTheNode() {
        val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k", sshPort = 2222)
        assertEquals(listOf("allow 2222/tcp"), FirewallRules.sshSpecs(node))
        assertTrue("allow 2222/tcp" in FirewallRules.specs(node))
        assertFalse("allow 22/tcp" in FirewallRules.specs(node))
        val limited = node.copy(sshAllowFrom = listOf("198.51.100.0/24"))
        assertEquals(listOf("allow from 198.51.100.0/24 to any port 2222 proto tcp"), FirewallRules.sshSpecs(limited))
    }

    @Test
    fun aPassUsesThePortOfTheServerUntilTheMoveCanRun() = runTest {
        val state = StateStore(tmpPath("ssh-port-state"))
        val move = SshPortMove(state, SshPool(tmpPath("ssh"), tmpPath("kh")), ReconcileLog())
        val config = testConfig()

        // The first pass keeps the port of the config for each node.
        assertEquals(config, move.settle(config) { null })
        assertEquals("22", state.node("na1").facts[SshPortMove.FACT])

        // A dry run and a pause do not move the server: each part of the pass stays on the old port.
        val wanted = config.copy(nodes = config.nodes.map { it.copy(sshPort = 2222) })
        val held = move.settle(wanted) { "the pause" }
        assertEquals(setOf(22), held.nodes.map { it.sshPort }.toSet())
        assertEquals("22", state.node("na1").facts[SshPortMove.FACT])
        val rules = FirewallRules.sshSpecs(held.nodes.first())
        assertEquals(listOf("allow 22/tcp"), rules, "the firewall keeps the old port")
    }
}
