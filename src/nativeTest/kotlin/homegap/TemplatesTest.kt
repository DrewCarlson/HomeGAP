package homegap

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import homegap.model.Planner
import homegap.model.RawPort
import homegap.provision.Mesh
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.YamlEmitter
import homegap.reconcile.ResourceReconciler
import kotlinx.serialization.json.*
import kotlin.test.*

class TemplatesTest {
    private val config = testConfig()
    private val secrets = NodeSecrets(
        setupToken = "a".repeat(32),
        serverSecret = "secret\"quote",
        adminEmail = "admin@test.example.net",
        adminPassword = "p'w\"d",
        crowdsecBouncerKey = "bk",
    )

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
        val back = ResourceReconciler.yamlToJson(yaml)
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
        val files = NodeTemplates.staticFiles(config, config.node("na1")!!, secrets, slot = 0)
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
        assertTrue("\"127.0.0.1:3003:3003\"" in compose, "integration API must be loopback only")
        val traefik = files.single { it.path.endsWith("traefik_config.yml") }.content
        assertFalse("27015" in traefik)
        val pangolin = files.single { it.path == "config/config.yml" }.content
        assertTrue("enable_integration_api: true" in pangolin)
        assertTrue("\"secret\\\"quote\"" in pangolin, "secrets are escaped, not interpolated raw")
        assertEquals("0600", files.single { it.path == "config/config.yml" }.mode)
    }

    @Test
    fun theNameOfTheSignInFailsOverToTheAgentOfTheNode() {
        val signIn = container("homegap-sign-in", "homegap.expose" to "vpn.test.example.net", "homegap.port" to "8090")
        val planned = Planner.plan(config, listOf(TRAEFIK, signIn)).nodes.getValue("na1")
        val name = "vpn.test.example.net"
        val control = Mesh.NodeControl(name, "10.77.0.10:8092", "key", "na1", "203.0.113.10", "100.96.128.0/24")
        val exit = Mesh.NodeExit("100.90.0.0/16", "100.90.255.10", 51840, "10.77.0.10:8091", "10.77.0.1", control)
        val routes = NodeTemplates.dynamicRoutes(planned.copy(exit = exit), emptyMap())
        assertTrue("failover:" in routes && "fallback: \"homegap-node-control\"" in routes, routes)
        assertTrue("url: \"http://10.77.0.10:8092\"" in routes)
        // Traefik refuses a failover if one of its two services has no health check.
        assertEquals(2, routes.lines().count { "path: \"/api/v1/\"" in it })
        // Without the control side, the name is a service like each other.
        assertFalse("failover:" in NodeTemplates.dynamicRoutes(planned, emptyMap()))
    }

    @Test
    fun dynamicRoutesSendPassthroughAndHealthOverTheMesh() {
        val plan = Planner.plan(config, listOf(TRAEFIK))
        val desired = plan.nodes.getValue("na1")
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7)
        val yaml = NodeTemplates.dynamicRoutes(desired, ids)
        val tree = ResourceReconciler.yamlToJson(yaml).jsonObject
        val tcp = tree["tcp"]!!.jsonObject["routers"]!!.jsonObject["homegap-frontdoor"]!!.jsonObject
        assertEquals("homegap-mesh-hg-frontdoor", tcp["service"]!!.jsonPrimitive.content)
        assertEquals(true, tcp["tls"]!!.jsonObject["passthrough"]!!.jsonPrimitive.boolean)
        // The stream goes to the port of the front door on the mesh address of the homelab.
        val port = desired.publicResources.single { it.niceId == Planner.FRONTDOOR_TCP }.proxyPort
        val service = tree["tcp"]!!.jsonObject["services"]!!.jsonObject["homegap-mesh-hg-frontdoor"]!!.toString()
        assertTrue("10.77.0.1:$port" in service, service)
        val http = tree["http"]!!.jsonObject["routers"]!!.jsonObject
        // The health check goes to the port of the health resource on the same address.
        assertEquals("homegap-mesh-hg-health", http["homegap-health"]!!.jsonObject["service"]!!.jsonPrimitive.content)
        val healthPort = desired.httpResources.single { it.niceId == Planner.HEALTH }.meshPort
        val healthService = tree["http"]!!.jsonObject["services"]!!.jsonObject["homegap-mesh-hg-health"]!!.jsonObject
        val healthServers = healthService["loadBalancer"]!!.jsonObject["servers"]!!.jsonArray
        val healthUrl = healthServers.single().jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("https://10.77.0.1:$healthPort", healthUrl)
        assertFalse("homegap-http-hg-health" in http, "the health resource has no public name")
        assertTrue("homegap-health-fallback" in http)
        assertTrue("homegap-mgmt-api" in http)
        assertFalse("homegap-mgmt-ui" in http, "node dashboards stay private by default")
        // Neither router waits for Pangolin.
        val early = ResourceReconciler.yamlToJson(NodeTemplates.dynamicRoutes(desired, emptyMap())).jsonObject
        assertTrue("homegap-frontdoor" in early["tcp"]!!.jsonObject["routers"]!!.jsonObject)
        val earlyHealth = early["http"]!!.jsonObject["routers"]!!.jsonObject["homegap-health"]!!.jsonObject
        assertEquals("homegap-mesh-hg-health", earlyHealth["service"]!!.jsonPrimitive.content)
    }

    @Test
    fun nodesRunTheMaintenancePageWithoutScripts() {
        val node = config.node("na1")!!
        val files = NodeTemplates.staticFiles(config, node, secrets, slot = 0)
        val compose = files.single { it.path == "compose.yml" }.content
        // Standalone nodes have no cluster image, so they pull one only for the page.
        assertTrue("docker.io/library/python:3.13-alpine" in compose)
        assertTrue("network_mode: \"service:gerbil\"" in compose.substringAfter("  maintenance:"))
        assertTrue("./config/maintenance:/maintenance:ro" in compose && "./config/traefik/dynamic:/dynamic" in compose)
        val page = files.single { it.path == "config/maintenance/index.html" }.content
        assertFalse("<script" in page.lowercase(), "the page is static HTML and CSS")
        assertTrue("Back shortly" in page && config.domain in page)
        assertEquals("maintenance", files.single { it.path == "config/maintenance/server.py" }.restarts)
    }

    @Test
    fun nodesCallNoOutsideServiceOnTheirOwn() {
        val files = NodeTemplates.staticFiles(config, config.node("na1")!!, secrets, slot = 0)
            .associateBy { it.path }
        val traefik = files.getValue("config/traefik/traefik_config.yml").content
        assertTrue("checkNewVersion: false" in traefik && "sendAnonymousUsage: false" in traefik, traefik)
        val pangolin = files.getValue("config/config.yml").content
        assertTrue(
            "anonymous_usage: false" in pangolin &&
                "product_updates: false" in pangolin &&
                "new_releases: false" in pangolin,
            pangolin,
        )
        assertTrue(
            "file: \"/app/${NodeTemplates.AI_CATALOG}\"" in pangolin ||
                "file: /app/${NodeTemplates.AI_CATALOG}" in pangolin,
            pangolin,
        )
        assertEquals("{\"data\": []}\n", files.getValue(NodeTemplates.AI_CATALOG).content)
        val compose = files.getValue("compose.yml").content
        assertTrue("checkip.amazonaws.com=127.0.0.1" in compose, compose)
    }

    @Test
    fun aBlueprintWithOnlyCommentsHasNothingToApply() {
        assertNull(ResourceReconciler.blueprintOf("# Shared policies\n# public-policies:\n#   allow: 1\n"))
        assertEquals("1", ResourceReconciler.blueprintOf("allow: 1\n")!!["allow"]!!.jsonPrimitive.content)
        assertEquals("1", ResourceReconciler.blueprintOf("{\"allow\": 1}")!!["allow"]!!.jsonPrimitive.content)
    }
}
