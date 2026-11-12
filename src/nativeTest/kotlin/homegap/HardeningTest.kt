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

    @Test
    fun theTraefikApiLabelNamesOnlyALocalHost() {
        for (ok in listOf("http://traefik:8080", "https://homelab_traefik-1", "http://10.0.3.4:9000", "http://172.20.0.5", "http://192.168.1.9:8080")) {
            assertTrue(homegap.discovery.isLocalApiUrl(ok), ok)
        }
        for (bad in listOf(
            "http://example.net", "http://169.254.169.254", "http://127.0.0.1:8088", "http://8.8.8.8", "http://172.32.0.1",
            "http://traefik:8080/api", "http://user@traefik", "http://traefik:99999", "ftp://traefik", "http://traefik?x=1",
        )) {
            assertTrue(!homegap.discovery.isLocalApiUrl(bad), bad)
        }
        val labels = mapOf("homegap.traefik_api" to "http://example.net/")
        assertEquals("http://front:8080", homegap.discovery.traefikApiUrl("front", labels))
        assertEquals("http://other:9000", homegap.discovery.traefikApiUrl("front", mapOf("homegap.traefik_api" to "http://other:9000/")))
        val parsed = homegap.discovery.parseContainerLabels("id", "front", labels + ("homegap.expose" to "app.test.example.net"), "test.example.net")
        assertTrue(parsed.warnings.any { "homegap.traefik_api must be" in it }, parsed.warnings.toString())
    }

    @Test
    fun aDeviceLinkHasACapOnStreamsBytesAndResults() {
        val tokens = homegap.speedtest.SpeedTestTokens()
        val (link, _) = tokens.issue(60_000)
        repeat(homegap.speedtest.SpeedTestTokens.STREAMS_PER_LINK) { assertTrue(tokens.acquire(link)) }
        assertTrue(!tokens.acquire(link), "the link has no stream left")
        tokens.release(link)
        assertTrue(tokens.acquire(link))
        assertTrue(tokens.spend(link, 600, budget = 1000))
        assertTrue(!tokens.spend(link, 600, budget = 1000), "the budget is used")
        repeat(homegap.speedtest.SpeedTestTokens.RESULTS_PER_LINK) { assertTrue(tokens.result(link)) }
        assertTrue(!tokens.result(link))
        assertTrue(!tokens.acquire("unknown") && !tokens.spend(null, 1, 10))

        // The other links share the streams of the listener.
        val others = List(4) { tokens.issue(60_000).first }
        val taken = others.sumOf { t -> (1..homegap.speedtest.SpeedTestTokens.STREAMS_PER_LINK).count { tokens.acquire(t) } }
        assertEquals(homegap.speedtest.SpeedTestTokens.STREAMS_TOTAL - homegap.speedtest.SpeedTestTokens.STREAMS_PER_LINK, taken)

        // A test from the homelab to a node has no cap.
        val (internal, _) = tokens.issue(60_000, limited = false)
        repeat(homegap.speedtest.TunnelSpeedTest.MAX_STREAMS) { assertTrue(tokens.acquire(internal)) }
        assertTrue(tokens.spend(internal, 5000, budget = 1000))
    }
}
