package homegap

import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.clients.DnsWire
import homegap.clients.SshPool
import homegap.model.DesiredDnsRecord
import homegap.model.Planner
import homegap.reconcile.*
import homegap.state.StateStore
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlin.test.*

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun HttpRequestData.text() = (body as TextContent).text

class ReconcilerTest {
    private fun reconcileContext(
        dryRun: Boolean = false,
        state: StateStore = StateStore(tmpPath("state")),
    ) = ReconcileContext(
        config = testConfig(),
        state = state,
        log = ReconcileLog(),
        ssh = SshPool(tmpPath("ssh"), tmpPath("kh")),
        dryRun = dryRun,
        diff = DiffCollector(),
    )

    private fun bunnyClient(http: HttpClient) = BunnyClient(http, baseUrl = "https://bunny.invalid", apiKey = "k")

    private class FakeBunny {
        val records = mutableListOf<BunnyRecord>()
        val calls = mutableListOf<String>()
        var nextId = 1000L
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            when (request.method) {
                HttpMethod.Get if path == "/dnszone" -> respondJson("""{"Items":[{"Id":7,"Domain":"example.net"}]}""")
                HttpMethod.Get if path == "/dnszone/7" -> respondJson(zone())
                HttpMethod.Put if path == "/dnszone/7/records" -> respondJson(add(request))
                HttpMethod.Post if path.startsWith("/dnszone/7/records/") -> {
                    replace(request)
                    respond("", HttpStatusCode.NoContent)
                }
                HttpMethod.Delete -> {
                    val id = path.substringAfterLast('/').toLong()
                    records.removeAll { it.id == id }
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("unexpected ${request.method} $path")
            }
        }

        private fun zone(): String {
            val zone = buildJsonObject {
                put("Id", 7)
                put("Domain", "example.net")
                put("Records", json.encodeToJsonElement(RECORD_LIST, records))
            }
            return json.encodeToString(JsonObject.serializer(), zone)
        }

        private fun add(request: HttpRequestData): String {
            val record = json.decodeFromString(BunnyRecord.serializer(), request.text()).copy(id = nextId++)
            records += record
            return json.encodeToString(BunnyRecord.serializer(), record)
        }

        private fun replace(request: HttpRequestData) {
            val record = json.decodeFromString(BunnyRecord.serializer(), request.text())
            for (index in records.indices) {
                if (records[index].id == record.id) {
                    records[index] = record
                }
            }
        }

        private companion object {
            val RECORD_LIST = ListSerializer(BunnyRecord.serializer())
        }
    }

    @Test
    fun dnsCreatesConvergesAndCleansUp() = runTest {
        val bunny = FakeBunny()
        val http = HttpClient(bunny.engine)
        // An unmanaged record collides with the managed wildcard set. A second record is unrelated.
        bunny.records += BunnyRecord(id = 1, type = 0, name = "*.test", value = "192.0.2.99")
        bunny.records += BunnyRecord(id = 2, type = 3, name = "txt", value = "keep-me")
        val ctx = reconcileContext()
        val desired = Planner.plan(ctx.config, listOf(TRAEFIK)).dns
        DnsReconciler(ctx, bunnyClient(http)).reconcile(desired, emptySet())

        val managed = bunny.records.filter { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true }
        assertEquals(desired.size, managed.size)
        assertTrue(bunny.records.none { it.value == "192.0.2.99" }, "colliding unmanaged record taken over")
        assertTrue(bunny.records.any { it.value == "keep-me" }, "unrelated records are left alone")
        val geo = managed.filter { it.name == "test" && it.typeName != "CAA" }
        val caaTags = managed.filter { it.typeName == "CAA" }.mapNotNull { it.tag }.sorted()
        assertEquals(listOf("issue", "issuewild"), caaTags)
        assertTrue(
            geo.all {
                it.smartRoutingType == BunnyRecord.ROUTING_GEO &&
                    it.monitorType == BunnyRecord.MONITOR_HTTP &&
                    it.ttl == 60
            },
        )

        // The second pass changes nothing.
        bunny.calls.clear()
        DnsReconciler(ctx, bunnyClient(http)).reconcile(desired, emptySet())
        assertTrue(
            bunny.calls.none { it.startsWith("PUT") || it.startsWith("POST") || it.startsWith("DELETE") },
            "idempotent: ${bunny.calls}",
        )

        // Drift: a person edits a value by hand, and the next pass repairs it.
        val index = bunny.records.indexOfFirst { it.name == "test" && it.value == "203.0.113.10" }
        bunny.records[index] = bunny.records[index].copy(ttl = 3600)
        DnsReconciler(ctx, bunnyClient(http)).reconcile(desired, emptySet())
        assertEquals(60, bunny.records[index].ttl)

        // Without the front door, the reconciler deletes the managed records of the front door.
        val managementOnly = Planner.plan(ctx.config, emptyList()).dns
        DnsReconciler(ctx, bunnyClient(http)).reconcile(managementOnly, emptySet())
        val stillManaged = bunny.records.count { it.comment?.startsWith(DesiredDnsRecord.OWNER_TAG) == true }
        assertEquals(managementOnly.size, stillManaged)
    }

    @Test
    fun dnsReportsRecordsANameserverDoesNotServeYet() = runTest {
        val bunny = FakeBunny()
        val http = HttpClient(bunny.engine)
        val ctx = reconcileContext()
        val desired = Planner.plan(ctx.config, listOf(TRAEFIK)).dns
        val records = desired.groupBy({ it.fqdn to DnsWire.typeCode(it.type) }, { it.value })
        // coco does not have the management record of na1 yet. It answers with the node of the wildcard.
        val probe = NameserverProbe { nameserver, name, type ->
            if (nameserver == "coco.bunny.net" && name == "node-na1.test.example.net") {
                listOf("198.51.100.20")
            } else {
                records[name to type].orEmpty()
            }
        }
        val check = NameserverCheck(probe)
        val observed = DnsReconciler(ctx, bunnyClient(http), check).reconcile(desired, emptySet())

        val lagging = observed.filter { !it.inSync }
        assertEquals(listOf("node-na1.test.example.net"), lagging.map { it.desired?.fqdn })
        assertEquals(
            "${DnsReconciler.NOT_ON_EVERY_NAMESERVER}: coco.bunny.net still answers 198.51.100.20",
            lagging.single().diff,
        )
        assertTrue(ctx.log.recent().any { it.message.contains("is not on every nameserver yet") })
        assertTrue(observed.filter { it.desired?.geo == true }.all { it.inSync }, "geo sets are not compared")
    }

    @Test
    fun dnsDryRunAndPausedNodesNeverWrite() = runTest {
        val bunny = FakeBunny()
        val http = HttpClient(bunny.engine)
        val dryRunCtx = reconcileContext(dryRun = true)
        val desired = Planner.plan(dryRunCtx.config, listOf(TRAEFIK)).dns
        DnsReconciler(dryRunCtx, bunnyClient(http)).reconcile(desired, emptySet())
        assertTrue(bunny.records.isEmpty())
        assertTrue(dryRunCtx.diff.all().any { it.action == "create" })

        val state = StateStore(tmpPath("state"))
        state.update { it.copy(pauses = it.pauses.copy(nodes = setOf("na1"))) }
        val pausedCtx = reconcileContext(state = state)
        DnsReconciler(pausedCtx, bunnyClient(http)).reconcile(desired, emptySet())
        assertTrue(bunny.records.none { it.comment?.endsWith("node=na1") == true })
        assertTrue(bunny.records.any { it.comment?.endsWith("node=as1") == true })
    }
}
