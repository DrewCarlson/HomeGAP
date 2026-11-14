package homegap

import homegap.config.ConfigLoader
import homegap.config.NodeConfig
import homegap.discovery.isLocalApiUrl
import homegap.discovery.parseContainerLabels
import homegap.discovery.traefikApiUrl
import homegap.model.Planner
import homegap.provision.FirewallRules
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.speedtest.RateLimiter
import homegap.speedtest.SpeedTestTokens
import homegap.speedtest.TunnelSpeedTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HardeningTest {
    private fun node(host: String = "203.0.113.30", user: String = "root", managementHost: String? = null) =
        NodeConfig(id = "eu1", host = host, sshUser = user, sshKeyFile = "/k", managementHost = managementHost)

    private fun crowdsecFile(failClosed: Boolean): String {
        val base = testConfig()
        val config = base.copy(pangolin = base.pangolin.copy(crowdsec = true, crowdsecFailClosed = failClosed))
        val desired = Planner.plan(config, listOf(TRAEFIK)).nodes.getValue("na1")
        val secrets = NodeSecrets("t", "s", "a@b", "p", "k")
        return NodeTemplates.staticFiles(config, desired.node, secrets, desired.rawPorts, slot = 0)
            .single { it.path.endsWith("homegap-crowdsec.yml") }
            .content
    }

    @Test
    fun nodeFieldsThatGoIntoScriptsAreChecked() {
        assertTrue(ConfigLoader.nodeErrors(node()).isEmpty())
        val named = node(host = "eu1.example.net", managementHost = "pangolin-eu1.example.net")
        assertTrue(ConfigLoader.nodeErrors(named).isEmpty())
        assertTrue(ConfigLoader.nodeErrors(node(host = "2001:db8::30")).isEmpty())
        assertEquals(1, ConfigLoader.nodeErrors(node(host = "a b")).size)
        assertEquals(1, ConfigLoader.nodeErrors(node(user = "root; reboot")).size)
        assertEquals(1, ConfigLoader.nodeErrors(node(managementHost = "x`) || Host(`y")).size)
    }

    @Test
    fun badIntervalsFailTheConfig() {
        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.parse("$TEST_CONFIG\nreconcile:\n  resync_interval: 0s\n")
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigLoader.parse("$TEST_CONFIG\nreconcile:\n  dns_check_interval: 5 mins\n")
        }
    }

    @Test
    fun aLabelCannotClaimAHostnameOfHomeGAP() {
        val app = container("app", "homegap.expose" to "pangolin-na1.test.example.net")
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK, app))
        assertTrue(plan.labelErrors.any { "reserved" in it.message })
        assertTrue(plan.services.none { it.hostname == "pangolin-na1.test.example.net" })
    }

    @Test
    fun sshSourcesAreWrittenAsUfwPrintsThem() {
        val sources = listOf("198.51.100.7/32", "2001:DB8::7/128", "198.51.100.0/24")
        val specs = FirewallRules.specs(node().copy(sshAllowFrom = sources))
        assertTrue("allow from 198.51.100.7 to any port 22 proto tcp" in specs)
        assertTrue("allow from 2001:db8::7 to any port 22 proto tcp" in specs)
        assertTrue("allow from 198.51.100.0/24 to any port 22 proto tcp" in specs)
    }

    // The plain HTTP addresses are the values under test.
    @Suppress("HttpUrlsUsage")
    @Test
    fun theTraefikApiLabelNamesOnlyALocalHost() {
        val local = listOf(
            "http://traefik:8080",
            "https://homelab_traefik-1",
            "http://10.0.3.4:9000",
            "http://172.20.0.5",
            "http://192.168.1.9:8080",
        )
        for (ok in local) {
            assertTrue(isLocalApiUrl(ok), ok)
        }
        val notLocal = listOf(
            "http://example.net",
            "http://169.254.169.254",
            "http://127.0.0.1:8088",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://traefik:8080/api",
            "http://user@traefik",
            "http://traefik:99999",
            "ftp://traefik",
            "http://traefik?x=1",
        )
        for (bad in notLocal) {
            assertTrue(!isLocalApiUrl(bad), bad)
        }
        val labels = mapOf("homegap.traefik_api" to "http://example.net/")
        assertEquals("http://front:8080", traefikApiUrl("front", labels))
        assertEquals(
            "http://other:9000",
            traefikApiUrl("front", mapOf("homegap.traefik_api" to "http://other:9000/")),
        )
        val parsed = parseContainerLabels(
            "id",
            "front",
            labels + ("homegap.expose" to "app.test.example.net"),
            listOf("test.example.net"),
        )
        assertTrue(parsed.warnings.any { "homegap.traefik_api must be" in it }, parsed.warnings.toString())
    }

    @Test
    fun aDeviceLinkHasACapOnStreamsBytesAndResults() {
        val tokens = SpeedTestTokens()
        val (link, _) = tokens.issue(60_000)
        repeat(SpeedTestTokens.STREAMS_PER_LINK) { assertTrue(tokens.acquire(link)) }
        assertTrue(!tokens.acquire(link), "the link has no stream left")
        tokens.release(link)
        assertTrue(tokens.acquire(link))
        assertTrue(tokens.spend(link, 600, budget = 1000))
        assertTrue(!tokens.spend(link, 600, budget = 1000), "the budget is used")
        repeat(SpeedTestTokens.RESULTS_PER_LINK) { assertTrue(tokens.result(link)) }
        assertTrue(!tokens.result(link))
        assertTrue(!tokens.acquire("unknown") && !tokens.spend(token = null, n = 1, budget = 10))

        // The other links share the streams of the listener.
        val others = List(4) { tokens.issue(60_000).first }
        val taken = others.sumOf { other -> (1..SpeedTestTokens.STREAMS_PER_LINK).count { tokens.acquire(other) } }
        assertEquals(SpeedTestTokens.STREAMS_TOTAL - SpeedTestTokens.STREAMS_PER_LINK, taken)

        // A test from the homelab to a node has no cap.
        val (internal, _) = tokens.issue(60_000, limited = false)
        repeat(TunnelSpeedTest.MAX_STREAMS) { assertTrue(tokens.acquire(internal)) }
        assertTrue(tokens.spend(internal, 5000, budget = 1000))
    }

    @Test
    fun aKeyGetsItsLimitInEachWindow() {
        var now = 0L
        val limiter = RateLimiter(limit = 3, windowMillis = 1000, now = { now })
        repeat(3) { assertTrue(limiter.allow("a")) }
        assertTrue(!limiter.allow("a"), "the fourth request in the window")
        assertTrue(limiter.allow("b"), "another address has its own count")
        now = 1000
        assertTrue(limiter.allow("a"), "a new window")
    }

    @Test
    fun nodesSendHstsAndLimitTheStatusPage() {
        val config = testConfig().copy(statusPage = testConfig().statusPage.copy(enabled = true))
        val plan = Planner.plan(config, listOf(TRAEFIK))
        val desired = plan.nodes.getValue("na1")
        val secrets = NodeSecrets("t", "s", "a@b", "p", "k")
        val files = NodeTemplates.staticFiles(config, desired.node, secrets, desired.rawPorts, slot = 0)
            .associateBy { it.path }
        val headers = files.getValue(NodeTemplates.DYNAMIC_HEADERS).content
        assertTrue("stsSeconds: 31536000" in headers, headers)
        assertTrue("includeSubdomains" !in headers.lowercase(), "a node sets no rule for other names")
        assertTrue("homegap-hsts@file" in files.getValue("config/traefik/traefik_config.yml").content)
        val routes = NodeTemplates.dynamicRoutes(desired, emptyMap())
        assertTrue("homegap-status-rate" in routes && "rateLimit" in routes, routes)
        // Without the status page, there is no limit to define.
        val off = Planner.plan(testConfig(), listOf(TRAEFIK)).nodes.getValue("na1")
        assertTrue("homegap-status-rate" !in NodeTemplates.dynamicRoutes(off, emptyMap()))
    }

    @Test
    fun crowdsecCanRefuseRequestsWhenItsCheckIsDown() {
        assertTrue("crowdsecAppsecUnreachableBlock: false" in crowdsecFile(failClosed = false))
        assertTrue("crowdsecAppsecUnreachableBlock: true" in crowdsecFile(failClosed = true))
    }
}
