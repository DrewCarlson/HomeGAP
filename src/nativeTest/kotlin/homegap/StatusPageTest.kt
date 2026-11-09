package homegap

import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.config.ConfigLoader
import homegap.model.Planner
import homegap.provision.NodeTemplates
import homegap.status.PublicStatus
import homegap.status.StatusPage
import kotlin.test.*

class StatusPageTest {
    private val cfg = ConfigLoader.parse(TEST_CONFIG + "\nstatus_page:\n  enabled: true\n  title: Example status\n")

    @Test
    fun sharedNameIsAnsweredByEveryNodeAndPerNodeNamesPointAtOne() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK))
        val shared = plan.dns.filter { it.fqdn == "status.test.example.net" }
        // Its own geo set even under the wildcard, so the node that answers is the nearest one.
        assertEquals(setOf("na1", "as1"), shared.map { it.nodeId }.toSet())
        assertTrue(shared.all { it.geo })
        val own = plan.dns.filter { it.purpose == "status" && it.fqdn != "status.test.example.net" }
        assertEquals(setOf("status-na1.test.example.net", "status-as1.test.example.net"), own.map { it.fqdn }.toSet())
        assertEquals("203.0.113.10", own.single { it.fqdn.startsWith("status-na1") && it.type == "A" }.value)
        assertTrue(own.none { it.geo })
    }

    @Test
    fun passthroughLeavesTheSharedNameToTheNode() {
        val n = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("as1")
        assertTrue("status.test.example.net" in n.sniRoutes.single().excludeHosts)
        assertEquals("status.test.example.net" to "status-as1.test.example.net", n.statusRedirect)
        val routes = NodeTemplates.dynamicRoutes(n, emptyMap())
        assertTrue("Host(`status.test.example.net`)" in routes)
        assertTrue("https://status-as1.test.example.net/" in routes)
        assertTrue("!HostSNI(`status.test.example.net`)" in NodeTemplates.sniRule(n.sniRoutes.single()))
    }

    @Test
    fun nonWildcardFrontDoorCarriesThePerNodeName() {
        val fd = container("traefik", "homegap.expose" to "test.example.net", "homegap.protocol" to "https", "homegap.tls" to "passthrough")
        val hosts = Planner.plan(cfg, listOf(fd)).nodes.getValue("na1").sniRoutes.single().exactHosts
        assertTrue("status-na1.test.example.net" in hosts)
        assertFalse("status.test.example.net" in hosts)
    }

    @Test
    fun terminateModeGetsAPublicResourcePerNode() {
        val fd = container("traefik", "homegap.expose" to "test.example.net", "homegap.wildcard" to "true", "homegap.protocol" to "https", "homegap.tls" to "terminate")
        val res = Planner.plan(cfg, listOf(fd)).nodes.getValue("na1").publicResources.single { it.niceId == Planner.STATUS }
        assertEquals("status-na1.test.example.net", res.fullDomain)
        assertEquals(homegap.discovery.AuthMode.NONE, res.auth)
    }

    @Test
    fun offWhenDisabledOrWithoutAFrontDoor() {
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK))
        assertTrue(plan.dns.none { it.purpose == "status" })
        assertNull(plan.nodes.getValue("na1").statusRedirect)
        assertTrue(Planner.plan(cfg, emptyList()).dns.none { it.purpose == "status" })
    }

    private fun node(id: String, health: Health = Health.OK, tunnel: Boolean = true, paused: Boolean = false) =
        NodeStatusDto(id = id, region = "", provider = "p", host = "203.0.113.99", managementHost = "pangolin-$id.test.example.net", health = health, reachable = true, tunnelUp = tunnel, paused = paused)

    @Test
    fun visitorsSeeStatesAndTheirNodeButNoAddresses() {
        val status = PublicStatus({ cfg }, { listOf(node("na1"), node("as1", health = Health.DEGRADED)) })
        val s = status.build("status-as1.test.example.net")
        assertEquals("as1", s.via)
        assertEquals("Example status", s.title)
        assertEquals(listOf("as1", "na1"), s.nodes.map { it.id }, "the visitor's node first")
        assertEquals("degraded", s.nodes.first().state)
        assertEquals("https://status-na1.test.example.net/status/ping", s.nodes.single { it.id == "na1" }.pingUrl)
        val json = kotlinx.serialization.json.Json.encodeToString(homegap.status.PublicStatusDto.serializer(), s)
        assertFalse("203.0.113" in json || "2001:db8" in json || "pangolin-" in json, json)
    }

    @Test
    fun aNodeWithoutItsTunnelIsOfflineAndNotPinged() {
        val status = PublicStatus({ cfg }, { listOf(node("na1", tunnel = false), node("as1", paused = true)) })
        val s = status.build("status.test.example.net")
        assertNull(s.via)
        assertTrue(s.nodes.all { it.state == PublicStatus.OFFLINE && it.pingUrl == null })
        assertEquals(PublicStatus.ONLINE, PublicStatus.stateOf(node("x", health = Health.UNKNOWN)))
    }

    @Test
    fun servesOnlyItsOwnNames() {
        val status = PublicStatus({ cfg }, { emptyList() })
        assertTrue(status.serves("STATUS.test.example.net"))
        assertTrue(status.serves("status-na1.test.example.net"))
        assertFalse(status.serves("status-zz9.test.example.net"))
        assertFalse(status.serves("speedtest-na1.test.example.net"))
        assertFalse(PublicStatus({ testConfig() }, { emptyList() }).serves("status.test.example.net"))
    }

    @Test
    fun configIsValidated() {
        assertFailsWith<Exception> { ConfigLoader.parse(TEST_CONFIG + "\nstatus_page:\n  enabled: true\n  hostname: Status Page\n") }
        assertFailsWith<Exception> { ConfigLoader.parse(TEST_CONFIG + "\nspeedtest:\n  enabled: true\nstatus_page:\n  enabled: true\n  hostname: speedtest\n") }
    }

    @Test
    fun pageHasNoExternalAssets() {
        assertFalse(Regex("""(src|href)=["']https?:""").containsMatchIn(StatusPage.HTML))
        assertTrue("/status/nodes" in StatusPage.HTML && "/status/ping" !in StatusPage.HTML.substringBefore("<script>"))
    }
}
