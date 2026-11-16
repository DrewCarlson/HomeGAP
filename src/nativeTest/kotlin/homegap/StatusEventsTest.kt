package homegap

import homegap.api.ApiJson
import homegap.api.CheckDto
import homegap.api.Health
import homegap.api.MeshMemberDto
import homegap.api.MeshPeerDto
import homegap.api.MeshStatusDto
import homegap.api.NodeStatusDto
import homegap.api.StatusSnapshot
import homegap.routes.respondRevalidated
import homegap.routes.withoutLiveValues
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatusEventsTest {
    private fun snapshot(rttMs: Double, bytesIn: Long, up: Boolean = true) = StatusSnapshot(
        generatedAt = 1,
        version = "1",
        dryRun = false,
        project = "p",
        domain = "example.net",
        paused = false,
        nodes = listOf(
            NodeStatusDto(
                id = "a1",
                region = "NA",
                provider = "",
                host = "203.0.113.10",
                managementHost = "node-a1.example.net",
                health = if (up) Health.OK else Health.DOWN,
                reachable = up,
                checks = listOf(CheckDto("mesh", if (up) Health.OK else Health.DOWN, value = rttMs, unit = "ms")),
            ),
        ),
        mesh = MeshStatusDto(
            subnet = "10.0.0.0/24",
            members = listOf(
                MeshMemberDto(
                    id = "homelab",
                    address = "10.0.0.1",
                    peers = listOf(
                        MeshPeerDto(
                            id = "a1",
                            lastHandshakeAt = bytesIn,
                            rxBytes = bytesIn,
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun newMeasurementsAreNotAStateChange() {
        assertEquals(
            snapshot(rttMs = 12.0, bytesIn = 100).withoutLiveValues(),
            snapshot(rttMs = 19.5, bytesIn = 900).withoutLiveValues(),
        )
    }

    @Test
    fun aNodeGoingDownIsAStateChange() {
        assertNotEquals(
            snapshot(rttMs = 12.0, bytesIn = 100).withoutLiveValues(),
            snapshot(rttMs = 12.0, bytesIn = 100, up = false).withoutLiveValues(),
        )
    }

    @Test
    fun defaultsAreSent() {
        val json = ApiJson.encodeToString(StatusSnapshot.serializer(), snapshot(rttMs = 12.0, bytesIn = 100))
        assertTrue("\"reconciling\":false" in json)
    }

    @Serializable
    private data class Sample(val value: String)

    @Test
    fun aKnownVersionIsNotSentAgain() = testApplication {
        routing {
            get("/sample") { respondRevalidated(Sample("one")) }
        }
        val first = client.get("/sample")
        assertEquals("{\"value\":\"one\"}", first.bodyAsText())
        val etag = first.headers[HttpHeaders.ETag].orEmpty()
        val second = client.get("/sample") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }
}
