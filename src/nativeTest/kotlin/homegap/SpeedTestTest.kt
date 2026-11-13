package homegap

import homegap.api.NodeCpuDto
import homegap.config.ConfigLoader
import homegap.model.Planner
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.speedtest.CpuCounters
import homegap.speedtest.SpeedTestLive
import homegap.speedtest.SpeedTestTokens
import homegap.speedtest.TunnelSpeedTest
import homegap.tunnels.TunnelFiles
import homegap.tunnels.TunnelSpec
import kotlin.math.round
import kotlin.test.*

class SpeedTestTest {
    private val config = ConfigLoader.parse(
        "$TEST_CONFIG\nspeedtest:\n  enabled: true\ntunnels:\n  mtu: 1420\n  native: true\n",
    )
    private val secrets = NodeSecrets(
        setupToken = "a".repeat(32),
        serverSecret = "s",
        adminEmail = "admin@test.example.net",
        adminPassword = "p",
        crowdsecBouncerKey = "bk",
    )

    @Test
    fun perNodeHostnamesGetDnsAndAutoRidesTheWildcard() {
        val plan = Planner.plan(config, listOf(TRAEFIK))
        val speedtestRecords = plan.dns.filter { it.purpose == "speedtest" }
        assertEquals(
            setOf("speedtest-na1.test.example.net", "speedtest-as1.test.example.net"),
            speedtestRecords.map { it.fqdn }.toSet(),
        )
        assertTrue(speedtestRecords.all { !it.geo && it.monitor == "none" })
        val na1Address = speedtestRecords.single { it.fqdn.startsWith("speedtest-na1") && it.type == "A" }.value
        assertEquals("203.0.113.10", na1Address)
        // A wildcard front door needs no separate record for the shared name and no more SNI hosts.
        assertTrue(plan.dns.none { it.fqdn == "speedtest.test.example.net" })
        assertEquals(listOf("test.example.net"), plan.nodes.getValue("na1").sniRoutes.single().exactHosts)
    }

    @Test
    fun nonWildcardFrontDoorCarriesSpeedtestHostsExplicitly() {
        val frontDoor = container(
            "traefik",
            "homegap.expose" to "test.example.net",
            "homegap.protocol" to "https",
            "homegap.tls" to "passthrough",
        )
        val plan = Planner.plan(config, listOf(frontDoor))
        val hosts = plan.nodes.getValue("na1").sniRoutes.single().exactHosts
        // The name of each node goes through the front door. The node answers the shared name itself.
        assertTrue("speedtest-na1.test.example.net" in hosts && "speedtest.test.example.net" !in hosts)
        assertTrue(plan.dns.any { it.fqdn == "speedtest.test.example.net" && it.geo })
    }

    @Test
    fun theSharedNameIsAnsweredByTheNodeUnderItsOwnName() {
        val node = Planner.plan(config, listOf(TRAEFIK)).nodes.getValue("as1")
        assertTrue(
            "speedtest.test.example.net" in node.sniRoutes.single().excludeHosts,
            "never passed through: the node answers it",
        )
        val routes = NodeTemplates.dynamicRoutes(node, emptyMap())
        // HTTPS on the shared name goes back into port 443 of the node as speedtest-as1. The front
        // door carries that name home, and the homelab sees the node of each request.
        assertTrue("Host(`speedtest.test.example.net`)" in routes)
        assertTrue(
            "Host: \"speedtest-as1.test.example.net\"" in routes || "Host: speedtest-as1.test.example.net" in routes,
            routes,
        )
        assertTrue(
            "serverName: \"speedtest-as1.test.example.net\"" in routes ||
                "serverName: speedtest-as1.test.example.net" in routes,
        )
        assertTrue("https://127.0.0.1:443" in routes)
        assertFalse("via=auto" in routes, "no redirect: the address bar keeps the shared name")
        // The terminate mode also needs no Pangolin resource for the shared name.
        val terminate = container(
            "traefik",
            "homegap.expose" to "test.example.net",
            "homegap.wildcard" to "true",
            "homegap.protocol" to "https",
            "homegap.tls" to "terminate",
        )
        val terminateNode = Planner.plan(config, listOf(terminate)).nodes.getValue("as1")
        assertTrue(terminateNode.publicResources.none { it.fullDomain == "speedtest.test.example.net" })
        assertTrue("Host(`speedtest.test.example.net`)" in NodeTemplates.dynamicRoutes(terminateNode, emptyMap()))
    }

