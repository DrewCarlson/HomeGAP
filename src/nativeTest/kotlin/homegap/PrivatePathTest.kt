package homegap

import homegap.clients.SshPool
import homegap.config.AppConfig
import homegap.config.ClusterConfig
import homegap.discovery.ContainerNetworkSettings
import homegap.discovery.NetworkEndpoint
import homegap.discovery.TraefikRouter
import homegap.discovery.aliasCovers
import homegap.discovery.parseLabels
import homegap.model.DesiredState
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
    private fun parse(vararg labels: Pair<String, String>) = parseLabels(
        containerId = "id",
        containerName = "svc",
        labels = labels.toMap(),
        domain = "test.example.net",
    )

    /** The front door on the shared network and on a network only private traffic uses. */
    private fun frontDoor(vararg extra: Pair<String, String>) = container(
        "traefik",
        "homegap.expose" to "test.example.net",
        "homegap.wildcard" to "true",
        "homegap.protocol" to "https",
        "homegap.proxy_protocol" to "true",
        "homegap.private_network" to "homegap-private",
        *extra,
    ).copy(
        networkSettings = ContainerNetworkSettings(
            mapOf(
                "traefik" to NetworkEndpoint("172.32.0.5"),
                "homelab_homegap-private" to NetworkEndpoint("172.33.0.2"),
            ),
        ),
    )

    private fun frontDoorTcp(plan: DesiredState) =
        plan.nodes.getValue("na1").publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }

    private fun reconcileContext(config: AppConfig) = ReconcileContext(
        config = config,
        state = StateStore(tmpPath("state")),
        log = ReconcileLog(),
        ssh = SshPool(controlDir = tmpPath("ssh"), knownHostsFile = tmpPath("kh")),
        localHttp = HttpClient(MockEngine { respondOk() }),
        dryRun = false,
        diff = DiffCollector(),
    )

    @Test
    fun proxyProtocolOnlyRidesTcpStreams() {
        val https = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.protocol" to "https",
            "homegap.proxy_protocol" to "true",
        )
        assertTrue(https.spec!!.proxyProtocol)
        val tcp = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "25",
            "homegap.public_port" to "2525",
            "homegap.proxy_protocol" to "true",
        )
        assertTrue(tcp.errors.isEmpty())
        val terminated = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.protocol" to "https",
            "homegap.tls" to "terminate",
            "homegap.proxy_protocol" to "true",
        )
        assertNull(terminated.spec, "the node re-proxies HTTP there: X-Forwarded-For carries the address")
        val plainHttp = parse("homegap.expose" to "a.test.example.net", "homegap.proxy_protocol" to "true")
        assertNull(plainHttp.spec, "plain http is terminated")
    }

    @Test
    fun privateHostsAreFullNamesUnderTheService() {
        val spec = parse(
            "homegap.expose" to "test.example.net",
            "homegap.protocol" to "https",
            "homegap.private_network" to "p",
            "homegap.private_hosts" to "jellyfin, *.home ,grafana.test.example.net",
        ).spec!!
        assertEquals(
            listOf("jellyfin.test.example.net", "*.home.test.example.net", "grafana.test.example.net"),
            spec.privateHosts,
        )
        val withoutHosts = parse(
            "homegap.expose" to "test.example.net",
            "homegap.protocol" to "https",
            "homegap.private_network" to "p",
        )
        assertEquals(listOf("test.example.net"), withoutHosts.spec!!.privateHosts)
        val withoutNetwork = parse("homegap.expose" to "test.example.net", "homegap.private_hosts" to "a")
        assertNull(withoutNetwork.spec, "needs the network")
        val privateAccess = parse(
            "homegap.expose" to "s.test.example.net",
            "homegap.access" to "private",
            "homegap.private_network" to "p",
        )
        assertNull(privateAccess.spec)
        assertTrue(aliasCovers("*.test.example.net", "pangolin.test.example.net"))
        assertFalse(aliasCovers("*.home.test.example.net", "pangolin.test.example.net"))
        assertTrue(aliasCovers("host-0?.test.example.net", "host-07.test.example.net"))
    }

    @Test
    fun theFrontDoorSendsTheViewersAddressAndClientsGetAPrivatePath() {
        val config = testConfig().copy(cluster = ClusterConfig())
        val plan = Planner.plan(config, listOf(frontDoor("homegap.private_hosts" to "jellyfin,*.home")))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val node = plan.nodes.getValue("na1")
        assertTrue(node.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }.proxyProtocol)
        assertTrue(
            node.publicResources.filter { it.mode == ResourceMode.HTTP }.none { it.proxyProtocol },
            "HTTP resources never carry it",
        )
        val private = node.privateResources.associateBy { it.alias }
        assertEquals(setOf("jellyfin.test.example.net", "*.home.test.example.net"), private.keys)
        // The address on the private network (compose prefixes it with the project), not a name:
        // Pangolin only takes a wildcard alias with an IP destination.
        assertTrue(private.values.all { it.destination == "172.33.0.2" && it.tcpPorts == "80,443" })
        assertEquals("hg-private--wildcard-home-test-example-net", private.getValue("*.home.test.example.net").niceId)

        val context = reconcileContext(config)
        val blueprint = ResourceReconciler(context).buildBlueprint(node.publicResources, node.privateResources)
        val tcpResource = blueprint["public-resources"]!!.jsonObject.getValue(Planner.FRONTDOOR_TCP).jsonObject
        assertTrue(tcpResource.getValue("proxy-protocol").jsonPrimitive.boolean)
        assertEquals(2, tcpResource.getValue("proxy-protocol-version").jsonPrimitive.int)
        val plain = Planner.plan(config, listOf(TRAEFIK)).nodes.getValue("na1")
        val unchanged = ResourceReconciler(context).buildBlueprint(plain.publicResources, plain.privateResources)
        val unchangedTcp = unchanged["public-resources"]!!.jsonObject.getValue(Planner.FRONTDOOR_TCP).jsonObject
        assertNull(unchangedTcp["proxy-protocol"], "off unless asked: the origin must accept it first")
    }

    @Test
    fun publicTrafficNeverTakesThePrivateNetwork() {
        val config = testConfig().copy(cluster = ClusterConfig())
        // By name, Docker can answer with the private address: the target is the other one.
        val shared = frontDoorTcp(Planner.plan(config, listOf(frontDoor())))
        assertEquals("172.32.0.5", shared.target.host)
        // homegap.host still wins.
        val pinned = frontDoorTcp(Planner.plan(config, listOf(frontDoor("homegap.host" to "traefik-public"))))
        assertEquals("traefik-public", pinned.target.host)
        // Several other networks: which one the tunnels share is unknown, so it asks.
        val twoNetworks = frontDoor()
        val threeNetworks = twoNetworks.networkSettings.networks + ("monitoring" to NetworkEndpoint("172.40.0.9"))
        val many = twoNetworks.copy(networkSettings = ContainerNetworkSettings(threeNetworks))
        assertTrue(Planner.plan(config, listOf(many)).labelErrors.any { "set homegap.host" in it.message })
        // A missing private network leaves the public service alone.
        val missing = Planner.plan(config, listOf(frontDoor("homegap.private_network" to "elsewhere")))
        assertTrue(missing.nodes.getValue("na1").publicResources.any { it.niceId == Planner.FRONTDOOR_TCP })
        assertTrue(missing.nodes.getValue("na1").privateResources.isEmpty())
    }

    @Test
    fun aClientsOwnAddressIsNeverCaptured() {
        val config = testConfig().copy(cluster = ClusterConfig())
        val plan = Planner.plan(config, listOf(frontDoor("homegap.private_hosts" to "*,jellyfin")))
        assertTrue(
            plan.labelErrors.any { "covers pangolin.test.example.net" in it.message },
            plan.labelErrors.toString(),
        )
        assertEquals(listOf("jellyfin.test.example.net"), plan.nodes.getValue("na1").privateResources.map { it.alias })
        val noNetwork = Planner.plan(config, listOf(frontDoor("homegap.private_network" to "elsewhere")))
        assertTrue(noNetwork.labelErrors.any { "no address on a network named 'elsewhere'" in it.message })
    }

    @Test
    fun routersUsingThePrivateMiddlewareNameTheirHosts() {
        val config = testConfig().copy(cluster = ClusterConfig())
        // From the front door's API: a subfolder service on the root and a subdomain one (from the
        // file provider). Then one with HostRegexp, a public one and a disabled one.
        val routers = listOf(
            TraefikRouter(
                name = "grafana@docker",
                rule = "Host(`test.example.net`) && PathPrefix(`/grafana`)",
                middlewares = listOf("auth@file", "private-only@file"),
            ),
            TraefikRouter(
                name = "jf@file",
                rule = "Host(`jellyfin.test.example.net`) || Host(`media.test.example.net`)",
                middlewares = listOf("private-only@file"),
            ),
            TraefikRouter(
                name = "misc@docker",
                rule = "HostRegexp(`^.+\\.lab\\.test\\.example\\.net$`)",
                middlewares = listOf("private-only@docker"),
            ),
            TraefikRouter(name = "blog@docker", rule = "Host(`blog.test.example.net`)"),
            TraefikRouter(
                name = "old@docker",
                rule = "Host(`old.test.example.net`)",
                middlewares = listOf("private-only@file"),
                status = "disabled",
            ),
        )
        val api = "http://traefik:8080"
        val withHosts = frontDoor(
            "homegap.private_middleware" to "private-only@file",
            "homegap.private_hosts" to "*.home",
        )
        val plan = Planner.plan(config, listOf(withHosts), traefikRouters = mapOf(api to routers))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val aliases = plan.nodes.getValue("na1").privateResources.map { it.alias }.toSet()
        // Both: the listed names, and every name that a router with the middleware serves.
        assertEquals(
            setOf("*.home.test.example.net", "test.example.net", "jellyfin.test.example.net", "media.test.example.net"),
            aliases,
        )
        assertTrue(
            plan.labelWarnings.any {
                "misc@docker" in it.message && "list them in homegap.private_hosts" in it.message
            },
            plan.labelWarnings.toString(),
        )

        // Only the middleware: the discovered names alone, not the front door's own.
        val only = Planner.plan(
            config,
            listOf(frontDoor("homegap.private_middleware" to "private-only")),
            traefikRouters = mapOf(api to routers.take(2)),
        )
        assertEquals(
            setOf("test.example.net", "jellyfin.test.example.net", "media.test.example.net"),
            only.nodes.getValue("na1").privateResources.map { it.alias }.toSet(),
        )
        // Another Traefik's API, or none read yet: nothing discovered, and it says so.
        val otherApi = frontDoor(
            "homegap.private_middleware" to "private-only",
            "homegap.traefik_api" to "http://other:9000/",
        )
        val elsewhere = Planner.plan(config, listOf(otherApi), traefikRouters = mapOf(api to routers))
        assertTrue(
            elsewhere.labelErrors.any { "http://other:9000 has not answered" in it.message },
            elsewhere.labelErrors.toString(),
        )
    }

    @Test
    fun standaloneNodesDefineThePangolinTransport() {
        val node = Planner.plan(testConfig(), listOf(frontDoor())).nodes.getValue("na1")
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7)
        val transport = NodeTemplates.PROXY_PROTOCOL_TRANSPORT
        assertTrue(transport in NodeTemplates.dynamicRoutes(node, ids, cluster = false))
        // Clustered nodes read Pangolin's own definition from its file. A second one clashes with it.
        assertFalse(transport in NodeTemplates.dynamicRoutes(node, ids, cluster = true))
    }

    @Test
    fun aNamedServiceKeepsItsOwnHostForPublicTraffic() {
        val app = container(
            "app",
            "homegap.services.web.expose" to "web.test.example.net",
            "homegap.services.web.protocol" to "https",
            "homegap.services.web.host" to "app-public",
            "homegap.services.web.private_network" to "homegap-private",
        ).copy(
            networkSettings = ContainerNetworkSettings(
                mapOf(
                    "traefik" to NetworkEndpoint("172.32.0.5"),
                    "homelab_homegap-private" to NetworkEndpoint("172.33.0.2"),
                ),
            ),
        )
        val plan = Planner.plan(testConfig(), listOf(app))
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val web = plan.services.single { it.name == "web" }
        assertEquals("app-public", web.targetHost)
        assertEquals("172.33.0.2", web.privateHost)
    }
}
