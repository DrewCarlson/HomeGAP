package homegap

import homegap.config.ClusterConfig
import homegap.discovery.ContainerNetworkSettings
import homegap.discovery.NetworkEndpoint
import homegap.discovery.TraefikRouter
import homegap.discovery.aliasCovers
import homegap.discovery.parseLabels
import homegap.model.Planner
import homegap.model.ResourceMode
import homegap.provision.NodeTemplates
import homegap.reconcile.DiffCollector
import homegap.reconcile.ReconcileContext
import homegap.reconcile.ReconcileLog
import homegap.reconcile.ResourceReconciler
import homegap.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** The viewer's address (PROXY protocol) and the private path for Pangolin clients. */
class PrivatePathTest {
    private fun parse(vararg l: Pair<String, String>) = parseLabels("id", "svc", l.toMap(), "test.example.net")

    /** The front door on the shared network and on a network only private traffic uses. */
    private fun frontDoor(vararg extra: Pair<String, String>) = container(
        "traefik",
        "homegap.expose" to "test.example.net", "homegap.wildcard" to "true", "homegap.protocol" to "https",
        "homegap.proxy_protocol" to "true", "homegap.private_network" to "homegap-private", *extra,
    ).copy(
        networkSettings = ContainerNetworkSettings(
            mapOf("traefik" to NetworkEndpoint("172.32.0.5"), "homelab_homegap-private" to NetworkEndpoint("172.33.0.2")),
        ),
    )