    @Test
    fun tuningRendersGerbilMtuAndSysctls() {
        val node = config.node("na1")!!
        val files = NodeTemplates.staticFiles(config, node, secrets, rawPorts = emptyList(), slot = 0)
        assertTrue(files.single { it.path == "compose.yml" }.content.contains("--mtu=1420"))
        assertTrue(NodeTemplates.sysctlConf().contains("tcp_congestion_control = bbr"))
        // The edge namespace (Gerbil + Traefik) gets its own TCP buffers.
        val compose = NodeTemplates.compose(config, emptyList())
        assertTrue("net.ipv4.tcp_wmem" in compose && "4096 65536 67108864" in compose)
        assertTrue("net.ipv4.tcp_reordering" in compose)
        assertTrue(TunnelSpeedTest.script(host = "h", token = "t", seconds = 8, streams = 4).contains("seq 1 4"))
        val untuned = ConfigLoader.parse("$TEST_CONFIG\npangolin:\n  tcp_tuning: false\n")
        assertFalse(NodeTemplates.compose(untuned, emptyList()).contains("sysctls"))
        assertFalse(NodeTemplates.compose(untuned, emptyList()).contains("--mtu"))
    }

    @Test
    fun mtuIsValidated() {
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse("$TEST_CONFIG\ntunnels:\n  mtu: 9000\n") }
    }

    @Test
    fun parsesTunnelTestOutput() {
        val output = buildString {
            // The parser drops the first sample, which has the handshake.
            appendLine("P 0.150 0.400 204")
            appendLine("P 0.000 0.100 204")
            appendLine("P 0.000 0.120 204")
            appendLine("P 0.000 0.110 204")
            appendLine("D 100000000 0.2 8.2 200")
            appendLine("{\"bytes\":50000000,\"ms\":8000}")
            appendLine("U 200")
        }
        val parsed = TunnelSpeedTest.parse(output, "speedtest-na1.test.example.net")
        assertNull(parsed.error)
        assertEquals(3, parsed.samples)
        assertEquals(110.0, parsed.pingMs!!, 0.01)
        assertEquals(15.0, parsed.jitterMs!!, 0.01)
        assertEquals(100.0, parsed.downMbps!!, 0.01)
        assertEquals(50.0, parsed.upMbps!!, 0.01)
    }

    @Test
    fun sumsParallelStreams() {
        val output = buildString {
            appendLine("P 0 0.1 204")
            appendLine("P 0 0.1 204")
            appendLine("D 50000000 0.2 8.2 200")
            appendLine("D 25000000 0.2 8.2 200")
            appendLine("{\"bytes\":20000000,\"ms\":8000}")
            appendLine("U 200")
            appendLine("{\"bytes\":10000000,\"ms\":8000}")
            appendLine("U 200")
        }
        val parsed = TunnelSpeedTest.parse(output, "h", streams = 2)
        assertNull(parsed.error)
        assertEquals(75.0, parsed.downMbps!!, 0.01)
        assertEquals(listOf(50L, 25L), parsed.downStreams.map { round(it).toLong() })
        assertEquals(30.0, parsed.upMbps!!, 0.01)
    }

    @Test
    fun reportsMissingStreams() {
        val parsed = TunnelSpeedTest.parse("P 0 0.1 204\nD 50000000 0.2 8.2 200\nD 0 0 0 000\n", "h", streams = 2)
        assertEquals(50.0, parsed.downMbps!!, 0.01)
        assertTrue(parsed.error!!.contains("download"))
    }

    @Test
    fun explainsCommonFailures() {
        val parsed = TunnelSpeedTest.parse("P 0 0 404\nD 9 0 1 404\nU 404\n", "h")
        assertTrue(parsed.error!!.contains("no route for h"))
        assertNull(parsed.downMbps)
    }

    @Test
    fun tokensExpireAndRevoke() {
        val tokens = SpeedTestTokens()
        val (token, _) = tokens.issue(60_000)
        assertTrue(tokens.valid(token))
        assertFalse(tokens.valid("nope"))
        assertFalse(tokens.valid(null))
        tokens.revoke(token)
        assertFalse(tokens.valid(token))
        val (expired, _) = tokens.issue(-1)
        assertFalse(tokens.valid(expired))
    }

    @Test
    fun tunnelSpecDefaultsStayCompatible() {
        // Specs written before tuning existed must decode and compare equal to untuned ones.
        val old = """{"nodeId":"na1","endpoint":"https://x","newtId":"i","newtSecret":"s","adminPort":47900}"""
        val spec = TunnelFiles.json.decodeFromString(TunnelSpec.serializer(), old)
        assertNull(spec.mtu)
        assertNull(spec.nativeInterface)
    }

    @Test
    fun aTunnelTestShowsTheCpuOfTheNodeInEachPhase() {
        val output = """
            P 0 0.1 204
            S d0 cpu  100 0 100 800 0 0 0 0 0 0
            S d0 cpu0 50 0 50 400 0 0 0 0 0 0
            S d0 cpu1 50 0 50 400 0 0 0 0 0 0
            S d1 cpu  300 0 500 1000 0 0 100 100 0 0
            S d1 cpu0 250 0 250 400 0 0 0 0 0 0
            S d1 cpu1 50 0 250 600 0 0 100 100 0 0
            D 50000000 0.2 8.2 200
            S u0 cpu  300 0 500 1000 0 0 100 100 0 0
            S u1 cpu  300 0 500 2000 0 0 100 100 0 0
        """.trimIndent()
        val parsed = TunnelSpeedTest.parse(output, "h")
        // Download: 1000 ticks, 200 idle. Kernel is system and softirq. Core 0 has no idle tick.
        assertEquals(
            NodeCpuDto(cores = 2, busyPct = 80.0, kernelPct = 50.0, stealPct = 10.0, maxCorePct = 100.0),
            parsed.downCpu,
        )
        assertEquals(0.0, parsed.upCpu?.busyPct)
        assertTrue(TunnelSpeedTest.script(host = "h", token = "t", seconds = 8, streams = 4).contains("cpu d0"))
    }

    @Test
    fun aTestWithoutCpuLinesHasNoCpuFigures() {
        val parsed = TunnelSpeedTest.parse("P 0 0.1 204\nD 50000000 0.2 8.2 200\n", "h")
        assertEquals(null, parsed.downCpu)
        assertEquals(null, parsed.upCpu)
    }

    @Test
    fun theHomelabCpuIsReadAtTheChangeOfEachPhase() {
        var idle = 0L
        // Each read: 100 more ticks, of which [idle] are idle.
        var total = 0L
        var idleSum = 0L
        val live = SpeedTestLive(
            cpu = {
                total += 100
                idleSum += idle
                val counters = "${total - idleSum} 0 0 $idleSum 0 0 0 0"
                CpuCounters.parse("cpu  $counters\ncpu0 $counters\n")
            },
        )
        live.start("t", "na1")
        live.bytes("t", upload = false, n = 10)
        idle = 25
        live.bytes("t", upload = true, n = 10)
        idle = 100
        val (down, up) = live.hostCpu("t")
        assertEquals(75.0, down?.busyPct)
        assertEquals(0.0, up?.busyPct)
        assertEquals(null to null, live.hostCpu("other"))
    }
}
