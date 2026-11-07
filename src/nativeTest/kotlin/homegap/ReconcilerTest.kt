package homegap

import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.clients.PangolinClient
import homegap.clients.SshPool
import homegap.model.DesiredDnsRecord
import homegap.model.Planner
import homegap.reconcile.*
import homegap.state.StateStore
import homegap.tunnels.TunnelSupervisor
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class ReconcilerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ctx(http: HttpClient, dryRun: Boolean = false, state: StateStore = StateStore(tmpPath("state"))) =
        ReconcileContext(testConfig(), state, ReconcileLog(), SshPool(tmpPath("ssh"), tmpPath("kh")), http, http, dryRun, DiffCollector())

    private class FakeBunny {
        val records = mutableListOf<BunnyRecord>()
        val calls = mutableListOf<String>()
        var nextId = 1000L
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            calls += "${req.method.value} $path"
            val j = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            fun ok(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            when {
                req.method == HttpMethod.Get && path == "/dnszone" -> ok("""{"Items":[{"Id":7,"Domain":"example.net"}]}""")
                req.method == HttpMethod.Get && path == "/dnszone/7" ->
                    ok(j.encodeToString(JsonObject.serializer(), buildJsonObject {
                        put("Id", 7); put("Domain", "example.net")
                        put("Records", j.encodeToJsonElement(ListSerializer, records))
                    }))
                req.method == HttpMethod.Put && path == "/dnszone/7/records" -> {
                    val r = j.decodeFromString(BunnyRecord.serializer(), (req.body as io.ktor.http.content.TextContent).text).copy(id = nextId++)
                    records += r
                    ok(j.encodeToString(BunnyRecord.serializer(), r))
                }
                req.method == HttpMethod.Post && path.startsWith("/dnszone/7/records/") -> {
                    val r = j.decodeFromString(BunnyRecord.serializer(), (req.body as io.ktor.http.content.TextContent).text)
                    for (i in records.indices) if (records[i].id == r.id) records[i] = r
                    respond("", HttpStatusCode.NoContent)
                }
                req.method == HttpMethod.Delete -> {
                    val id = path.substringAfterLast('/').toLong()
                    records.removeAll { it.id == id }
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("unexpected ${req.method} $path")
            }
        }
        companion object { val ListSerializer = kotlinx.serialization.builtins.ListSerializer(BunnyRecord.serializer()) }
    }

    @Test
    fun dnsCreatesConvergesAndCleansUp() = runTest {
        val bunny = FakeBunny()
        val http = HttpClient(bunny.engine)
        // a stray unmanaged record that collides with the managed wildcard set, plus an unrelated one
        bunny.records += BunnyRecord(id = 1, type = 0, name = "*.test", value = "192.0.2.99")
        bunny.records += BunnyRecord(id = 2, type = 3, name = "txt", value = "keep-me")
        val c = ctx(http)
        val desired = Planner.plan(c.config, listOf(TRAEFIK)).dns
        DnsReconciler(c, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(desired, emptySet())

        val managed = bunny.records.filter { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true }
        assertEquals(desired.size, managed.size)
        assertTrue(bunny.records.none { it.value == "192.0.2.99" }, "colliding unmanaged record taken over")
        assertTrue(bunny.records.any { it.value == "keep-me" }, "unrelated records are left alone")
        val geo = managed.filter { it.name == "test" }
        assertTrue(geo.all { it.smartRoutingType == BunnyRecord.ROUTING_GEO && it.monitorType == BunnyRecord.MONITOR_HTTP && it.ttl == 60 })

        // Second pass is a no-op.
        bunny.calls.clear()
        DnsReconciler(c, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(desired, emptySet())
        assertTrue(bunny.calls.none { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("DELETE") }, "idempotent: ${bunny.calls}")

        // Drift: someone edits a value by hand -> repaired; removing the front door deletes managed records.
        val idx = bunny.records.indexOfFirst { it.name == "test" && it.value == "203.0.113.10" }
        bunny.records[idx] = bunny.records[idx].copy(ttl = 3600)
        DnsReconciler(c, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(desired, emptySet())
        assertEquals(60, bunny.records[idx].ttl)

        val onlyMgmt = Planner.plan(c.config, emptyList()).dns
        DnsReconciler(c, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(onlyMgmt, emptySet())
        assertEquals(onlyMgmt.size, bunny.records.count { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true })
    }

    @Test
    fun dnsDryRunAndPausedNodesNeverWrite() = runTest {
        val bunny = FakeBunny()
        val http = HttpClient(bunny.engine)
        val c = ctx(http, dryRun = true)
        val desired = Planner.plan(c.config, listOf(TRAEFIK)).dns
        DnsReconciler(c, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(desired, emptySet())
        assertTrue(bunny.records.isEmpty())
        assertTrue(c.diff.all().any { it.action == "create" })

        val state = StateStore(tmpPath("state"))
        state.update { it.copy(pauses = it.pauses.copy(nodes = setOf("na1"))) }
        val c2 = ctx(http, state = state)
        DnsReconciler(c2, BunnyClient(http, "https://bunny.invalid", "k")).reconcile(desired, emptySet())
        assertTrue(bunny.records.none { it.comment?.endsWith("node=na1") == true })
        assertTrue(bunny.records.any { it.comment?.endsWith("node=as1") == true })
    }

    @Test
    fun blueprintShapeMatchesPangolinSchema() {
        val c = ctx(HttpClient(MockEngine { error("no http") }))
        val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "8080", "homegap.auth" to "sso", "homegap.roles" to "Member", "homegap.healthcheck" to "/healthz")
        val ssh = container("ssh", "homegap.expose" to "ssh.test.example.net", "homegap.port" to "22", "homegap.protocol" to "tcp", "homegap.access" to "private")
        val nd = Planner.plan(c.config, listOf(TRAEFIK, wiki, ssh)).nodes.getValue("na1")
        val bp = ResourceReconciler(c).buildBlueprint(nd.publicResources, nd.privateResources)
        val pub = bp["public-resources"]!!.jsonObject
        val fd = pub["hg-frontdoor"]!!.jsonObject
        assertEquals("tcp", fd["mode"]!!.jsonPrimitive.content)
        assertNull(fd["targets"]!!.jsonArray[0].jsonObject["method"], "tcp targets must not carry a method")
        val w = pub["hg-wiki-test-example-net"]!!.jsonObject
        assertEquals("wiki.test.example.net", w["full-domain"]!!.jsonPrimitive.content)
        assertEquals(true, w["auth"]!!.jsonObject["sso-enabled"]!!.jsonPrimitive.boolean)
        val hc = w["targets"]!!.jsonArray[0].jsonObject["healthcheck"]!!.jsonObject
        assertEquals("/healthz", hc["path"]!!.jsonPrimitive.content)
        assertEquals("homelab", w["targets"]!!.jsonArray[0].jsonObject["site"]!!.jsonPrimitive.content)
        val priv = bp["private-resources"]!!.jsonObject["hg-ssh-test-example-net"]!!.jsonObject
        assertEquals("host", priv["mode"]!!.jsonPrimitive.content)
        assertEquals("22", priv["tcp-ports"]!!.jsonPrimitive.content)
    }

    @Test
    fun resourceReconcilerDeletesOnlyManagedStaleResources() = runTest {
        val deleted = mutableListOf<String>()
        var applied: JsonObject? = null
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            fun ok(data: String) = respond("""{"data":$data,"success":true,"error":false,"message":"ok","status":200}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            when {
                p.endsWith("/resources") -> ok("""{"resources":[
                    {"resourceId":1,"niceId":"hg-frontdoor","mode":"tcp","proxyPort":1,"targets":[{"ip":"traefik","port":443}]},
                    {"resourceId":2,"niceId":"hg-old-service","mode":"http","fullDomain":"old.test.example.net","targets":[]},
                    {"resourceId":3,"niceId":"hand-made","mode":"http","fullDomain":"hand.test.example.net","targets":[]}
                ]}""")
                p.endsWith("/site-resources") -> ok("""{"siteResources":[]}""")
                p.endsWith("/blueprint") -> {
                    val body = Json.parseToJsonElement((req.body as io.ktor.http.content.TextContent).text).jsonObject
                    applied = Json.parseToJsonElement(Base64.decode(body["blueprint"]!!.jsonPrimitive.content).decodeToString()).jsonObject
                    ok("null")
                }
                req.method == HttpMethod.Delete -> { deleted += p; ok("null") }
                else -> error("unexpected $p")
            }
        }
        val http = HttpClient(engine)
        val c = ctx(http)
        val nd = Planner.plan(c.config, listOf(TRAEFIK)).nodes.getValue("na1")
        val ready = ReadyNode(c.config.node("na1")!!, PangolinClient(http, "http://pangolin.invalid/v1", "id.key"))
        // Pause the node so the routes file (SSH) is not written, but run deletion logic: unpause just for resources.
        val rr = ResourceReconciler(c)
        runCatching { rr.reconcile(ready, nd, emptySet()) } // SSH route write fails in tests; API effects are what we assert
        assertNotNull(applied, "proxy-port mismatch triggers a blueprint apply")
        assertEquals(listOf("/v1/resource/2"), deleted, "only stale hg- resources are deleted")
    }

    @Test
    fun newtMetricsParsing() {
        val m = TunnelSupervisor.parseMetrics("""
            # HELP newt_tunnel_bytes_total bytes
            newt_tunnel_bytes_total{direction="ingress",tunnel_id="a"} 1024
            newt_tunnel_bytes_total{direction="egress",tunnel_id="a"} 2048
            newt_tunnel_bytes_total{direction="ingress",tunnel_id="b"} 1
            newt_websocket_connected{site="x"} 1
        """.trimIndent())
        assertEquals(1025, m.bytesIn)
        assertEquals(2048, m.bytesOut)
        assertEquals(true, m.connected)
    }
}
