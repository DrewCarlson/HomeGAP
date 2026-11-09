package homegap.presentation

import homegap.api.Health
import homegap.api.MeshMemberDto
import homegap.api.MeshOutageDto
import homegap.api.MeshProbeDto
import homegap.api.MeshPeerDto
import homegap.api.MeshStatusDto
import homegap.presentation.nodes.toPanelModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshPanelModelTest {
    private val now = 10_000_000L

    @Test
    fun rendersEachMembersViewOfTheOthers() {
        val status = MeshStatusDto(
            subnet = "10.77.0.0/24",
            members = listOf(
                MeshMemberDto("homelab", "10.77.0.1", peers = listOf(
                    MeshPeerDto("na1", lastHandshakeAt = now - 30_000),
                    MeshPeerDto("eu1", lastHandshakeAt = now - 5 * 60_000),
                )),
                MeshMemberDto("na1", "10.77.0.10", peers = listOf(MeshPeerDto("homelab", lastHandshakeAt = now - 30_000))),
                MeshMemberDto("eu1", "10.77.0.11", error = "ssh: timed out"),
            ),
        )
        val rows = status.toPanelModel(now).members.associateBy { it.id }

        val homelab = rows.getValue("homelab")
        assertEquals(listOf(Health.OK, Health.DEGRADED), homelab.peers.map { it.health })
        assertEquals(Health.DEGRADED, homelab.status.health, "one fresh and one stale link")

        val na1 = rows.getValue("na1")
        assertEquals("eu1 · not configured", na1.peers.single { it.text.startsWith("eu1") }.text)
        assertEquals(Health.DEGRADED, na1.status.health)

        val eu1 = rows.getValue("eu1")
        assertEquals(Health.DOWN, eu1.status.health)
        assertEquals("ssh: timed out", eu1.error)
        assertEquals(setOf(Health.UNKNOWN), eu1.peers.map { it.health }.toSet(), "an unreadable member shows unknown links")
    }

    @Test
    fun probesShowRoundTripLossAndOutages() {
        val status = MeshStatusDto(
            subnet = "10.77.0.0/24",
            members = listOf(
                MeshMemberDto("homelab", "10.77.0.1"),
                MeshMemberDto("na1", "10.77.0.10", peers = listOf(
                    MeshPeerDto("homelab", lastHandshakeAt = now, probe = MeshProbeDto(rttMs = 236.4, lossPercent = 0.0, sent = 3600, windowMs = 3_600_000)),
                    MeshPeerDto("eu1", lastHandshakeAt = now, probe = MeshProbeDto(
                        rttMs = 12.34, lossPercent = 0.4, sent = 3600, windowMs = 3_600_000,
                        outages = listOf(MeshOutageDto(now - 120_000, now - 105_000)),
                    )),
                )),
                MeshMemberDto("eu1", "10.77.0.11", peers = listOf(
                    MeshPeerDto("na1", lastHandshakeAt = now, probe = MeshProbeDto(rttMs = null, sent = 10, outages = listOf(MeshOutageDto(now - 5_000)))),
                )),
            ),
        )
        val rows = status.toPanelModel(now).members.associateBy { it.id }
        assertEquals(emptyList(), rows.getValue("homelab").probes, "the homelab runs no probe")
        val na1 = rows.getValue("na1").probes.associateBy { it.text.substringBefore(" ") }
        assertEquals("homelab · 236 ms", na1.getValue("homelab").text)
        assertEquals(Health.OK, na1.getValue("homelab").health)
        assertEquals("eu1 · 12.3 ms · 0.4% lost · 1 outage", na1.getValue("eu1").text)
        assertEquals(Health.DEGRADED, na1.getValue("eu1").health)
        assertTrue("lasted 15s" in na1.getValue("eu1").tooltip.orEmpty())
        assertEquals(Health.DOWN, rows.getValue("eu1").probes.single().health, "out right now")
    }
}
