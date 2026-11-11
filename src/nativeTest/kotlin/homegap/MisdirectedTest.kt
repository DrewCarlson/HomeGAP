package homegap

import homegap.config.ClusterConfig
import homegap.discovery.TraefikRouter
import homegap.model.Planner
import homegap.provision.HomelabTraefik
import homegap.provision.NodeTemplates
import homegap.routes.TraefikRoutes
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 421 routes for a browser reusing one site's connection for a name served elsewhere. */
class MisdirectedTest {
    private val cfg = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
    private val plan = Planner.plan(cfg, listOf(TRAEFIK))

    @Test
    fun nodesMatchWhatTheyPassThroughOnlyAsPatterns() {
        val na1 = plan.nodes.getValue("na1")
        val rule = NodeTemplates.misdirectedRule(na1.sniRoutes)!!
        // One plain Host(), for a name nobody asks for, keeps the route's TLS options; the real
        // names are patterns, so the node's SNI passthrough for them is left alone.
        assertEquals(1, Regex("""\bHost\(""").findAll(rule).count())
        assertTrue(rule.startsWith("Host(`${NodeTemplates.MISDIRECTED_ANCHOR}`)"))
        assertTrue("HostRegexp(`(?i)^test\\.example\\.net$`)" in rule, rule)
        assertTrue("HostRegexp(`${NodeTemplates.wildcardRegex("test.example.net")}`)" in rule, rule)

        val routes = NodeTemplates.dynamicRoutes(na1, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertTrue(NodeTemplates.MISDIRECTED_ROUTER in routes)
        assertTrue("options: \"${NodeTemplates.ELSEWHERE_TLS_OPTIONS}\"" in routes || "options: ${NodeTemplates.ELSEWHERE_TLS_OPTIONS}" in routes, routes)
        assertTrue("noop@internal" in routes)
    }

    @Test
    fun noPassthroughMeansNoRoute() {
        assertNull(NodeTemplates.misdirectedRule(emptyList()))
        // The passthrough routes are only written once their resources exist; so is this one.
        val routes = NodeTemplates.dynamicRoutes(plan.nodes.getValue("na1"), emptyMap(), cluster = true)
        assertFalse(NodeTemplates.MISDIRECTED_ROUTER in routes)
        assertFalse(NodeTemplates.ELSEWHERE_TLS_OPTIONS in routes)
    }

    @Test
    fun theHomelabListsNamesOnlyTheNodesServe() {
        val names = HomelabTraefik.nodeOnlyNames(plan, cfg.clientHost, homelabRouters = null)
        assertTrue("pangolin-na1.test.example.net" in names)
        assertTrue("pangolin-as1.test.example.net" in names)
        assertTrue("pangolin.test.example.net" in names)
        assertTrue(names.none { it == "test.example.net" || it.startsWith("git.") }, "homelab names are not listed: $names")
        // Unknown homelab routes: the shared speed test name stays out (the setup guide routes it there).
        assertTrue(names.none { it.startsWith("speedtest.") }, names.toString())
    }

    @Test
    fun namesTheHomelabRoutesAreLeftOut() {
        val routers = listOf(
            TraefikRouter("mine@docker", "Host(`pangolin.test.example.net`) && PathPrefix(`/x`)"),
            TraefikRouter("other@file", "HostRegexp(`^status-.+$`)"),
        )
        val names = HomelabTraefik.nodeOnlyNames(plan, cfg.clientHost, routers)
        assertFalse("pangolin.test.example.net" in names)
        assertTrue("pangolin-na1.test.example.net" in names)
        // Its own route, read back from the homelab Traefik, does not take the names away.
        val loaded = routers + TraefikRouter("${HomelabTraefik.MISDIRECTED_ROUTER}@http", "Host(`pangolin-na1.test.example.net`)")
        assertEquals(names, HomelabTraefik.nodeOnlyNames(plan, cfg.clientHost, loaded))
        // Copied per entry point when the entry points have defaults (e.g. websecure's http.tls).
        val copies = routers + listOf("web", "websecure").map { TraefikRouter("$it-${HomelabTraefik.MISDIRECTED_ROUTER}@http", "Host(`pangolin-na1.test.example.net`) || Host(`status.test.example.net`)") }
        assertEquals(names, HomelabTraefik.nodeOnlyNames(plan, cfg.clientHost, copies))
        assertFalse(HomelabTraefik.isOwn("not-homegap-misdirected-x@file"))
        assertEquals(listOf("a.example", "b.example"), HomelabTraefik.hostsIn("Host(`a.example`) || Host(`B.example`) && Path(`/`)"))
    }

    @Test
    fun homelabConfigCarriesItsOwnTlsOptions() {
        val conf = HomelabTraefik.misdirectedConfig(listOf("pangolin.test.example.net", "pangolin-na1.test.example.net"))
        val router = conf["http"]!!.jsonObject["routers"]!!.jsonObject[HomelabTraefik.MISDIRECTED_ROUTER]!!.jsonObject
        assertEquals("Host(`pangolin.test.example.net`) || Host(`pangolin-na1.test.example.net`)", router["rule"]!!.jsonPrimitive.content)
        assertEquals(NodeTemplates.ELSEWHERE_TLS_OPTIONS, router["tls"]!!.jsonObject["options"]!!.jsonPrimitive.content)
        assertTrue(NodeTemplates.ELSEWHERE_TLS_OPTIONS in conf["tls"]!!.jsonObject["options"]!!.jsonObject)
        assertTrue(HomelabTraefik.misdirectedConfig(emptyList()).isEmpty())
    }

    @Test
    fun onlyTheHomelabTraefiksOwnPollSkipsTheToken() {
        val frontDoor = setOf("172.30.0.14", "172.29.0.3")
        fun allowed(peer: String, forwarded: Boolean, authorization: String? = null, token: String? = "t0ken") =
            TraefikRoutes.allowed(token, authorization, peer, forwarded, frontDoor)
        assertTrue(allowed("172.30.0.14", forwarded = false), "Traefik's own poll")
        assertTrue(allowed("172.29.0.3", forwarded = false), "on any of its networks")
        // Everything Traefik forwards comes from its address too, with X-Forwarded-For: a visitor
        // opening the dashboard's address must not get in this way.
        assertFalse(allowed("172.30.0.14", forwarded = true))
        assertFalse(allowed("172.30.0.99", forwarded = false), "another container")
        assertTrue(allowed("172.30.0.99", forwarded = true, authorization = "Bearer t0ken"), "the token always works")
        assertFalse(allowed("172.30.0.99", forwarded = false, authorization = "Bearer wrong"))
        assertTrue(allowed("172.30.0.99", forwarded = true, token = null), "no token set: open, like the rest of /api")
        assertFalse(TraefikRoutes.allowed("t0ken", null, "172.30.0.14", false, emptySet()), "front door not discovered yet")
    }
}
