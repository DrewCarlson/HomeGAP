package homegap.presentation

import homegap.api.IsolationDto
import homegap.presentation.app.BannerKind
import homegap.presentation.app.banners
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsolationBannerTest {
    @Test
    fun theHomelabLosingEveryNodeIsCalledOut() {
        val ongoing = snapshot().copy(homelabIsolation = IsolationDto(NOW - 5 * 60_000)).banners().single()
        assertEquals(BannerKind.Error, ongoing.kind)
        assertTrue("can't reach any node (for 5 min)" in ongoing.text, ongoing.text)

        val past = snapshot().copy(homelabIsolation = IsolationDto(NOW - 30 * 60_000, NOW - 21 * 60_000)).banners().single()
        assertEquals(BannerKind.Info, past.kind)
        assertTrue("for 9 min, until 21 min ago" in past.text, past.text)

        assertTrue(snapshot().copy(homelabIsolation = IsolationDto(NOW - 3 * 3_600_000, NOW - 2 * 3_600_000)).banners().isEmpty(), "gone after an hour")
    }
}
