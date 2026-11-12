package homegap

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import homegap.model.Planner
import homegap.provision.MaintenancePage
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.provision.YamlEmitter
import homegap.reconcile.ResourceReconciler
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TemplatesTest {
    private val cfg = testConfig()
    private val secrets = NodeSecrets("a".repeat(32), "secret\"quote", "admin@test.example.net", "p'w\"d", "bk")

    @Test
    fun yamlEmitterRoundTripsThroughARealParser() {
        val tree = buildJsonObject {
            put("plain", "x"); put("quote", "a\"b'c: #d"); put("num", 5); put("bool", true); putJsonArray("empty") {}
            putJsonObject("nested") { putJsonArray("list") { add("one"); addJsonObject { put("k", "v"); put("k2", 2) } } }
            put("rule", "Host(`a.b`) && PathPrefix(`/api`)")
        }
        val yaml = YamlEmitter.emit(tree, "header")
        val back = ResourceReconciler.yamlToJson(yaml)
        assertEquals(tree["quote"], back.jsonObject["quote"])
        assertEquals(tree["rule"], back.jsonObject["rule"])
        assertEquals("v", back.jsonObject["nested"]!!.jsonObject["list"]!!.jsonArray[1].jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyRenderedFileIsValidYaml() {
        val game = container("game", "homegap.expose" to "game.test.example.net", "homegap.port" to "27015", "homegap.protocol" to "udp")
        val plan = Planner.plan(cfg, listOf(TRAEFIK, game))
        val nd = plan.nodes.getValue("na1")
        val files = NodeTemplates.staticFiles(cfg, cfg.node("na1")!!, secrets, nd.rawPorts, 0)
        for (f in files.filter { it.path.endsWith(".yml") || it.path.endsWith(".yaml") }) {
            val node = Yaml.default.parseToYamlNode(f.content)
            assertTrue(node is YamlMap, "${f.path} should parse as a mapping")
        }
        val compose = files.single { it.path == "compose.yml" }.content
        assertTrue("\"27015:27015/udp\"" in compose)
        assertTrue("\"127.0.0.1:3003:3003\"" in compose, "integration API must be loopback only")
        val traefik = files.single { it.path.endsWith("traefik_config.yml") }.content
        assertTrue("udp-27015" in traefik)
        val pangolin = files.single { it.path == "config/config.yml" }.content
        assertTrue("enable_integration_api: true" in pangolin)
        assertTrue("\"secret\\\"quote\"" in pangolin, "secrets are escaped, not interpolated raw")
        assertEquals("0600", files.single { it.path == "config/config.yml" }.mode)
    }

    @Test
    fun dynamicRoutesReferencePangolinServicesById() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK))
        val nd = plan.nodes.getValue("na1")
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7, Planner.HEALTH to 9)
        val yaml = NodeTemplates.dynamicRoutes(nd, ids)
        val tree = ResourceReconciler.yamlToJson(yaml).jsonObject
        val tcp = tree["tcp"]!!.jsonObject["routers"]!!.jsonObject["homegap-frontdoor"]!!.jsonObject
        assertEquals("7-hg-frontdoor-service@http", tcp["service"]!!.jsonPrimitive.content)
        assertEquals(true, tcp["tls"]!!.jsonObject["passthrough"]!!.jsonPrimitive.boolean)
        val http = tree["http"]!!.jsonObject["routers"]!!.jsonObject
        assertEquals("9-hg-health-service@http", http["homegap-health"]!!.jsonObject["service"]!!.jsonPrimitive.content)
        assertTrue("homegap-health-fallback" in http)
        assertTrue("homegap-mgmt-api" in http)
        assertFalse("homegap-mgmt-ui" in http, "node dashboards stay private by default")
        // Without resource ids yet, passthrough routers are omitted rather than pointing nowhere.
        val empty = ResourceReconciler.yamlToJson(NodeTemplates.dynamicRoutes(nd, emptyMap())).jsonObject
        assertNull(empty["tcp"])
    }

    @Test
    fun nodesRunTheMaintenancePageWithoutScripts() {
        val nd = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("na1")
        val node = cfg.node("na1")!!
        val files = NodeTemplates.staticFiles(cfg, node, secrets, nd.rawPorts, 0)
        val compose = files.single { it.path == "compose.yml" }.content
        // Standalone nodes have no cluster image, so they pull one just for the page.
        assertTrue("docker.io/library/python:3.13-alpine" in compose)
        assertTrue("network_mode: \"service:gerbil\"" in compose.substringAfter("  maintenance:"))
        assertTrue("./config/maintenance:/maintenance:ro" in compose && "./config/traefik/dynamic:/dynamic" in compose)
        val page = files.single { it.path == "config/maintenance/index.html" }.content
        assertFalse("<script" in page.lowercase(), "the page is static HTML and CSS")
        assertTrue("Back shortly" in page && cfg.domain in page)
        assertEquals("maintenance", files.single { it.path == "config/maintenance/server.py" }.restarts)
    }

    @Test
    fun nodesCallNoOutsideServiceOnTheirOwn() {
        val nd = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("na1")
        val files = NodeTemplates.staticFiles(cfg, cfg.node("na1")!!, secrets, nd.rawPorts, 0).associateBy { it.path }
        val traefik = files.getValue("config/traefik/traefik_config.yml").content
        assertTrue("checkNewVersion: false" in traefik && "sendAnonymousUsage: false" in traefik, traefik)
        val pangolin = files.getValue("config/config.yml").content
        assertTrue("anonymous_usage: false" in pangolin && "product_updates: false" in pangolin && "new_releases: false" in pangolin, pangolin)
        assertTrue("file: \"/app/${NodeTemplates.AI_CATALOG}\"" in pangolin || "file: /app/${NodeTemplates.AI_CATALOG}" in pangolin, pangolin)
        assertEquals("{\"data\": []}\n", files.getValue(NodeTemplates.AI_CATALOG).content)
        val compose = files.getValue("compose.yml").content
        assertTrue("checkip.amazonaws.com=127.0.0.1" in compose, compose)
    }
}