    @Test
    fun proxyProtocolOnlyRidesTcpStreams() {
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "https", "homegap.proxy_protocol" to "true").spec!!.proxyProtocol)
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "25", "homegap.public_port" to "2525", "homegap.proxy_protocol" to "true").errors.isEmpty())
        val terminated = parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "https", "homegap.tls" to "terminate", "homegap.proxy_protocol" to "true")
        assertNull(terminated.spec, "the node re-proxies HTTP there: X-Forwarded-For carries the address")
        assertNull(parse("homegap.expose" to "a.test.example.net", "homegap.proxy_protocol" to "true").spec, "plain http is terminated")
    }

    @Test
    fun privateHostsAreFullNamesUnderTheService() {
        val s = parse(
            "homegap.expose" to "test.example.net", "homegap.protocol" to "https", "homegap.private_network" to "p",
            "homegap.private_hosts" to "jellyfin, *.home ,grafana.test.example.net",
        ).spec!!
        assertEquals(listOf("jellyfin.test.example.net", "*.home.test.example.net", "grafana.test.example.net"), s.privateHosts)
        assertEquals(listOf("test.example.net"), parse("homegap.expose" to "test.example.net", "homegap.protocol" to "https", "homegap.private_network" to "p").spec!!.privateHosts)
        assertNull(parse("homegap.expose" to "test.example.net", "homegap.private_hosts" to "a").spec, "needs the network")
        assertNull(parse("homegap.expose" to "s.test.example.net", "homegap.access" to "private", "homegap.private_network" to "p").spec)
        assertTrue(aliasCovers("*.test.example.net", "pangolin.test.example.net"))
        assertFalse(aliasCovers("*.home.test.example.net", "pangolin.test.example.net"))
        assertTrue(aliasCovers("host-0?.test.example.net", "host-07.test.example.net"))
    }

    @Test
    fun theFrontDoorSendsTheViewersAddressAndClientsGetAPrivatePath() {
        val cfg = testConfig().copy(cluster = ClusterConfig())
        val plan = Planner.plan(cfg, listOf(frontDoor("homegap.private_hosts" to "jellyfin,*.home")))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val n = plan.nodes.getValue("na1")
        assertTrue(n.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }.proxyProtocol)
        assertTrue(n.publicResources.filter { it.mode == ResourceMode.HTTP }.none { it.proxyProtocol }, "HTTP resources never carry it")
        val private = n.privateResources.associateBy { it.alias }
        assertEquals(setOf("jellyfin.test.example.net", "*.home.test.example.net"), private.keys)
        // The address on the private network (compose prefixes it with the project), not a name:
        // Pangolin only takes a wildcard alias with an IP destination.
        assertTrue(private.values.all { it.destination == "172.33.0.2" && it.tcpPorts == "80,443" })
        assertEquals("hg-private--wildcard-home-test-example-net", private.getValue("*.home.test.example.net").niceId)

        val ctx = ReconcileContext(cfg, StateStore(tmpPath("state")), ReconcileLog(), homegap.clients.SshPool(tmpPath("ssh"), tmpPath("kh")),
            HttpClient(MockEngine { respondOk() }), HttpClient(MockEngine { respondOk() }), false, DiffCollector())
        val bp = ResourceReconciler(ctx).buildBlueprint(n.publicResources, n.privateResources)
        val fd = bp["public-resources"]!!.jsonObject.getValue(Planner.FRONTDOOR_TCP).jsonObject
        assertTrue(fd.getValue("proxy-protocol").jsonPrimitive.boolean)
        assertEquals(2, fd.getValue("proxy-protocol-version").jsonPrimitive.int)
        val plain = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("na1")
        val unchanged = ResourceReconciler(ctx).buildBlueprint(plain.publicResources, plain.privateResources)
        assertNull(unchanged["public-resources"]!!.jsonObject.getValue(Planner.FRONTDOOR_TCP).jsonObject["proxy-protocol"], "off unless asked: the origin must accept it first")
    }

    @Test
    fun publicTrafficNeverTakesThePrivateNetwork() {
        val cfg = testConfig().copy(cluster = ClusterConfig())
        // By name, Docker may answer with the private address: the target is the other one.
        val fd = Planner.plan(cfg, listOf(frontDoor())).nodes.getValue("na1").publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }
        assertEquals("172.32.0.5", fd.target.host)
        // homegap.host still wins.
        val pinned = Planner.plan(cfg, listOf(frontDoor("homegap.host" to "traefik-public"))).nodes.getValue("na1").publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }
        assertEquals("traefik-public", pinned.target.host)
        // Several other networks: which one the tunnels share is unknown, so it asks.
        val many = frontDoor().let { it.copy(networkSettings = ContainerNetworkSettings(it.networkSettings.networks + ("monitoring" to NetworkEndpoint("172.40.0.9")))) }
        assertTrue(Planner.plan(cfg, listOf(many)).labelErrors.any { "set homegap.host" in it.message })
        // A missing private network leaves the public service alone.
        val missing = Planner.plan(cfg, listOf(frontDoor("homegap.private_network" to "elsewhere")))
        assertTrue(missing.nodes.getValue("na1").publicResources.any { it.niceId == Planner.FRONTDOOR_TCP })
        assertTrue(missing.nodes.getValue("na1").privateResources.isEmpty())
    }

    @Test
    fun aClientsOwnAddressIsNeverCaptured() {
        val cfg = testConfig().copy(cluster = ClusterConfig())
        val plan = Planner.plan(cfg, listOf(frontDoor("homegap.private_hosts" to "*,jellyfin")))
        assertTrue(plan.labelErrors.any { "covers pangolin.test.example.net" in it.message }, plan.labelErrors.toString())
        assertEquals(listOf("jellyfin.test.example.net"), plan.nodes.getValue("na1").privateResources.map { it.alias })
        val noNetwork = Planner.plan(cfg, listOf(frontDoor("homegap.private_network" to "elsewhere")))
        assertTrue(noNetwork.labelErrors.any { "no address on a network named 'elsewhere'" in it.message })
    }

    @Test
    fun routersUsingThePrivateMiddlewareNameTheirHosts() {
        val cfg = testConfig().copy(cluster = ClusterConfig())
        // From the front door's API: a subfolder service on the root, a subdomain one (from the
        // file provider), one with HostRegexp, a public one and a disabled one.
        val routers = listOf(
            TraefikRouter("grafana@docker", "Host(`test.example.net`) && PathPrefix(`/grafana`)", listOf("auth@file", "private-only@file")),
            TraefikRouter("jf@file", "Host(`jellyfin.test.example.net`) || Host(`media.test.example.net`)", listOf("private-only@file")),
            TraefikRouter("misc@docker", "HostRegexp(`^.+\\.lab\\.test\\.example\\.net$`)", listOf("private-only@docker")),
            TraefikRouter("blog@docker", "Host(`blog.test.example.net`)"),
            TraefikRouter("old@docker", "Host(`old.test.example.net`)", listOf("private-only@file"), status = "disabled"),
        )
        val api = "http://traefik:8080"
        val plan = Planner.plan(
            cfg, listOf(frontDoor("homegap.private_middleware" to "private-only@file", "homegap.private_hosts" to "*.home")),
            traefikRouters = mapOf(api to routers),
        )
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val aliases = plan.nodes.getValue("na1").privateResources.map { it.alias }.toSet()
        // Both: the listed names, and every name a router using the middleware serves.
        assertEquals(setOf("*.home.test.example.net", "test.example.net", "jellyfin.test.example.net", "media.test.example.net"), aliases)
        assertTrue(plan.labelWarnings.any { "misc@docker" in it.message && "list them in homegap.private_hosts" in it.message }, plan.labelWarnings.toString())

        // Only the middleware: just the discovered names, not the front door's own.
        val only = Planner.plan(cfg, listOf(frontDoor("homegap.private_middleware" to "private-only")), traefikRouters = mapOf(api to routers.take(2)))
        assertEquals(setOf("test.example.net", "jellyfin.test.example.net", "media.test.example.net"), only.nodes.getValue("na1").privateResources.map { it.alias }.toSet())
        // Another Traefik's API, or none read yet: nothing discovered, and it says so.
        val elsewhere = Planner.plan(cfg, listOf(frontDoor("homegap.private_middleware" to "private-only", "homegap.traefik_api" to "http://other:9000/")), traefikRouters = mapOf(api to routers))
        assertTrue(elsewhere.labelErrors.any { "http://other:9000 has not answered" in it.message }, elsewhere.labelErrors.toString())
    }

    @Test
    fun standaloneNodesDefineThePangolinTransport() {
        val n = Planner.plan(testConfig(), listOf(frontDoor())).nodes.getValue("na1")
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7)
        assertTrue(NodeTemplates.PROXY_PROTOCOL_TRANSPORT in NodeTemplates.dynamicRoutes(n, ids, cluster = false))
        // Clustered nodes read Pangolin's own definition from its file; a second one would clash.
        assertFalse(NodeTemplates.PROXY_PROTOCOL_TRANSPORT in NodeTemplates.dynamicRoutes(n, ids, cluster = true))
    }
}
