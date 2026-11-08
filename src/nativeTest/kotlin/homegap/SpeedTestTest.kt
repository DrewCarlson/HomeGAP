package homegap

import homegap.config.ConfigLoader
import homegap.model.Planner
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.speedtest.SpeedTestTokens
import homegap.speedtest.TunnelSpeedTest
import homegap.tunnels.TunnelSpec
import kotlin.test.*

class SpeedTestTest {
    private val cfg = ConfigLoader.parse(
        TEST_CONFIG + "\nspeedtest:\n  enabled: true\ntunnels:\n  mtu: 1420\n  native: true\n"
    )
    private val secrets = NodeSecrets("a".repeat(32), "s", "admin@test.example.net", "p", "bk")

    @Test
    fun perNodeHostnamesGetDnsAndAutoRidesTheWildcard() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK))
        val st = plan.dns.filter { it.purpose == "speedtest" }
        assertEquals(setOf("speedtest-na1.test.example.net", "speedtest-as1.test.example.net"), st.map { it.fqdn }.toSet())
        assertTrue(st.all { !it.geo && it.monitor == "none" })
        assertEquals("203.0.113.10", st.single { it.fqdn.startsWith("speedtest-na1") && it.type == "A" }.value)
        // wildcard front door: no separate auto record, no extra SNI hosts
        assertTrue(plan.dns.none { it.fqdn == "speedtest.test.example.net" })
        assertEquals(listOf("test.example.net"), plan.nodes.getValue("na1").sniRoutes.single().exactHosts)
    }

    @Test
    fun nonWildcardFrontDoorCarriesSpeedtestHostsExplicitly() {
        val fd = container("traefik", "homegap.expose" to "test.example.net", "homegap.protocol" to "https", "homegap.tls" to "passthrough")
        val plan = Planner.plan(cfg, listOf(fd))
        val hosts = plan.nodes.getValue("na1").sniRoutes.single().exactHosts
        assertTrue("speedtest.test.example.net" in hosts && "speedtest-na1.test.example.net" in hosts)
        assertTrue(plan.dns.any { it.fqdn == "speedtest.test.example.net" && it.geo })
    }

    @Test
    fun autoEntryRedirectsToTheNodesOwnHostname() {
        val n = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("as1")
        val routes = NodeTemplates.dynamicRoutes(cfg, n, emptyMap())
        assertTrue("Host(`speedtest.test.example.net`)" in routes)
        assertTrue("https://speedtest-as1.test.example.net/?via=auto" in routes)
    }

    @Test
    fun tuningRendersGerbilMtuAndSysctls() {
        val node = cfg.node("na1")!!
        val files = NodeTemplates.staticFiles(cfg, node, secrets, emptyList(), 0)
        assertTrue(files.single { it.path == "compose.yml" }.content.contains("--mtu=1420"))
        assertTrue(NodeTemplates.sysctlConf().contains("tcp_congestion_control = bbr"))
        // The edge namespace (Gerbil + Traefik) gets its own TCP buffers.
        val compose = NodeTemplates.compose(cfg, emptyList())
        assertTrue("net.ipv4.tcp_wmem" in compose && "4096 65536 67108864" in compose)
        assertTrue("net.ipv4.tcp_reordering" in compose)
        assertTrue(TunnelSpeedTest.script("h", "t", 8, 4).contains("seq 1 4"))
        val off = ConfigLoader.parse(TEST_CONFIG + "\npangolin:\n  tcp_tuning: false\n")
        assertFalse(NodeTemplates.compose(off, emptyList()).contains("sysctls"))
        assertFalse(NodeTemplates.compose(off, emptyList()).contains("--mtu"))
    }

    @Test
    fun mtuIsValidated() {
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(TEST_CONFIG + "\ntunnels:\n  mtu: 9000\n") }
    }

    @Test
    fun parsesTunnelTestOutput() {
        val out = buildString {
            appendLine("P 0.150 0.400 204") // first sample (handshake) dropped
            appendLine("P 0.000 0.100 204")
            appendLine("P 0.000 0.120 204")
            appendLine("P 0.000 0.110 204")
            appendLine("D 100000000 0.2 8.2 200")
            appendLine("{\"bytes\":50000000,\"ms\":8000}")
            appendLine("U 200")
        }
        val p = TunnelSpeedTest.parse(out, "speedtest-na1.test.example.net")
        assertNull(p.error)
        assertEquals(3, p.samples)
        assertEquals(110.0, p.pingMs!!, 0.01)
        assertEquals(15.0, p.jitterMs!!, 0.01)
        assertEquals(100.0, p.downMbps!!, 0.01)
        assertEquals(50.0, p.upMbps!!, 0.01)
    }

    @Test
    fun sumsParallelStreams() {
        val out = buildString {
            appendLine("P 0 0.1 204"); appendLine("P 0 0.1 204")
            appendLine("D 50000000 0.2 8.2 200")
            appendLine("D 25000000 0.2 8.2 200")
            appendLine("{\"bytes\":20000000,\"ms\":8000}"); appendLine("U 200")
            appendLine("{\"bytes\":10000000,\"ms\":8000}"); appendLine("U 200")
        }
        val p = TunnelSpeedTest.parse(out, "h", streams = 2)
        assertNull(p.error)
        assertEquals(75.0, p.downMbps!!, 0.01)
        assertEquals(listOf(50L, 25L), p.downStreams.map { kotlin.math.round(it).toLong() })
        assertEquals(30.0, p.upMbps!!, 0.01)
    }

    @Test
    fun reportsMissingStreams() {
        val p = TunnelSpeedTest.parse("P 0 0.1 204\nD 50000000 0.2 8.2 200\nD 0 0 0 000\n", "h", streams = 2)
        assertEquals(50.0, p.downMbps!!, 0.01)
        assertTrue(p.error!!.contains("download"))
    }

    @Test
    fun explainsCommonFailures() {
        val p = TunnelSpeedTest.parse("P 0 0 404\nD 9 0 1 404\nU 404\n", "h")
        assertTrue(p.error!!.contains("no route for h"))
        assertNull(p.downMbps)
    }

    @Test
    fun tokensExpireAndRevoke() {
        val t = SpeedTestTokens()
        val (a, _) = t.issue(60_000)
        assertTrue(t.valid(a))
        assertFalse(t.valid("nope")); assertFalse(t.valid(null))
        t.revoke(a)
        assertFalse(t.valid(a))
        val (b, _) = t.issue(-1)
        assertFalse(t.valid(b))
    }

    @Test
    fun tunnelSpecDefaultsStayCompatible() {
        // Specs written before tuning existed must decode and compare equal to untuned ones.
        val old = """{"nodeId":"na1","endpoint":"https://x","newtId":"i","newtSecret":"s","adminPort":47900}"""
        val spec = homegap.tunnels.TunnelFiles.json.decodeFromString(TunnelSpec.serializer(), old)
        assertNull(spec.mtu); assertNull(spec.nativeInterface)
    }
}
