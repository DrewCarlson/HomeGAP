package homegap

import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.platform.ProcessResult
import homegap.provision.FirewallRules
import homegap.setup.SetupScripts
import homegap.setup.SetupShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeSetupTest {
    private val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k", sshPort = 2222)

    @Test
    fun sshIsOpenOrLimitedToTheListedSources() {
        assertEquals("allow 2222/tcp", FirewallRules.specs(node).first())
        val limited = FirewallRules.specs(node.copy(sshAllowFrom = listOf("198.51.100.7", "10.0.0.0/8")))
        assertEquals(
            listOf("allow from 198.51.100.7 to any port 2222 proto tcp", "allow from 10.0.0.0/8 to any port 2222 proto tcp"),
            limited.take(2),
        )
        assertTrue(limited.none { it == "allow 2222/tcp" }, "no open SSH rule once sources are listed")
        assertTrue("allow 51820/udp" in limited && "allow 443/tcp" in limited)
    }

    @Test
    fun firewallScriptAddsBeforeItRemoves() {
        val script = FirewallRules.script(FirewallRules.specs(node))
        assertTrue(script.indexOf("comment homegap") < script.indexOf("ufw delete"), "new rules exist before old ones go")
        assertTrue("allow 2222/tcp" in script)
    }

    @Test
    fun rejectsInvalidSshSources() {
        val config = ConfigLoader.parse(TEST_CONFIG)
        val bad = config.copy(nodes = config.nodes.map { it.copy(sshAllowFrom = listOf("not-an-ip")) })
        assertFailsWith<IllegalArgumentException> { ConfigLoader.validate(bad) }.also { assertTrue("ssh_allow_from" in it.message!!) }
    }

    @Test
    fun explainsSshFailures() {
        fun error(stderr: String) = SetupShell.connectionError(ProcessResult(255, "", stderr))
        assertEquals("Login rejected: wrong user, password or key.", error("root@x: Permission denied (publickey,password)."))
        assertEquals(SetupShell.HOST_KEY_CHANGED, error("@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@"))
        assertTrue("did not answer" in error("ssh: connect to host x port 22: Operation timed out")!!)
        assertNull(SetupShell.connectionError(ProcessResult(1, "", "sudo: a password is required")), "remote command failures are not connection errors")
        assertEquals(SetupShell.DROPPED, error(""), "a connection cut without a message is still explained")
        assertEquals(SetupShell.DROPPED, error("kex_exchange_identification: Connection closed by remote host"))
        assertTrue(SetupShell.isDropped(error("ssh: connect to host x port 22: Connection timed out")!!))
        assertTrue(!SetupShell.isDropped(SetupShell.LOGIN_REJECTED))
        assertEquals(SetupShell.STALLED, error("Connection timed out during banner exchange\nConnection to 203.0.113.30 port 22 timed out"))
        assertEquals(SetupShell.STALLED, error("\n[homegap] process timed out after 30s"), "a login killed by our timeout connected first")
        assertTrue(SetupShell.isDropped(SetupShell.STALLED))
    }

    @Test
    fun probeReportsWhereSshdTookTheConnection() {
        assertTrue("SERVER_ADDR=" in SetupScripts.PROBE && "SERVER_PORT=" in SetupScripts.PROBE)
    }

    @Test
    fun confirmFailsWhenARevertTimerAlreadyFired() {
        val script = SetupScripts.confirm(listOf("ufw", "ssh"))
        assertTrue("[ -f /run/homegap-setup-ufw.armed ] ||" in script && "[ -f /run/homegap-setup-ssh.armed ] ||" in script)
        assertTrue(script.indexOf("exit 1") < script.indexOf("rm -f"), "every timer is checked before any is cancelled")
    }

    @Test
    fun exemptsTheOrchestratorFromLoginBlockers() {
        val script = SetupScripts.exemptFromBlockers("198.51.100.7")
        assertTrue("IP=198.51.100.7" in script)
        assertTrue("fail2ban-client" in script && "sshguard" in script && "cscli decisions delete" in script)
        assertTrue("unbanip" in script, "a current ban is lifted")
        assertTrue(script.trimEnd().endsWith("exit 0"), "best effort: never fails the step")
        assertTrue(SetupScripts.exemptFromBlockers("2001:db8::7").contains("IP=2001:db8::7"))
        assertFailsWith<IllegalArgumentException> { SetupScripts.exemptFromBlockers("198.51.100.7; reboot") }
    }

    @Test
    fun hardeningVerifiesTheEffectiveSettingsAndArmsARevert() {
        val script = SetupScripts.hardenSsh(disableRootLogin = true, disablePasswordLogin = true, listOf("root", "homegap"), "198.51.100.7")
        assertTrue(SetupScripts.SSHD_DROP_IN in script)
        assertTrue("PermitRootLogin no" in script && "PasswordAuthentication no" in script)
        assertTrue("sshd -t" in script, "config is syntax-checked")
        assertTrue("for who in root homegap" in script && "addr=198.51.100.7" in script, "effect confirmed per user, for the orchestrator's address")
        assertTrue("addr=127.0.0.1" in SetupScripts.hardenSsh(true, true, listOf("root"), "not an ip"), "an unusable address is never put in the script")
        assertTrue("sleep ${SetupScripts.REVERT_AFTER_SECONDS}" in script, "a revert timer is armed")
        assertTrue(script.indexOf("sleep ${SetupScripts.REVERT_AFTER_SECONDS}") < script.lastIndexOf("reload"), "armed before the reload")
    }
}
