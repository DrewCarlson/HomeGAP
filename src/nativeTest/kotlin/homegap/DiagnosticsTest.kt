package homegap

import homegap.api.Health
import homegap.diagnostics.NodeDiagnostics
import homegap.platform.Subprocess
import homegap.provision.ClusterProxy
import homegap.provision.ClusterTemplates
import homegap.provision.MeshProbe
import homegap.reconcile.ClusterReconciler
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DiagnosticsTest {
    private fun bashSyntax(script: String) = runBlocking {
        val r = Subprocess.run(listOf("bash", "-n"), stdin = script)
        assertEquals(0, r.exitCode, r.stderr)
    }

    @Test
    fun logsComeFromTheRightProjectAndRejectOddInput() {
        val s = NodeDiagnostics.Scripts
        assertTrue("cd '/opt/homegap/cluster'" in s.logs("/opt/homegap", "haproxy", "1h", 200))
        assertTrue("cd '/opt/homegap/exit'" in s.logs("/opt/homegap", "newt", "15m", 200))
        assertTrue("--since 30m --tail 300 pangolin" in s.logs("/opt/homegap", "pangolin", "30m", 300))
        assertFailsWith<IllegalArgumentException> { s.logs("/opt/homegap", "pangolin; reboot", "30m", 10) }
        assertFailsWith<IllegalArgumentException> { s.logs("/opt/homegap", "pangolin", "1h; reboot", 10) }
        assertFailsWith<IllegalArgumentException> { s.logs("/opt/homegap", "pangolin", "1h", 0) }
    }

    @Test
    fun theBundleIsValidShellAndNeverPrintsTheMeshKey() {
        val bundle = NodeDiagnostics.Scripts.bundle("/opt/homegap")
        bashSyntax(bundle)
        assertTrue("grep -v 'private key'" in bundle)
        assertFalse("docker compose config" in bundle, "would print the stack's secrets")
    }

    private val csv = """
        |# pxname,svname,qcur,qmax,scur,smax,slim,stot,bin,bout,dreq,dresp,ereq,econ,eresp,wretr,wredis,status,weight,act,bck,chkfail,chkdown,lastchg,downtime
        |postgres-primary,FRONTEND,,,0,1,2000,1,0,0,0,0,0,,,,,OPEN,,,,,,,
        |postgres-primary,homelab,0,0,0,1,,1,0,0,,0,,0,0,0,0,UP,1,1,0,3,1,40,15
        |postgres-primary,as1,0,0,0,0,,0,0,0,,0,,0,0,0,0,DOWN,1,1,0,1,1,900,900
        |postgres-primary,BACKEND,0,0,0,1,200,1,0,0,0,0,,0,0,0,0,UP,1,1,0,,2,40,15
        |valkey-primary,homelab,0,0,0,1,,1,0,0,,0,,0,0,0,0,DOWN,1,1,0,3,2,3,30
        |valkey-primary,BACKEND,0,0,0,1,200,1,0,0,0,0,,0,0,0,0,DOWN,1,1,0,,3,3,30
        |""".trimMargin()

    @Test
    fun theProxyCheckNamesWhereEachPrimaryGoesAndCountsLosses() {
        val proxies = ClusterProxy.parse(csv).associateBy { it.name }
        assertEquals(listOf("homelab"), proxies.getValue("postgres-primary").upServers)
        assertEquals(2, proxies.getValue("postgres-primary").outages, "the backend row counts times with no server")
        assertEquals(emptyList(), proxies.getValue("valkey-primary").upServers)

        val history = mutableListOf<Pair<Long, Map<String, Long>>>()
        val first = ClusterProxy.check(csv, history, 1_000_000)
        assertEquals(Health.DOWN, first.health, "Valkey has no primary")
        assertTrue("valkey → none" in first.detail, first.detail)

        val healthy = csv.replace("DOWN,1,1,0,3,2,3,30", "UP,1,1,0,3,2,3,30").replace("DOWN,1,1,0,,3,3,30", "UP,1,1,0,,3,3,30")
        assertEquals(Health.OK, ClusterProxy.check(healthy, mutableListOf(), 1_000_000).health)
        // Later the backend's outage count went up: the check is degraded for the hour.
        val again = ClusterProxy.check(healthy.replace("UP,1,1,0,,3,3,30", "UP,1,1,0,,5,3,30"), history, 1_060_000)
        assertEquals(Health.DEGRADED, again.health)
        assertTrue("lost the primary 2 times" in again.detail, again.detail)
        assertEquals(Health.UNKNOWN, ClusterProxy.check(null, history, 1_060_000).health)
    }

    @Test
    fun theProxyPublishesStatsOnLoopbackOnly() {
        val m = ClusterTemplates.Member("na1", "10.77.0.10")
        assertTrue("stats uri /stats" in ClusterTemplates.haproxy(listOf(m), homegap.provision.ClusterSecrets("pg", "repl", "vk", "rest")))
        assertTrue("\"127.0.0.1:8404:8404\"" in ClusterTemplates.nodeCompose("img", emptyMap()))
    }

    @Test
    fun theProbeScriptParsesAndItsReadingsBecomeLossAndOutages() {
        runBlocking {
            val r = Subprocess.run(listOf("python3", "-c", "import ast,sys; ast.parse(sys.stdin.read())"), stdin = MeshProbe.SCRIPT)
            assertEquals(0, r.exitCode, r.stderr)
        }
        val members = listOf(ClusterTemplates.Member("homelab", "10.77.0.1"), ClusterTemplates.Member("na1", "10.77.0.10"), ClusterTemplates.Member("as1", "10.77.0.11"))
        assertEquals("homelab 10.77.0.1\nas1 10.77.0.11\n", MeshProbe.targets("na1", members))

        val history = MeshProbe.History(windowMs = 3_600_000)
        val t0 = 10_000_000L
        history.record("na1", MeshProbe.parse("S homelab $t0 100 0 236000 0\n"), t0)
        val view = history.record(
            "na1",
            MeshProbe.parse("S homelab ${t0 + 60_000} 160 10 -1 ${t0 + 50_000}\nO homelab ${t0 + 20_000} ${t0 + 35_000}\n"),
            t0 + 60_000,
        ).getValue("homelab")
        assertNull(view.rttMs, "the last probe was lost")
        assertEquals(60, view.sent)
        assertEquals(10 * 100.0 / 60, view.lossPercent)
        assertEquals(listOf(null, t0 + 35_000), view.outages.map { it.endAt }, "the ongoing outage first, then the past one")

        // The probe restarted: its counters start again rather than going negative.
        val restarted = history.record("na1", MeshProbe.parse("S homelab ${t0 + 70_000} 5 0 240000 0\n"), t0 + 70_000).getValue("homelab")
        assertEquals(5, restarted.sent)
        assertEquals(240.0, restarted.rttMs)
    }

    @Test
    fun onlySitesHomegapMadeAreTiedToANode() {
        val d = ClusterReconciler.Departed
        assertEquals("old1", d.siteOwner("homelab-old1", "homelab via old1", "homelab"))
        assertEquals("old1", d.siteOwner("exit-old1", "exit via old1", "homelab"))
        assertNull(d.siteOwner("homelab-old1", "my own site", "homelab"), "a site someone made by hand")
        assertNull(d.siteOwner("office", "office", "homelab"))
    }

    @Test
    fun exitNodesOfKnownNodesAreKept() {
        val sql = ClusterReconciler.Departed.exitNodeSql(setOf("na1", "as1", "bad'id"))
        assertTrue("region NOT IN ('na1','as1')" in sql, sql)
        assertTrue("type = 'gerbil'" in sql && "region IS NOT NULL" in sql)
        assertEquals(listOf(7 to "old1"), ClusterReconciler.Departed.parseDeleted("7 old1\n\nDELETE 1\n"))
    }

    @Test
    fun theContainerProbeChecksEveryOtherMemberFromHaproxy() {
        val members = listOf(ClusterTemplates.Member("homelab", "10.77.0.1"), ClusterTemplates.Member("na1", "10.77.0.10"), ClusterTemplates.Member("as1", "10.77.0.11"))
        val cfg = ClusterTemplates.haproxy(members, homegap.provision.ClusterSecrets("pg", "repl", "vk", "rest"), self = "na1")
        val probe = cfg.substringAfter("backend mesh-probe")
        assertTrue("server homelab 10.77.0.1:2379 check" in probe && "server as1 10.77.0.11:2379 check" in probe, probe)
        assertFalse("server na1" in probe, "not itself")
        assertTrue("inter 1s fall 3" in probe)
    }

    @Test
    fun haproxyStatsAndLogBecomeProbeReadings() {
        val now = 1_794_225_000_000L
        val text = """
            |C # pxname,svname,qcur,status,chkfail,chkdown,lastchg,downtime,check_duration
            |C mesh-probe,homelab,0,UP,4,1,600,15,237
            |C mesh-probe,as1,0,DOWN,0,1,20,20,
            |C mesh-probe,BACKEND,0,UP,,1,600,0,
            |L haproxy-1  | 2026-11-09T11:00:00.500000000Z [WARNING]  (8) : Server mesh-probe/homelab is DOWN, reason: Layer4 timeout
            |L haproxy-1  | 2026-11-09T11:00:15.000000000Z [WARNING]  (8) : Server mesh-probe/homelab is UP, reason: Layer4 check passed
            |""".trimMargin()
        val out = MeshProbe.parseContainer(text, now)
        val homelab = out.readings.getValue("homelab")
        assertEquals(237_000, homelab.rttMicros)
        assertEquals(19, homelab.lost, "failed checks plus seconds spent down")
        assertNull(homelab.ongoingSince)
        val as1 = out.readings.getValue("as1")
        assertNull(as1.rttMicros)
        assertEquals(now - 20_000 - 3_000, as1.ongoingSince, "down since its first missed check")
        val start = kotlin.time.Instant.parse("2026-11-09T11:00:00.500Z").toEpochMilliseconds() - 3_000
        val end = kotlin.time.Instant.parse("2026-11-09T11:00:15Z").toEpochMilliseconds()
        assertEquals(listOf(Triple("homelab", start, end)), out.outages)

        // Loss over the window comes from the counters' growth between passes.
        val history = MeshProbe.History()
        history.record("na1", out, now)
        val later = MeshProbe.parseContainer(text.replace("UP,4,1,600,15,237", "UP,10,1,660,15,240"), now + 60_000)
        assertEquals(10.0, history.record("na1", later, now + 60_000).getValue("homelab").lossPercent)
    }

    @Test
    fun patroniRunsWithoutJit() {
        val yml = ClusterTemplates.patroni(ClusterTemplates.Member("na1", "10.77.0.10"), homegap.config.MeshConfig(), homegap.provision.ClusterSecrets("pg", "repl", "vk", "rest"))
        assertTrue("  parameters:\n    jit: \"off\"" in yml, yml)
        assertFalse("org.homegap.config" in ClusterTemplates.nodeCompose("img", mapOf("valkey" to "x")).substringAfter("  db:").substringBefore("  valkey:"),
            "a settings change reloads Patroni instead of recreating the database")
        assertTrue("kill -HUP 1" in ClusterTemplates.PATRONI_RELOAD)
    }

    @Test
    fun readingTheProbesNeverFailsTheMeshScript() = runBlocking {
        // No HAProxy and no cluster directory here: both reads find nothing, under the node scripts' settings.
        val script = "set -euo pipefail\n${MeshProbe.containerReadCmd("/nonexistent")}\n${MeshProbe.READ_CMD}\necho DONE"
        val r = Subprocess.run(listOf("bash", "-s"), stdin = script)
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue("DONE" in r.stdout)
    }
}
