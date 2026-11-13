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
        var now = 0L
        val live = SpeedTestLive(clock = { now })
        assertNull(live.snapshot(), "nothing runs")
        live.start("tok", "na1")
        // The pings come one after the other, and each gap is a round trip. The first ping only starts the clock.
        for (gap in listOf(0L, 30L, 50L, 40L)) {
            now += gap
            live.ping("tok")
        }
        live.ping("someone-else")
        var snapshot = live.snapshot()!!
        assertEquals("ping", snapshot.phase)
        assertEquals(listOf(50.0, 40.0), snapshot.pingSamples, "the first gap includes the connection")
        assertEquals(45.0, snapshot.pingMs)
        assertEquals(10.0, snapshot.jitterMs)
        assertNull(snapshot.mbps)

        // 125 kB every 100 ms is 10 Mbps.
        repeat(20) {
            now += 100
            live.bytes("tok", upload = false, n = 125_000)
        }
        snapshot = live.snapshot()!!
        assertEquals("download", snapshot.phase)
        assertEquals(10.0, snapshot.mbps!!, 0.01)
        assertTrue(snapshot.mbpsSamples.isNotEmpty())

        repeat(20) {
            now += 100
            live.bytes("tok", upload = true, n = 250_000)
        }
        snapshot = live.snapshot()!!
        assertEquals("upload", snapshot.phase)
        assertEquals(20.0, snapshot.mbps!!, 0.01)
        assertEquals(10.0, snapshot.downMbps!!, 0.6, "the download's average stays")
        assertEquals(45.0, snapshot.pingMs)

        live.stop("tok")
        assertNull(live.snapshot())
    }

    /** A page on plain HTTP is one of the origins that the server refuses. */
    @Suppress("HttpUrlsUsage")
    @Test
    fun onlyTheSpeedTestsOwnPagesMayCallEveryNode() {
        val config = testConfig()
        val server = SpeedTestServer(
            config = { config },
            tokens = SpeedTestTokens(),
            store = SpeedTestStore(tmpPath("st")),
            status = PublicStatus({ config }, { emptyList() }),
        )
        assertTrue(server.speedTestOrigin("https://speedtest.test.example.net"))
        assertTrue(server.speedTestOrigin("https://speedtest-na1.test.example.net"))
        assertFalse(server.speedTestOrigin("http://speedtest-na1.test.example.net"))
        assertFalse(server.speedTestOrigin("https://evil.example"))
        assertFalse(server.speedTestOrigin("https://status.test.example.net"))
    }
}
