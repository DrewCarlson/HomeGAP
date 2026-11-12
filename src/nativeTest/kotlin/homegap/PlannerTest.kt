package homegap

import homegap.model.Planner
import homegap.model.ResourceMode
import homegap.provision.NodeTemplates
import kotlin.test.*

class PlannerTest {
    private val cfg = testConfig()

    @Test
    fun passthroughFrontDoorBecomesTcpResourcePlusSniRoute() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK))
        assertEquals("traefik", plan.frontDoor?.containerName)
        val n = plan.nodes.getValue("na1")
        val fd = n.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }
        assertEquals(ResourceMode.TCP, fd.mode)
        assertEquals("traefik", fd.target.host)
        assertEquals(443, fd.target.port)
        assertTrue(fd.proxyPort!! in 40000 until 45000)
        val route = n.sniRoutes.single()
        assertEquals(Planner.FRONTDOOR_TCP, route.resourceNiceId)
        assertEquals("test.example.net", route.wildcardOf)
        assertTrue("pangolin-na1.test.example.net" in route.excludeHosts, "management host must not be swallowed by the wildcard")
        // health resource probes through the tunnel to the front door
        val health = n.publicResources.single { it.niceId == Planner.HEALTH }
        assertEquals("https", health.target.method)
        assertEquals("test.example.net", health.hostHeader)
        assertTrue(n.rawPorts.isEmpty())
    }

    @Test
    fun terminateFrontDoorBecomesRootAndWildcardHttpResources() {
        val t = container("traefik", "homegap.expose" to "test.example.net", "homegap.wildcard" to "true", "homegap.protocol" to "https", "homegap.tls" to "terminate")
        val n = Planner.plan(cfg, listOf(t)).nodes.getValue("na1")
        val domains = n.publicResources.filter { it.mode == ResourceMode.HTTP && it.niceId != Planner.HEALTH }.map { it.fullDomain }.toSet()
        assertEquals(setOf("test.example.net", "*.test.example.net"), domains)
        assertTrue(n.sniRoutes.isEmpty())
        assertTrue(n.publicResources.filter { it.niceId != Planner.HEALTH }.all { it.target.method == "https" })
    }

    @Test
    fun dedicatedTerminateHostIsExcludedFromWildcardPassthrough() {
        val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "8080")
        val n = Planner.plan(cfg, listOf(TRAEFIK, wiki)).nodes.getValue("na1")
        assertTrue("wiki.test.example.net" in n.sniRoutes.single().excludeHosts)
        val r = n.publicResources.single { it.fullDomain == "wiki.test.example.net" }
        assertEquals("http", r.target.method)
        assertEquals("wiki", r.target.host)
    }

    @Test
    fun dedicatedPassthroughGetsHigherPrioritySniRoute() {
        val git = container("git", "homegap.expose" to "git.test.example.net", "homegap.protocol" to "https")
        val n = Planner.plan(cfg, listOf(TRAEFIK, git)).nodes.getValue("na1")
        val gitRoute = n.sniRoutes.single { it.exactHosts == listOf("git.test.example.net") }
        val fd = n.sniRoutes.single { it.wildcardOf != null }
        assertTrue(gitRoute.priority > fd.priority)
        assertNotEquals(
            n.publicResources.single { it.niceId == gitRoute.resourceNiceId }.proxyPort,
            n.publicResources.single { it.niceId == fd.resourceNiceId }.proxyPort,
        )
    }

    @Test
    fun rawUdpServicePublishesPortOnlyOnItsRegions() {
        val game = container("game", "homegap.expose" to "game.test.example.net", "homegap.port" to "27015", "homegap.protocol" to "udp", "homegap.regions" to "na1")
        val plan = Planner.plan(cfg, listOf(TRAEFIK, game))
        assertEquals(listOf("udp-27015"), plan.nodes.getValue("na1").rawPorts.map { it.entryPoint })
        assertTrue(plan.nodes.getValue("as1").rawPorts.isEmpty())
        assertEquals(listOf("na1"), plan.dns.filter { it.fqdn == "game.test.example.net" }.map { it.nodeId })
    }

    @Test
    fun privateServicesGetPrivateResourcesAndNoPublicDns() {
        val ssh = container("ssh-jump", "homegap.expose" to "ssh.test.example.net", "homegap.port" to "22", "homegap.protocol" to "tcp", "homegap.access" to "private")
        val plan = Planner.plan(cfg, listOf(TRAEFIK, ssh))
        val p = plan.nodes.getValue("na1").privateResources.single()
        assertEquals("ssh.test.example.net", p.alias)
        assertEquals("22", p.tcpPorts)
        assertEquals("ssh-jump", p.destination)
        assertTrue(plan.dns.none { it.fqdn == "ssh.test.example.net" })
        assertTrue(plan.nodes.getValue("na1").rawPorts.isEmpty())
    }

    @Test
    fun dnsHasGeoSetsAndManagementRecords() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK))
        val root = plan.dns.filter { it.fqdn == "test.example.net" && it.type != "CAA" }
        assertEquals(setOf("na1", "as1"), root.map { it.nodeId }.toSet())
        assertTrue(root.all { it.geo && it.monitor == "http" && it.name == "test" })
        val wildcard = plan.dns.filter { it.name == "*.test" }
        assertEquals(3, wildcard.size, "A for both nodes + AAAA for as1")
        val na1 = root.single { it.nodeId == "na1" }
        assertEquals(45.5, na1.latitude)
        val as1 = root.single { it.nodeId == "as1" && it.type == "A" }
        assertNotNull(as1.latitude, "region code AS falls back to a centroid")
        val mgmt = plan.dns.filter { it.purpose == "management" }
        assertEquals(setOf("pangolin-na1.test", "pangolin-as1.test"), mgmt.map { it.name }.toSet())
        assertTrue(mgmt.none { it.geo })
    }

    @Test
    fun duplicateHostnamesAreReported() {
        val a = container("a", "homegap.expose" to "x.test.example.net")
        val b = container("b", "homegap.expose" to "x.test.example.net")
        val plan = Planner.plan(cfg, listOf(a, b))
        assertEquals(1, plan.services.size)
        assertTrue(plan.labelErrors.any { it.container == "b" && "already claimed" in it.message })
    }

    @Test
    fun portAllocationIsDeterministicAndCollisionFree() {
        val keys = (1..300).map { "hg-svc-$it" }
        val a = Planner.allocatePorts(keys, 40000, setOf(41000))
        val b = Planner.allocatePorts(keys.reversed(), 40000, setOf(41000))
        assertEquals(a, b)
        assertEquals(keys.size, a.values.toSet().size)
        assertFalse(41000 in a.values)
    }

    @Test
    fun sniRuleShape() {
        val n = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("na1")
        val rule = NodeTemplates.sniRule(n.sniRoutes.single())
        assertTrue(rule.startsWith("(HostSNI(`test.example.net`) || HostSNIRegexp(`(?i)^"))
        assertTrue(rule.endsWith("&& !HostSNI(`pangolin-na1.test.example.net`)"))
        val re = Regex(NodeTemplates.wildcardRegex("test.example.net").removePrefix("(?i)"), RegexOption.IGNORE_CASE)
        assertTrue(re.matches("media.test.example.net"))
        assertFalse(re.matches("a.b.test.example.net"), "wildcards match a single label only")
        assertFalse(re.matches("test.example.net"))
    }
}
