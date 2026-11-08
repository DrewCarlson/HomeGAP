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
    }

    @Test
    fun hardeningVerifiesTheEffectiveSettingsAndArmsARevert() {
        val script = SetupScripts.hardenSsh(disableRootLogin = true, disablePasswordLogin = true)
        assertTrue(SetupScripts.SSHD_DROP_IN in script)
        assertTrue("PermitRootLogin no" in script && "PasswordAuthentication no" in script)
        assertTrue("sshd -t" in script && "sshd -T" in script, "config is syntax-checked and its effect confirmed")
        assertTrue("sleep ${SetupScripts.REVERT_AFTER_SECONDS}" in script, "a revert timer is armed")
        assertTrue(script.indexOf("sleep ${SetupScripts.REVERT_AFTER_SECONDS}") < script.lastIndexOf("reload"), "armed before the reload")
    }
}
