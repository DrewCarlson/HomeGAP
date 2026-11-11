package homegap

import homegap.speedtest.SpeedTestLive
import homegap.speedtest.SpeedTestServer
import homegap.speedtest.SpeedTestStore
import homegap.speedtest.SpeedTestTokens
import homegap.status.PublicStatus
import kotlin.test.*

class SpeedTestLiveTest {
    @Test
    fun followsTheRunningTestThroughItsPhases() {
        var t = 0L
        val live = SpeedTestLive { t }
        assertNull(live.snapshot(), "nothing runs")
        live.start("tok", "na1")
        // Pings one after another: each gap is a round trip; the first only starts the clock.
        for (gap in listOf(0L, 30L, 50L, 40L)) { t += gap; live.ping("tok") }
        live.ping("someone-else")
        var s = live.snapshot()!!
        assertEquals("ping", s.phase)
        assertEquals(listOf(50.0, 40.0), s.pingSamples, "the first gap includes the connection")
        assertEquals(45.0, s.pingMs)
        assertEquals(10.0, s.jitterMs)
        assertNull(s.mbps)

        // 125 kB every 100 ms is 10 Mbps.
        repeat(20) { t += 100; live.bytes("tok", upload = false, n = 125_000) }
        s = live.snapshot()!!
        assertEquals("download", s.phase)
        assertEquals(10.0, s.mbps!!, 0.01)
        assertTrue(s.mbpsSamples.isNotEmpty())

        repeat(20) { t += 100; live.bytes("tok", upload = true, n = 250_000) }
        s = live.snapshot()!!
        assertEquals("upload", s.phase)
        assertEquals(20.0, s.mbps!!, 0.01)
        assertEquals(10.0, s.downMbps!!, 0.6, "the download's average stays")
        assertEquals(45.0, s.pingMs)

        live.stop("tok")
        assertNull(live.snapshot())
    }

    @Test
    fun onlyTheSpeedTestsOwnPagesMayCallEveryNode() {
        val cfg = testConfig()
        val server = SpeedTestServer({ cfg }, SpeedTestTokens(), SpeedTestStore(tmpPath("st")), PublicStatus({ cfg }, { emptyList() }))
        assertTrue(server.speedTestOrigin("https://speedtest.test.example.net"))
        assertTrue(server.speedTestOrigin("https://speedtest-na1.test.example.net"))
        assertFalse(server.speedTestOrigin("http://speedtest-na1.test.example.net"))
        assertFalse(server.speedTestOrigin("https://evil.example"))
        assertFalse(server.speedTestOrigin("https://status.test.example.net"))
    }
}
