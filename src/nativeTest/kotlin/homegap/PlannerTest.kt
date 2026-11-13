package homegap

import homegap.model.Planner
import homegap.model.ResourceMode
import homegap.provision.NodeTemplates
import kotlin.test.*

class PlannerTest {
    private val config = testConfig()

    @Test
    fun passthroughFrontDoorBecomesTcpResourcePlusSniRoute() {
        val plan = Planner.plan(config, listOf(TRAEFIK))
        assertEquals("traefik", plan.frontDoor?.containerName)
        val node = plan.nodes.getValue("na1")
        val frontDoor = node.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }
        assertEquals(ResourceMode.TCP, frontDoor.mode)
        assertEquals("traefik", frontDoor.target.host)
        assertEquals(443, frontDoor.target.port)
        assertTrue(frontDoor.proxyPort!! in 40000 until 45000)
        val route = node.sniRoutes.single()
        assertEquals(Planner.FRONTDOOR_TCP, route.resourceNiceId)
        assertEquals("test.example.net", route.wildcardOf)
        assertTrue(
            "pangolin-na1.test.example.net" in route.excludeHosts,
            "management host must not be swallowed by the wildcard",
        )
        // The health resource probes the front door through the tunnel.
        val health = node.publicResources.single { it.niceId == Planner.HEALTH }
        assertEquals("https", health.target.method)
        assertEquals("test.example.net", health.hostHeader)
        assertTrue(node.rawPorts.isEmpty())
    }

    @Test
    fun terminateFrontDoorBecomesRootAndWildcardHttpResources() {
        val terminate = container(
            "traefik",
            "homegap.expose" to "test.example.net",
            "homegap.wildcard" to "true",
            "homegap.protocol" to "https",
            "homegap.tls" to "terminate",
        )
        val node = Planner.plan(config, listOf(terminate)).nodes.getValue("na1")
        val domains = node.publicResources
            .filter { it.mode == ResourceMode.HTTP && it.niceId != Planner.HEALTH }
            .map { it.fullDomain }
            .toSet()
        assertEquals(setOf("test.example.net", "*.test.example.net"), domains)
        assertTrue(node.sniRoutes.isEmpty())
        assertTrue(node.publicResources.filter { it.niceId != Planner.HEALTH }.all { it.target.method == "https" })
    }

    @Test
    fun dedicatedTerminateHostIsExcludedFromWildcardPassthrough() {
        val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "8080")
        val node = Planner.plan(config, listOf(TRAEFIK, wiki)).nodes.getValue("na1")
        assertTrue("wiki.test.example.net" in node.sniRoutes.single().excludeHosts)
        val resource = node.publicResources.single { it.fullDomain == "wiki.test.example.net" }
        assertEquals("http", resource.target.method)
        assertEquals("wiki", resource.target.host)
    }

    @Test
    fun dedicatedPassthroughGetsHigherPrioritySniRoute() {
        val git = container("git", "homegap.expose" to "git.test.example.net", "homegap.protocol" to "https")
        val node = Planner.plan(config, listOf(TRAEFIK, git)).nodes.getValue("na1")
        val gitRoute = node.sniRoutes.single { it.exactHosts == listOf("git.test.example.net") }
        val frontDoorRoute = node.sniRoutes.single { it.wildcardOf != null }
        assertTrue(gitRoute.priority > frontDoorRoute.priority)
        assertNotEquals(
            node.publicResources.single { it.niceId == gitRoute.resourceNiceId }.proxyPort,
            node.publicResources.single { it.niceId == frontDoorRoute.resourceNiceId }.proxyPort,
        )
    }

    @Test
    fun rawUdpServicePublishesPortOnlyOnItsRegions() {
        val game = container(
            "game",
            "homegap.expose" to "game.test.example.net",
            "homegap.port" to "27015",
            "homegap.protocol" to "udp",
            "homegap.regions" to "na1",
        )
        val plan = Planner.plan(config, listOf(TRAEFIK, game))
        assertEquals(listOf("udp-27015"), plan.nodes.getValue("na1").rawPorts.map { it.entryPoint })
        assertTrue(plan.nodes.getValue("as1").rawPorts.isEmpty())
        assertEquals(listOf("na1"), plan.dns.filter { it.fqdn == "game.test.example.net" }.map { it.nodeId })
    }

    @Test
    fun privateServicesGetPrivateResourcesAndNoPublicDns() {
        val ssh = container(
            "ssh-jump",
            "homegap.expose" to "ssh.test.example.net",
            "homegap.port" to "22",
            "homegap.protocol" to "tcp",
            "homegap.access" to "private",
        )
        val plan = Planner.plan(config, listOf(TRAEFIK, ssh))
        val privateResource = plan.nodes.getValue("na1").privateResources.single()
        assertEquals("ssh.test.example.net", privateResource.alias)
        assertEquals("22", privateResource.tcpPorts)
        assertEquals("ssh-jump", privateResource.destination)
        assertTrue(plan.dns.none { it.fqdn == "ssh.test.example.net" })
        assertTrue(plan.nodes.getValue("na1").rawPorts.isEmpty())
    }

    @Test
    fun dnsHasGeoSetsAndManagementRecords() {
        val plan = Planner.plan(config, listOf(TRAEFIK))
        val root = plan.dns.filter { it.fqdn == "test.example.net" && it.type != "CAA" }
        assertEquals(setOf("na1", "as1"), root.map { it.nodeId }.toSet())
        assertTrue(root.all { it.geo && it.monitor == "http" && it.name == "test" })
        val wildcard = plan.dns.filter { it.name == "*.test" }
        assertEquals(3, wildcard.size, "A for both nodes + AAAA for as1")
        val na1 = root.single { it.nodeId == "na1" }
        assertEquals(45.5, na1.latitude)
        val as1 = root.single { it.nodeId == "as1" && it.type == "A" }
        assertNotNull(as1.latitude, "region code AS falls back to a centroid")
        val management = plan.dns.filter { it.purpose == "management" }
        assertEquals(setOf("pangolin-na1.test", "pangolin-as1.test"), management.map { it.name }.toSet())
        assertTrue(management.none { it.geo })
    }

    @Test
    fun duplicateHostnamesAreReported() {
        val first = container("a", "homegap.expose" to "x.test.example.net")
        val second = container("b", "homegap.expose" to "x.test.example.net")
        val plan = Planner.plan(config, listOf(first, second))
        assertEquals(1, plan.services.size)
        assertTrue(plan.labelErrors.any { it.container == "b" && "already claimed" in it.message })
    }

    @Test
    fun portAllocationIsDeterministicAndCollisionFree() {
        val keys = (1..300).map { "hg-svc-$it" }
        val forward = Planner.allocatePorts(keys, base = 40000, avoid = setOf(41000))
        val reversed = Planner.allocatePorts(keys.reversed(), base = 40000, avoid = setOf(41000))
        assertEquals(forward, reversed)
        assertEquals(keys.size, forward.values.toSet().size)
        assertFalse(41000 in forward.values)
    }

    @Test
    fun sniRuleShape() {
        val node = Planner.plan(config, listOf(TRAEFIK)).nodes.getValue("na1")
        val rule = NodeTemplates.sniRule(node.sniRoutes.single())
        assertTrue(rule.startsWith("(HostSNI(`test.example.net`) || HostSNIRegexp(`(?i)^"))
        assertTrue(rule.endsWith("&& !HostSNI(`pangolin-na1.test.example.net`)"))
        val pattern = NodeTemplates.wildcardRegex("test.example.net").removePrefix("(?i)")
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        assertTrue(regex.matches("media.test.example.net"))
        assertFalse(regex.matches("a.b.test.example.net"), "wildcards match a single label only")
        assertFalse(regex.matches("test.example.net"))
    }
}
