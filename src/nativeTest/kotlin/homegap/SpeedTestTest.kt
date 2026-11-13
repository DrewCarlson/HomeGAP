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
        // The per-node name rides the front door; the shared one is the node's own.
        assertTrue("speedtest-na1.test.example.net" in hosts && "speedtest.test.example.net" !in hosts)
        assertTrue(plan.dns.any { it.fqdn == "speedtest.test.example.net" && it.geo })
    }

    @Test
    fun theSharedNameIsAnsweredByTheNodeUnderItsOwnName() {
        val n = Planner.plan(cfg, listOf(TRAEFIK)).nodes.getValue("as1")
        assertTrue("speedtest.test.example.net" in n.sniRoutes.single().excludeHosts, "never passed through: the node answers it")
        val routes = NodeTemplates.dynamicRoutes(n, emptyMap())
        // HTTPS on the shared name goes back into the node's own :443 as speedtest-as1, which the
        // front door carries home: the homelab always sees which node it came through.
        assertTrue("Host(`speedtest.test.example.net`)" in routes)
        assertTrue("Host: \"speedtest-as1.test.example.net\"" in routes || "Host: speedtest-as1.test.example.net" in routes, routes)
        assertTrue("serverName: \"speedtest-as1.test.example.net\"" in routes || "serverName: speedtest-as1.test.example.net" in routes)
        assertTrue("https://127.0.0.1:443" in routes)
        assertFalse("via=auto" in routes, "no redirect: the address bar keeps the shared name")
        // Terminate mode needs no Pangolin resource for the shared name either.
        val terminate = container("traefik", "homegap.expose" to "test.example.net", "homegap.wildcard" to "true", "homegap.protocol" to "https", "homegap.tls" to "terminate")
        val t = Planner.plan(cfg, listOf(terminate)).nodes.getValue("as1")
        assertTrue(t.publicResources.none { it.fullDomain == "speedtest.test.example.net" })
        assertTrue("Host(`speedtest.test.example.net`)" in NodeTemplates.dynamicRoutes(t, emptyMap()))
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

    @Test
    fun aTunnelTestShowsTheCpuOfTheNodeInEachPhase() {
        val out = """
            N 2
            P 0 0.1 204
            S d0 100 0 100 800 0 0 0 0
            S d1 300 0 500 1000 0 0 100 100
            D 50000000 0.2 8.2 200
            S u0 300 0 500 1000 0 0 100 100
            S u1 300 0 500 2000 0 0 100 100
        """.trimIndent()
        val p = TunnelSpeedTest.parse(out, "h")
        // Download: 1000 ticks, 200 idle. Kernel is system and softirq.
        assertEquals(homegap.api.NodeCpuDto(cores = 2, busyPct = 80.0, kernelPct = 50.0, stealPct = 10.0), p.downCpu)
        assertEquals(0.0, p.upCpu?.busyPct)
        assertTrue(TunnelSpeedTest.script("h", "t", 8, 4).contains("cpu d0"))
    }

    @Test
    fun aTestWithoutCpuLinesHasNoCpuFigures() {
        val p = TunnelSpeedTest.parse("P 0 0.1 204\nD 50000000 0.2 8.2 200\n", "h")
        assertEquals(null, p.downCpu)
        assertEquals(null, p.upCpu)
    }
}
