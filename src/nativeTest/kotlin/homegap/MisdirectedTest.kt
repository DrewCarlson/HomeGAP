package homegap

import homegap.config.ClusterConfig
import homegap.config.ConfigLoader
import homegap.dashboard.DashboardAuth
import homegap.discovery.ContainerNetworkSettings
import homegap.discovery.NetworkEndpoint
import homegap.discovery.TraefikRouter
import homegap.model.Planner
import homegap.provision.HomelabTraefik
import homegap.provision.NodeTemplates
import homegap.routes.TraefikRoutes
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 421 routes for a browser that reuses the connection of one site for a name that another address serves. */
class MisdirectedTest {
    private val config = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
    private val plan = Planner.plan(config, listOf(TRAEFIK))

    @Test
    fun nodesMatchWhatTheyPassThroughOnlyAsPatterns() {
        val na1 = plan.nodes.getValue("na1")
        val rule = NodeTemplates.misdirectedRule(na1.sniRoutes)!!
        // One plain Host(), for a name that nobody requests, keeps the TLS options of the route.
        // The real names are patterns, so the SNI passthrough of the node for them stays as it is.
        assertEquals(1, Regex("""\bHost\(""").findAll(rule).count())
        assertTrue(rule.startsWith("Host(`${NodeTemplates.MISDIRECTED_ANCHOR}`)"))
        assertTrue("HostRegexp(`(?i)^test\\.example\\.net$`)" in rule, rule)
        assertTrue("HostRegexp(`${NodeTemplates.wildcardRegex("test.example.net")}`)" in rule, rule)

        val routes = NodeTemplates.dynamicRoutes(na1, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertTrue(NodeTemplates.MISDIRECTED_ROUTER in routes)
        val options = NodeTemplates.ELSEWHERE_TLS_OPTIONS
        assertTrue("options: \"$options\"" in routes || "options: $options" in routes, routes)
        assertTrue("noop@internal" in routes)
    }

    @Test
    fun theNodesOwnNamesGetA404NotA421() {
        val na1 = plan.nodes.getValue("na1")
        val managementHost = na1.managementHost
        // The passthrough wildcard covers the management host. Its paths without a route must not return a 421.
        val rule = NodeTemplates.misdirectedRule(na1.sniRoutes, listOf(managementHost, "Pangolin.test.example.net"))!!
        assertTrue(rule.startsWith("(Host(`${NodeTemplates.MISDIRECTED_ANCHOR}`)"), rule)
        assertTrue(rule.endsWith(" && !Host(`$managementHost`) && !Host(`pangolin.test.example.net`)"), rule)
        val routes = NodeTemplates.dynamicRoutes(na1, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertTrue("!Host(`$managementHost`)" in routes, routes)
    }

    @Test
    fun noPassthroughMeansNoRoute() {
        assertNull(NodeTemplates.misdirectedRule(emptyList()))
        // The node writes the passthrough routes only when their resources exist, and this route too.
        val routes = NodeTemplates.dynamicRoutes(plan.nodes.getValue("na1"), emptyMap(), cluster = true)
        assertFalse(NodeTemplates.MISDIRECTED_ROUTER in routes)
        assertFalse(NodeTemplates.ELSEWHERE_TLS_OPTIONS in routes)
    }

    @Test
    fun theHomelabListsNamesOnlyTheNodesServe() {
        val names = HomelabTraefik.nodeOnlyNames(plan, config.clientHost, homelabRouters = null)
        assertTrue("pangolin-na1.test.example.net" in names)
        assertTrue("pangolin-as1.test.example.net" in names)
        assertTrue("pangolin.test.example.net" in names)
        assertTrue(
            names.none { it == "test.example.net" || it.startsWith("git.") },
            "homelab names are not listed: $names",
        )
        // Unknown homelab routes: the shared speed test name stays out (the setup guide routes it there).
        assertTrue(names.none { it.startsWith("speedtest.") }, names.toString())
    }

    @Test
    fun namesTheHomelabRoutesAreLeftOut() {
        val routers = listOf(
            TraefikRouter("mine@docker", "Host(`pangolin.test.example.net`) && PathPrefix(`/x`)"),
            TraefikRouter("other@file", "HostRegexp(`^status-.+$`)"),
        )
        val names = HomelabTraefik.nodeOnlyNames(plan, config.clientHost, routers)
        assertFalse("pangolin.test.example.net" in names)
        assertTrue("pangolin-na1.test.example.net" in names)
        // The route of HomeGAP, as the homelab Traefik returns it, does not remove the names.
        val own = TraefikRouter("${HomelabTraefik.MISDIRECTED_ROUTER}@http", "Host(`pangolin-na1.test.example.net`)")
        val loaded = routers + own
        assertEquals(names, HomelabTraefik.nodeOnlyNames(plan, config.clientHost, loaded))
        // Traefik copies the route for each entry point when the entry points have defaults
        // (for example the http.tls of websecure).
        val copyRule = "Host(`pangolin-na1.test.example.net`) || Host(`status.test.example.net`)"
        val copies = routers + listOf("web", "websecure").map {
            TraefikRouter("$it-${HomelabTraefik.MISDIRECTED_ROUTER}@http", copyRule)
        }
        assertEquals(names, HomelabTraefik.nodeOnlyNames(plan, config.clientHost, copies))
        assertFalse(HomelabTraefik.isOwn("not-homegap-misdirected-x@file"))
        assertEquals(
            listOf("a.example", "b.example"),
            HomelabTraefik.hostsIn("Host(`a.example`) || Host(`B.example`) && Path(`/`)"),
        )
    }

    @Test
    fun homelabConfigCarriesItsOwnTlsOptions() {
        val misdirected = HomelabTraefik.misdirectedConfig(
            listOf("pangolin.test.example.net", "pangolin-na1.test.example.net"),
        )
        val routers = misdirected["http"]!!.jsonObject["routers"]!!.jsonObject
        val router = routers[HomelabTraefik.MISDIRECTED_ROUTER]!!.jsonObject
        assertEquals(
            "Host(`pangolin.test.example.net`) || Host(`pangolin-na1.test.example.net`)",
            router["rule"]!!.jsonPrimitive.content,
        )
        assertEquals(NodeTemplates.ELSEWHERE_TLS_OPTIONS, router["tls"]!!.jsonObject["options"]!!.jsonPrimitive.content)
        assertTrue(NodeTemplates.ELSEWHERE_TLS_OPTIONS in misdirected["tls"]!!.jsonObject["options"]!!.jsonObject)
        assertTrue(HomelabTraefik.misdirectedConfig(emptyList()).isEmpty())
    }

    @Test
    fun theConfigSendsThePagesToTheOrchestrator() {
        val both = ConfigLoader.parse("$TEST_CONFIG\nspeedtest:\n  enabled: true\nstatus_page:\n  enabled: true\n")
        val pageHosts = HomelabTraefik.pageHosts(both)
        val expected = listOf(
            "speedtest.test.example.net",
            "speedtest-na1.test.example.net",
            "speedtest-as1.test.example.net",
            "status-na1.test.example.net",
            "status-as1.test.example.net",
        )
        assertEquals(expected, pageHosts)

        val pages = HomelabTraefik.Pages(pageHosts, "http://orchestrator:8089")
        val answer = HomelabTraefik.dynamicConfig(emptyList(), pages)
        val http = answer["http"]!!.jsonObject
        val router = http["routers"]!!.jsonObject[HomelabTraefik.PAGES]!!.jsonObject
        assertEquals(expected.joinToString(" || ") { "Host(`$it`)" }, router["rule"]!!.jsonPrimitive.content)
        assertEquals(HomelabTraefik.PAGES, router["service"]!!.jsonPrimitive.content)
        assertTrue(router["tls"]!!.jsonObject.isEmpty(), "the homelab Traefik gives the certificate")
        val service = http["services"]!!.jsonObject[HomelabTraefik.PAGES]!!.jsonObject
        val servers = service["loadBalancer"]!!.jsonObject["servers"]
        assertEquals("""[{"url":"http://orchestrator:8089"}]""", servers.toString())
        assertNull(http["routers"]!!.jsonObject[HomelabTraefik.MISDIRECTED_ROUTER], "no name of the nodes only")
        assertNull(answer["tls"])
    }

    @Test
    fun pangolinClientsGetTheSharedStatusNameFromTheHomelab() {
        val status = HomelabTraefik.PrivateStatus("status.test.example.net", "172.29.0.3/24")
        val pages = HomelabTraefik.Pages(listOf("status-na1.test.example.net"), "http://orchestrator:8089", status)
        val answer = HomelabTraefik.dynamicConfig(listOf("status.test.example.net"), pages)
        val routers = answer["http"]!!.jsonObject["routers"]!!.jsonObject
        val private = routers[HomelabTraefik.PRIVATE_STATUS]!!.jsonObject
        val misdirected = routers[HomelabTraefik.MISDIRECTED_ROUTER]!!.jsonObject
        val rule = "Host(`status.test.example.net`) && ClientIP(`172.29.0.3/24`)"
        assertEquals(rule, private["rule"]!!.jsonPrimitive.content)
        assertEquals(HomelabTraefik.PAGES, private["service"]!!.jsonPrimitive.content)
        assertTrue(private["priority"]!!.jsonPrimitive.int > misdirected["priority"]!!.jsonPrimitive.int)
        assertEquals(misdirected["tls"], private["tls"], "one name, one set of TLS options")
        assertTrue(HomelabTraefik.isOwn("websecure-${HomelabTraefik.PRIVATE_STATUS}@http"))
    }

    @Test
    fun aPageThatIsOffHasNoRoute() {
        assertTrue(HomelabTraefik.pageHosts(testConfig()).isEmpty(), "the two pages are off by default")
        val statusOnly = ConfigLoader.parse("$TEST_CONFIG\nstatus_page:\n  enabled: true\n")
        assertEquals(
            listOf("status-na1.test.example.net", "status-as1.test.example.net"),
            HomelabTraefik.pageHosts(statusOnly),
        )
        val misdirectedOnly = HomelabTraefik.misdirectedConfig(listOf("pangolin.test.example.net"))
        assertNull(misdirectedOnly["http"]!!.jsonObject["services"])
        assertNull(misdirectedOnly["http"]!!.jsonObject["routers"]!!.jsonObject[HomelabTraefik.PAGES])
    }

    @Test
    fun aPrivateDashboardRouteAdmitsOnlyThePrivateNetwork() {
        val dashboard = HomelabTraefik.Dashboard(
            host = "orc.test.example.net",
            url = "http://orchestrator:8088",
            allowFrom = "172.29.0.3/24",
        )
        val noPages = HomelabTraefik.Pages(emptyList(), "")
        val http = HomelabTraefik.dynamicConfig(emptyList(), noPages, dashboard)["http"]!!.jsonObject

        val router = http["routers"]!!.jsonObject[HomelabTraefik.DASHBOARD]!!.jsonObject
        assertEquals("Host(`orc.test.example.net`)", router["rule"]!!.jsonPrimitive.content)
        assertEquals("""["${HomelabTraefik.PRIVATE_ONLY}"]""", router["middlewares"].toString())
        val allowList = http["middlewares"]!!.jsonObject[HomelabTraefik.PRIVATE_ONLY]!!.jsonObject["ipAllowList"]
        assertEquals("""{"sourceRange":["172.29.0.3/24"]}""", allowList.toString())
        val service = http["services"]!!.jsonObject[HomelabTraefik.DASHBOARD]!!.jsonObject
        val servers = service["loadBalancer"]!!.jsonObject["servers"]
        assertEquals("""[{"url":"http://orchestrator:8088"}]""", servers.toString())
    }

    @Test
    fun aPublicDashboardRouteHasNoAllowList() {
        val dashboard = HomelabTraefik.Dashboard("orc.test.example.net", "http://orchestrator:8088", allowFrom = null)
        val noPages = HomelabTraefik.Pages(emptyList(), "")
        val http = HomelabTraefik.dynamicConfig(emptyList(), noPages, dashboard)["http"]!!.jsonObject

        assertNull(http["routers"]!!.jsonObject[HomelabTraefik.DASHBOARD]!!.jsonObject["middlewares"])
        assertNull(http["middlewares"])
    }

    @Test
    fun aPrivateDashboardIsOneMorePrivateNameOfTheFrontDoor() {
        val private = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  hostname: orc\n")
        val networks = mapOf(
            "traefik" to NetworkEndpoint("172.32.0.2", 16),
            "homegap-private" to NetworkEndpoint("172.29.0.3", 24),
        )
        val privateLabels = mapOf("homegap.private_network" to "homegap-private", "homegap.private_hosts" to "wiki")
        val frontDoor = TRAEFIK.copy(
            labels = TRAEFIK.labels + privateLabels,
            networkSettings = ContainerNetworkSettings(networks),
        )

        val plan = Planner.plan(private, listOf(frontDoor))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        assertTrue("orc.test.example.net" in plan.frontDoor!!.privateHosts)
        val aliases = plan.nodes.getValue("na1").privateResources.map { it.alias }
        assertTrue("orc.test.example.net" in aliases && "wiki.test.example.net" in aliases, aliases.toString())

        assertFalse("status.test.example.net" in aliases, "no status page, so no private name for it")
        val withStatus = ConfigLoader.parse("$TEST_CONFIG\nstatus_page:\n  enabled: true\n")
        val statusPlan = Planner.plan(withStatus, listOf(frontDoor))
        assertTrue("status.test.example.net" in statusPlan.frontDoor!!.privateHosts)
        assertTrue(Planner.plan(withStatus, listOf(TRAEFIK)).frontDoor!!.privateHosts.isEmpty())

        val withoutPath = Planner.plan(private, listOf(TRAEFIK))
        assertTrue(withoutPath.labelErrors.single().message.startsWith("dashboard.access is private"))
        val public = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  hostname: orc\n  access: public\n")
        val publicPlan = Planner.plan(public, listOf(TRAEFIK))
        assertTrue(publicPlan.labelErrors.isEmpty(), "a public dashboard needs no private path")
    }

    @Test
    fun theNameOfTheDashboardIsChecked() {
        val badName = "$TEST_CONFIG\ndashboard:\n  hostname: Orc.x\n"
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(badName) }
        val wrongAccess = "$TEST_CONFIG\ndashboard:\n  hostname: orc\n  access: lan\n"
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(wrongAccess) }
        val taken = "$TEST_CONFIG\nstatus_page:\n  enabled: true\ndashboard:\n  hostname: status\n"
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(taken) }
        val claimed = container("app", "homegap.expose" to "orc.test.example.net", "homegap.port" to "80")
        val config = ConfigLoader.parse("$TEST_CONFIG\ndashboard:\n  hostname: orc\n  access: public\n")
        val plan = Planner.plan(config, listOf(TRAEFIK, claimed))
        assertTrue(plan.labelErrors.isNotEmpty(), "a label cannot take the name")
    }

    @Test
    fun onlyTheHomelabTraefiksOwnPollSkipsTheToken() {
        val frontDoor = setOf("172.30.0.14", "172.29.0.3")
        val auth = DashboardAuth("t0ken", generatedFile = null)
        fun allowed(peer: String, forwarded: Boolean, authorization: String? = null) =
            TraefikRoutes.allowed(auth, authorization, peer, forwarded, frontDoor)
        assertTrue(allowed("172.30.0.14", forwarded = false), "Traefik's own poll")
        assertTrue(allowed("172.29.0.3", forwarded = false), "on any of its networks")
        // Everything that Traefik forwards comes from its address too, with X-Forwarded-For.
        // A visitor who opens the address of the dashboard must not get access this way.
        assertFalse(allowed("172.30.0.14", forwarded = true))
        assertFalse(allowed("172.30.0.99", forwarded = false), "another container")
        assertTrue(allowed("172.30.0.99", forwarded = true, authorization = "Bearer t0ken"), "the token always works")
        assertFalse(allowed("172.30.0.99", forwarded = false, authorization = "Bearer wrong"))
        assertFalse(allowed("172.30.0.99", forwarded = false, authorization = "t0ken"), "not a bearer header")
        val undiscovered = TraefikRoutes.allowed(
            auth,
            authorization = null,
            peer = "172.30.0.14",
            forwarded = false,
            frontDoor = emptySet(),
        )
        assertFalse(undiscovered, "front door not discovered yet")
    }
}
