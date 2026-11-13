package homegap

import homegap.api.Health
import homegap.config.MeshConfig
import homegap.diagnostics.NodeDiagnostics
import homegap.platform.Subprocess
import homegap.provision.ClusterProxy
import homegap.provision.ClusterSecrets
import homegap.provision.ClusterTemplates
import homegap.provision.MeshProbe
import homegap.reconcile.ClusterReconciler
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Instant

class DiagnosticsTest {
    private val secrets = ClusterSecrets(
        postgres = "pg",
        replication = "repl",
        valkey = "vk",
        rest = "rest",
        pangolin = "pangolin",
        rewind = "rewind",
        etcd = "etcd",
        sentinel = "sentinel",
    )

    private val members = listOf(
        ClusterTemplates.Member("homelab", "10.77.0.1"),
        ClusterTemplates.Member("na1", "10.77.0.10"),
        ClusterTemplates.Member("as1", "10.77.0.11"),
    )

    private fun bashSyntax(script: String) = runBlocking {
        val result = Subprocess.run(listOf("bash", "-n"), stdin = script)
        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun logsComeFromTheRightProjectAndRejectOddInput() {
        val scripts = NodeDiagnostics.Scripts
        assertTrue("cd '/opt/homegap/cluster'" in scripts.logs("/opt/homegap", "haproxy", "1h", 200))
        assertTrue("cd '/opt/homegap/exit'" in scripts.logs("/opt/homegap", "newt", "15m", 200))
        assertTrue("--since 30m --tail 300 pangolin" in scripts.logs("/opt/homegap", "pangolin", "30m", 300))
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "pangolin; reboot", "30m", 10) }
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "pangolin", "1h; reboot", 10) }
        assertFailsWith<IllegalArgumentException> { scripts.logs("/opt/homegap", "pangolin", "1h", 0) }
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

    /** The same stats with a Valkey primary. */
    private val healthyCsv = csv
        .replace("DOWN,1,1,0,3,2,3,30", "UP,1,1,0,3,2,3,30")
        .replace("DOWN,1,1,0,,3,3,30", "UP,1,1,0,,3,3,30")

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

        assertEquals(Health.OK, ClusterProxy.check(healthyCsv, mutableListOf(), 1_000_000).health)
        // The outage count of the backend increases: yellow while the loss is recent, with its time.
        val more = healthyCsv.replace("UP,1,1,0,,3,3,30", "UP,1,1,0,,5,3,30")
        val again = ClusterProxy.check(more, history, 1_060_000)
        assertEquals(Health.DEGRADED, again.health)
        assertTrue("lost the primary 2 times in the last 60 min, last just now" in again.detail, again.detail)
        // Twenty minutes later, nothing is new: green again, and the detail still gives the last loss.
        val later = ClusterProxy.check(more, history, 1_060_000 + 20 * 60_000)
        assertEquals(Health.OK, later.health)
        assertTrue("last 20 min ago (recovered)" in later.detail, later.detail)
        // HAProxy restarted (its counters went back to 1): not a loss.
        val countersReset = more.replace("UP,1,1,0,,5,3,30", "UP,1,1,0,,1,3,30")
        val restarted = ClusterProxy.check(countersReset, history, 1_060_000 + 21 * 60_000)
        assertEquals(Health.OK, restarted.health)
        assertEquals(Health.UNKNOWN, ClusterProxy.check(null, history, 1_060_000).health)
    }

    @Test
    fun aPlannedSwitchDoesNotMakeTheProxyCheckYellow() {
        val lost = healthyCsv.replace("UP,1,1,0,,3,3,30", "UP,1,1,0,,4,3,30")
        val history = mutableListOf<Pair<Long, Map<String, Long>>>()
        ClusterProxy.check(healthyCsv, history, 1_000_000)
        // HomeGAP switched the primary back between the two readings.
        val planned = ClusterProxy.check(lost, history, 1_060_000, planned = listOf(1_020_000))
        assertEquals(Health.OK, planned.health, planned.detail)
        assertTrue("planned switch just now" in planned.detail, planned.detail)
        assertFalse("lost the primary" in planned.detail, planned.detail)
        // An unplanned loss still shows, then clears after five minutes.
        val lostAgain = lost.replace("UP,1,1,0,,4,3,30", "UP,1,1,0,,5,3,30")
        val failover = ClusterProxy.check(lostAgain, history, 1_120_000, planned = listOf(1_020_000))
        assertEquals(Health.DEGRADED, failover.health, failover.detail)
        val later = ClusterProxy.check(lostAgain, history, 1_120_000 + 6 * 60_000, planned = listOf(1_020_000))
        assertEquals(Health.OK, later.health, later.detail)
    }

    @Test
    fun theProxyPublishesStatsOnLoopbackOnly() {
        val member = ClusterTemplates.Member("na1", "10.77.0.10")
        assertTrue("stats uri /stats" in ClusterTemplates.haproxy(listOf(member), secrets))
        assertTrue("\"127.0.0.1:8404:8404\"" in ClusterTemplates.nodeCompose("img", emptyMap()))
    }

    @Test
    fun theProbeScriptParsesAndItsReadingsBecomeLossAndOutages() {
        runBlocking {
            val parseAsPython = listOf("python3", "-c", "import ast,sys; ast.parse(sys.stdin.read())")
            val result = Subprocess.run(parseAsPython, stdin = MeshProbe.SCRIPT)
            assertEquals(0, result.exitCode, result.stderr)
        }
        assertEquals("homelab 10.77.0.1\nas1 10.77.0.11\n", MeshProbe.targets("na1", members))

        val history = MeshProbe.History(windowMs = 3_600_000)
        val start = 10_000_000L
        history.record("na1", MeshProbe.parse("S homelab $start 100 0 236000 0\n"), start)
        val counters = "S homelab ${start + 60_000} 160 10 -1 ${start + 50_000}\n"
        val outage = "O homelab ${start + 20_000} ${start + 35_000}\n"
        val view = history.record("na1", MeshProbe.parse(counters + outage), start + 60_000).getValue("homelab")
        assertNull(view.rttMs, "the last probe was lost")
        assertEquals(60, view.sent)
        assertEquals(10 * 100.0 / 60, view.lossPercent)
        assertEquals(
            listOf(null, start + 35_000),
            view.outages.map { it.endAt },
            "the ongoing outage first, then the past one",
        )

        // The probe restarted: its counters start again and do not go negative.
        val afterRestart = MeshProbe.parse("S homelab ${start + 70_000} 5 0 240000 0\n")
        val restarted = history.record("na1", afterRestart, start + 70_000).getValue("homelab")
        assertEquals(5, restarted.sent)
        assertEquals(240.0, restarted.rttMs)
    }

    @Test
    fun onlySitesHomegapMadeAreTiedToANode() {
        val departed = ClusterReconciler.Departed
        assertEquals("old1", departed.siteOwner("homelab-old1", "homelab via old1", "homelab"))
        assertEquals("old1", departed.siteOwner("exit-old1", "exit via old1", "homelab"))
        assertNull(departed.siteOwner("homelab-old1", "my own site", "homelab"), "a site someone made by hand")
        assertNull(departed.siteOwner("office", "office", "homelab"))
    }

    @Test
    fun exitNodesOfKnownNodesAreKept() {
        val sql = ClusterReconciler.Departed.exitNodeSql(setOf("na1", "as1", "bad'id"))
        assertTrue("region NOT IN ('na1','as1')" in sql, sql)
        assertTrue("type = 'gerbil'" in sql && "region IS NOT NULL" in sql)
        val expected = listOf(7 to "old1")
        assertEquals(expected, ClusterReconciler.Departed.parseDeleted("7 old1\n\nDELETE 1\n"))
    }

    @Test
    fun theContainerProbeChecksEveryOtherMemberFromHaproxy() {
        val haproxyConfig = ClusterTemplates.haproxy(members, secrets, self = "na1")
        val probe = haproxyConfig.substringAfter("backend mesh-probe")
        assertTrue(
            "server homelab 10.77.0.1:2379 check" in probe && "server as1 10.77.0.11:2379 check" in probe,
            probe,
        )
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
        val output = MeshProbe.parseContainer(text, now)
        val homelab = output.readings.getValue("homelab")
        assertEquals(237_000, homelab.rttMicros)
        assertEquals(19, homelab.lost, "failed checks plus seconds spent down")
        assertNull(homelab.ongoingSince)
        val as1 = output.readings.getValue("as1")
        assertNull(as1.rttMicros)
        assertEquals(now - 20_000 - 3_000, as1.ongoingSince, "down since its first missed check")
        val start = Instant.parse("2026-11-09T11:00:00.500Z").toEpochMilliseconds() - 3_000
        val end = Instant.parse("2026-11-09T11:00:15Z").toEpochMilliseconds()
        assertEquals(listOf(Triple("homelab", start, end)), output.outages)

        // Loss over the window comes from the counters' growth between passes.
        val history = MeshProbe.History()
        history.record("na1", output, now)
        val laterText = text.replace("UP,4,1,600,15,237", "UP,10,1,660,15,240")
        val later = MeshProbe.parseContainer(laterText, now + 60_000)
        assertEquals(10.0, history.record("na1", later, now + 60_000).getValue("homelab").lossPercent)
    }

    @Test
    fun patroniRunsWithoutJit() {
        val patroniYaml = ClusterTemplates.patroni(ClusterTemplates.Member("na1", "10.77.0.10"), MeshConfig(), secrets)
        assertTrue("  parameters:\n    jit: \"off\"" in patroniYaml, patroniYaml)
        val databaseService = ClusterTemplates.nodeCompose("img", mapOf("valkey" to "x"))
            .substringAfter("  db:")
            .substringBefore("  valkey:")
        assertFalse(
            "org.homegap.config" in databaseService,
            "a settings change reloads Patroni instead of recreating the database",
        )
        assertTrue("kill -HUP 1" in ClusterTemplates.PATRONI_RELOAD)
    }

    @Test
    fun readingTheProbesNeverFailsTheMeshScript() = runBlocking {
        // There is no HAProxy and no cluster directory here. Both reads find nothing, with the settings
        // of the node scripts.
        val script = listOf(
            "set -euo pipefail",
            MeshProbe.containerReadCmd("/nonexistent"),
            MeshProbe.READ_CMD,
            "echo DONE",
        ).joinToString("\n")
        val result = Subprocess.run(listOf("bash", "-s"), stdin = script)
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue("DONE" in result.stdout)
    }
}
