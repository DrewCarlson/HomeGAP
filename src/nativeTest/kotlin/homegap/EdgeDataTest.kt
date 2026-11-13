package homegap

import homegap.api.CheckDto
import homegap.api.Health
import homegap.api.MetricPointDto
import homegap.api.NodeStatusDto
import homegap.api.ServiceNodeHealthDto
import homegap.api.ServiceStatusDto
import homegap.api.StatusSnapshot
import homegap.api.TunnelStatusDto
import homegap.reconcile.Controller
import homegap.reconcile.EdgeMetrics
import homegap.reconcile.HomelabProbe
import homegap.reconcile.attentionItems
import homegap.reconcile.attentionSince
import homegap.reconcile.parseServiceProbe
import homegap.reconcile.withProbe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EdgeDataTest {
    private fun node(id: String, health: Health, vararg checks: CheckDto) = NodeStatusDto(
        id = id,
        region = "AS",
        provider = "vps",
        host = "h",
        managementHost = "m",
        health = health,
        reachable = true,
        checks = checks.toList(),
    )

    private fun snapshot(at: Long, vararg nodes: NodeStatusDto, tunnelUp: Boolean = true) = StatusSnapshot(
        generatedAt = at,
        version = "1",
        dryRun = false,
        project = "p",
        domain = "example.net",
        paused = false,
        nodes = nodes.toList(),
        tunnels = listOf(
            TunnelStatusDto(
                nodeId = "a1",
                siteNiceId = "a1",
                endpoint = "e",
                up = tunnelUp,
                processRunning = true,
                siteOnline = tunnelUp,
            ),
        ),
    )

    @Test
    fun attentionPutsTheWorstFirstAndKeepsWhenItStarted() {
        val slow = CheckDto(
            name = "pangolin-api",
            health = Health.DEGRADED,
            detail = "slow",
            value = 412.0,
            threshold = 300.0,
            unit = "ms",
        )
        val first = attentionItems(
            snapshot = snapshot(1_000, node("sg1", Health.DEGRADED, slow), tunnelUp = false),
            since = emptyMap(),
        )
        assertEquals(listOf("tunnel:a1", "node:sg1:degraded"), first.map { it.id })
        assertEquals("Pangolin API probe 412 ms", first[1].detail)

        val seen = attentionSince(first, emptyMap())
        val later = attentionItems(
            snapshot = snapshot(9_000, node("sg1", Health.DEGRADED, slow), tunnelUp = false),
            since = seen,
        )
        assertEquals(listOf(1_000L, 1_000L), later.map { it.since })
    }

    @Test
    fun aServiceThatFailsOnEveryEdgeIsOneItem() {
        fun service(vararg edges: ServiceNodeHealthDto) = ServiceStatusDto(
            key = "git",
            container = "git",
            hostname = "git.example.net",
            wildcard = false,
            protocol = "http",
            tls = "terminate",
            access = "public",
            auth = "none",
            regions = emptyList(),
            kind = "container",
            paused = false,
            perNode = edges.toList(),
        )
        val down = ServiceNodeHealthDto(nodeId = "a1", health = Health.DOWN, detail = "no answer", code = 0)
        val everywhere = snapshot(1_000).copy(services = listOf(service(down, down.copy(nodeId = "b1"))))
        val item = attentionItems(everywhere, emptyMap()).single()
        assertEquals("git.example.net failing on every edge", item.title)
        assertEquals("no answer", item.detail)

        val oneEdge = snapshot(1_000).copy(services = listOf(service(down, ServiceNodeHealthDto("b1", Health.OK))))
        assertEquals("git.example.net failing on a1", attentionItems(oneEdge, emptyMap()).single().title)
    }

    @Test
    fun serviceProbesFoldIntoTheEdgeHealth() {
        val ok = ServiceNodeHealthDto("a1", Health.OK, "online")
        assertEquals(Health.DOWN, ok.withProbe(Controller.ServiceProbe(code = 502, ms = 40.0)).health)
        assertEquals("no answer", ok.withProbe(Controller.ServiceProbe(code = 0, ms = 3000.0)).detail)
        assertEquals(Health.DEGRADED, ok.withProbe(Controller.ServiceProbe(code = 200, ms = 1500.0)).health)
        val fine = ok.withProbe(Controller.ServiceProbe(code = 302, ms = 31.0))
        assertEquals(Health.OK, fine.health)
        assertEquals(31.0, fine.ms)
    }

    @Test
    fun readsProbeLines() {
        val (host, probe) = parseServiceProbe("H media.example.com 200 0.034512".split(' ', limit = 3))!!
        assertEquals("media.example.com", host)
        assertEquals(200, probe.code)
        assertEquals(34.512, probe.ms, 0.001)
        assertNull(parseServiceProbe(listOf("H")))
    }

    @Test
    fun metricsKeepTheNewestSamples() = runTest {
        val metrics = EdgeMetrics(capacity = 3)
        for (second in 1L..5L) {
            metrics.record(
                atSeconds = second,
                points = listOf(MetricPointDto("a1", EdgeMetrics.API, second * 10.0)),
            )
        }
        val series = metrics.range(sinceSeconds = 0).single()
        assertEquals(listOf(3L, 4L, 5L), series.times)
        assertEquals(listOf(30.0, 40.0, 50.0), series.values)
        assertEquals(listOf(5L), metrics.range(sinceSeconds = 5).single().times)
    }
}

class HomelabProbeTest {
    @Test
    fun countsMissesAndOutages() {
        val probe = HomelabProbe()
        probe.record(peer = "a1", at = 1_000, rttMs = 20.0)
        for (i in 1..3) probe.record(peer = "a1", at = 1_000L + i * 60_000, rttMs = null)
        val down = probe.view().getValue("a1")
        assertNull(down.rttMs)
        assertEquals(75.0, down.lossPercent)
        assertEquals(null, down.outages.single().endAt, "out right now")
        probe.record(peer = "a1", at = 250_000, rttMs = 22.0)
        val back = probe.view().getValue("a1")
        assertEquals(22.0, back.rttMs)
        assertEquals(250_000L, back.outages.single().endAt)
    }
}
