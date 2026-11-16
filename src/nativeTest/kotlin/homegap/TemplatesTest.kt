package homegap

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import homegap.model.Planner
import homegap.model.RawPort
import homegap.provision.MaintenancePage
import homegap.provision.Mesh
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.YamlEmitter
import kotlinx.serialization.json.*
import kotlin.test.*

class TemplatesTest {
    private val config = testConfig()
    private val secrets = NodeSecrets(crowdsecBouncerKey = "bk")

    private fun JsonObject.at(vararg path: String): JsonObject =
        path.fold(this) { tree, key -> tree.getValue(key).jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun yamlEmitterRoundTripsThroughARealParser() {
        val tree = buildJsonObject {
            put("plain", "x")
            put("quote", "a\"b'c: #d")
            put("num", 5)
            put("bool", true)
            putJsonArray("empty") {}
            putJsonObject("nested") {
                putJsonArray("list") {
                    add("one")
                    addJsonObject {
                        put("k", "v")
                        put("k2", 2)
                    }
                }
            }
            put("rule", "Host(`a.b`) && PathPrefix(`/api`)")
        }
        val yaml = YamlEmitter.emit(tree, "header")
        val back = yamlToJson(yaml)
        assertEquals(tree["quote"], back.jsonObject["quote"])
        assertEquals(tree["rule"], back.jsonObject["rule"])
        val nestedList = back.jsonObject["nested"]!!.jsonObject["list"]!!.jsonArray
        assertEquals("v", nestedList[1].jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyRenderedFileIsValidYaml() {
        val game = container(
            "game",
            "homegap.expose" to "game.test.example.net",
            "homegap.port" to "27015",
            "homegap.protocol" to "udp",
        )
        val plan = Planner.plan(config, listOf(TRAEFIK, game))
        assertEquals(listOf(RawPort(27015, "udp")), plan.nodes.getValue("na1").rawPorts)
        val files = NodeTemplates.staticFiles(config, config.node("na1")!!, secrets)
        for ((path, content) in files.filter { it.path.endsWith(".yml") || it.path.endsWith(".yaml") }) {
            val node = Yaml.default.parseToYamlNode(content)
            assertTrue(node is YamlMap, "$path should parse as a mapping")
        }
        val compose = files.single { it.path == "compose.yml" }.content
        // No container publishes a raw port: the kernel or the agent of the node serves it.
        assertFalse("27015" in compose)
        val agent = compose.substringAfter("  homegap-agent:")
        assertTrue("network_mode: \"host\"" in agent && "./agent:/agent:ro" in agent)
        assertTrue("NET_BIND_SERVICE" in agent && "read_only: true" in agent)
        // The exit of the node: the agent changes the network of the host and keeps its key.
        assertTrue("NET_ADMIN" in agent && "./agent-state:/agent-state" in agent)
        val published = yamlToJson(compose).jsonObject.at("services").values.flatMap { service ->
            service.jsonObject["ports"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("443:443", "443:443/udp", "80:80"), published, "only the web ports are public")
        val traefik = files.single { it.path.endsWith("traefik_config.yml") }.content
        assertFalse("27015" in traefik)
        assertEquals("0600", files.single { it.path == ".env" }.mode)
    }

    @Test
    fun theNameOfTheSignInFailsOverToTheAgentOfTheNode() {
        val signIn = container("homegap-sign-in", "homegap.expose" to "vpn.test.example.net", "homegap.port" to "8090")
        val planned = Planner.plan(config, listOf(TRAEFIK, signIn)).nodes.getValue("na1")
        val name = "vpn.test.example.net"
        val control = Mesh.NodeControl(name, "10.77.0.10:8092", "key", "na1", "203.0.113.10", "100.96.128.0/24")
        val exit = Mesh.NodeExit("100.90.0.0/16", "100.90.255.10", 51840, "10.77.0.10:8091", "10.77.0.1", control)
        val routes = NodeTemplates.dynamicRoutes(planned.copy(exit = exit))
        assertTrue("failover:" in routes && "fallback: \"homegap-node-control\"" in routes, routes)
        assertTrue("url: \"http://10.77.0.10:8092\"" in routes)
        // Traefik refuses a failover if one of its two services has no health check.
        assertEquals(2, routes.lines().count { "path: \"/api/v1/\"" in it })
        // Without the control side, the name is a service like each other.
        assertFalse("failover:" in NodeTemplates.dynamicRoutes(planned))
    }

    @Test
    fun dynamicRoutesSendPassthroughAndHealthOverTheMesh() {
        val plan = Planner.plan(config, listOf(TRAEFIK))
        val desired = plan.nodes.getValue("na1")
        val tree = yamlToJson(NodeTemplates.dynamicRoutes(desired)).jsonObject
        val tcp = tree.at("tcp", "routers", "homegap-frontdoor")
        assertEquals("homegap-mesh-hg-frontdoor", tcp.text("service"))
        assertEquals(true, tcp.at("tls").getValue("passthrough").jsonPrimitive.boolean)
        // The stream goes to the port of the front door on the mesh address of the homelab.
        val port = desired.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }.proxyPort
        val service = tree.at("tcp", "services", "homegap-mesh-hg-frontdoor").toString()
        assertTrue("10.77.0.1:$port" in service, service)
        val http = tree.at("http", "routers")
        // The health check goes to the port of the health resource on the same address.
        val healthId = Planner.perNode(Planner.HEALTH, "na1")
        assertEquals("homegap-mesh-$healthId", http.at("homegap-health").text("service"))
        val healthPort = desired.httpResources.single { it.niceId == healthId }.meshPort
        val healthServers = tree.at("http", "services", "homegap-mesh-$healthId", "loadBalancer")
            .getValue("servers").jsonArray
        assertEquals("https://10.77.0.1:$healthPort", healthServers.single().jsonObject.text("url"))
        assertFalse("homegap-http-$healthId" in http, "the health resource has no public name")
        assertTrue("homegap-health-fallback" in http)
        assertEquals("Host(`node-na1.test.example.net`)", http.at("homegap-mgmt-redirect").text("rule"))
    }

    @Test
    fun aTerminatedServiceGetsARouterAndAServiceOnTheMesh() {
        val app = container("app", "homegap.expose" to "app.test.example.net", "homegap.port" to "8080")
        val desired = Planner.plan(config, listOf(TRAEFIK, app)).nodes.getValue("na1")
        val resource = desired.httpResources.single { it.fullDomain == "app.test.example.net" }
        val http = yamlToJson(NodeTemplates.dynamicRoutes(desired)).jsonObject.at("http")

        val router = http.at("routers", "homegap-http-${resource.niceId}")
        assertEquals("Host(`app.test.example.net`)", router.text("rule"))
        assertEquals(listOf("websecure"), router.getValue("entryPoints").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("tls" in router, "the node ends TLS for the name")
        assertEquals("homegap-mesh-${resource.niceId}", router.text("service"))
        val servers = http.at("services", "homegap-mesh-${resource.niceId}", "loadBalancer").getValue("servers")
        val url = servers.jsonArray.single().jsonObject.text("url")
        assertEquals("10.77.0.1:${resource.meshPort}", url.substringAfter("://"))
        // The service of the homelab learns the node, and a client cannot set the header.
        val header = http.at("middlewares", "homegap-node", "headers", "customRequestHeaders")
        assertEquals("na1", header.text(NodeTemplates.NODE_HEADER_NAME))
        assertTrue(JsonPrimitive("homegap-node") in router.getValue("middlewares").jsonArray)
    }

    @Test
    fun aPausedServiceKeepsItsRoutesAndItsPorts() {
        val app = container(
            "app",
            "homegap.expose" to "app.test.example.net",
            "homegap.port" to "8080",
            "homegap.pause" to "true",
        )
        val game = container(
            "game",
            "homegap.expose" to "game.test.example.net",
            "homegap.port" to "27015",
            "homegap.protocol" to "udp",
            "homegap.pause" to "true",
        )
        val desired = Planner.plan(config, listOf(TRAEFIK, app, game)).nodes.getValue("na1")
        val resource = desired.httpResources.single { it.fullDomain == "app.test.example.net" }

        assertTrue(resource.paused)
        val routes = yamlToJson(NodeTemplates.dynamicRoutes(desired)).jsonObject
        assertTrue("homegap-http-${resource.niceId}" in routes.at("http", "routers"))
        assertTrue("homegap-mesh-${resource.niceId}" in routes.at("http", "services"))
        assertEquals(listOf(RawPort(27015, "udp")), desired.forwardedPorts)
    }

    @Test
    fun nodesRunTheMaintenancePageWithoutScripts() {
        val node = config.node("na1")!!
        val files = NodeTemplates.staticFiles(config, node, secrets)
        val compose = files.single { it.path == "compose.yml" }.content
        // A node has no image of HomeGAP for the page, so it pulls one only for the page.
        assertTrue("docker.io/library/python:3.13-alpine" in compose)
        // The page has its own place on the network of the stack, so a restart of Traefik does not cut it off.
        val service = compose.substringAfter("  maintenance:").substringBefore("\n  homegap-agent:")
        assertFalse("network_mode" in service || "ports:" in service, service)
        val app = container("app", "homegap.expose" to "app.test.example.net", "homegap.tls" to "terminate")
        val routes = MaintenancePage.routes(Planner.plan(config, listOf(TRAEFIK, app)).nodes.getValue("na1"))
        assertTrue("http://homegap-maintenance:8090" in routes, routes)
        assertTrue("./config/maintenance:/maintenance:ro" in compose && "./config/traefik/dynamic:/dynamic" in compose)
        val page = files.single { it.path == "config/maintenance/index.html" }.content
        assertFalse("<script" in page.lowercase(), "the page is static HTML and CSS")
        assertTrue("Back shortly" in page && config.domain in page)
        assertEquals("maintenance", files.single { it.path == "config/maintenance/server.py" }.restarts)
    }

    @Test
    fun nodesCallNoOutsideServiceOnTheirOwn() {
        val files = NodeTemplates.staticFiles(config, config.node("na1")!!, secrets).associateBy { it.path }
        val traefik = files.getValue("config/traefik/traefik_config.yml").content
        assertTrue("checkNewVersion: false" in traefik && "sendAnonymousUsage: false" in traefik, traefik)
    }
}
