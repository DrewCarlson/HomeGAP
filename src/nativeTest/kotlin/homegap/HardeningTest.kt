package homegap

import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.model.Planner
import homegap.provision.FirewallRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HardeningTest {
    private fun node(host: String = "203.0.113.30", user: String = "root", mgmt: String? = null) =
        NodeConfig(id = "eu1", host = host, sshUser = user, sshKeyFile = "/k", managementHost = mgmt)

    @Test
    fun nodeFieldsThatGoIntoScriptsAreChecked() {
        assertTrue(ConfigLoader.nodeErrors(node()).isEmpty())
        assertTrue(ConfigLoader.nodeErrors(node(host = "eu1.example.net", mgmt = "pangolin-eu1.example.net")).isEmpty())
        assertTrue(ConfigLoader.nodeErrors(node(host = "2001:db8::30")).isEmpty())
        assertEquals(1, ConfigLoader.nodeErrors(node(host = "a b")).size)
        assertEquals(1, ConfigLoader.nodeErrors(node(user = "root; reboot")).size)
        assertEquals(1, ConfigLoader.nodeErrors(node(mgmt = "x`) || Host(`y")).size)
    }

    @Test
    fun badIntervalsFailTheConfig() {
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse("$TEST_CONFIG\nreconcile:\n  resync_interval: 0s\n") }
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse("$TEST_CONFIG\nreconcile:\n  dns_check_interval: 5 mins\n") }
    }

    @Test
    fun aLabelCannotClaimAHostnameOfHomeGAP() {
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK, container("app", "homegap.expose" to "pangolin-na1.test.example.net")))
        assertTrue(plan.labelErrors.any { "reserved" in it.message })
        assertTrue(plan.services.none { it.hostname == "pangolin-na1.test.example.net" })
    }

    @Test
    fun sshSourcesAreWrittenAsUfwPrintsThem() {
        val specs = FirewallRules.specs(node().copy(sshAllowFrom = listOf("198.51.100.7/32", "2001:DB8::7/128", "198.51.100.0/24")))
        assertTrue("allow from 198.51.100.7 to any port 22 proto tcp" in specs)
        assertTrue("allow from 2001:db8::7 to any port 22 proto tcp" in specs)
        assertTrue("allow from 198.51.100.0/24 to any port 22 proto tcp" in specs)
    }
}
