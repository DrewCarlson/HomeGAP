package homegap

import homegap.signin.Device
import homegap.signin.Presence
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** A device with no control connection keeps the key of its tunnel for a time. */
class PresenceTest {
    private val phone = Device(id = "phone", address = "100.90.0.2")
    private val keep = 7.days

    @Test
    fun aDeviceThatSleepsKeepsItsKeyUntilTheTimeEnds() = runTest {
        val removed = mutableListOf<String>()
        val presence = Presence(backgroundScope, keep) { _, key -> removed += key }
        presence.arrive(phone.id)
        assertTrue(presence.leave(phone.id))
        presence.depart(phone, "key")

        advanceTimeBy(keep - 1.hours)
        assertEquals(emptyList(), removed)
        advanceTimeBy(2.hours)
        assertEquals(listOf("key"), removed)
    }

    @Test
    fun aDeviceThatComesBackKeepsItsKey() = runTest {
        val removed = mutableListOf<String>()
        val presence = Presence(backgroundScope, keep) { _, key -> removed += key }
        presence.depart(phone, "key")
        advanceTimeBy(1.days)
        presence.arrive(phone.id)

        advanceTimeBy(keep)
        assertEquals(emptyList(), removed)
    }

    @Test
    fun anOldConnectionThatEndsAfterANewOneRemovesNothing() = runTest {
        val removed = mutableListOf<String>()
        val presence = Presence(backgroundScope, keep) { _, key -> removed += key }
        presence.arrive(phone.id)
        presence.arrive(phone.id)
        assertFalse(presence.leave(phone.id))
        // A caller that asks all the same gets no removal while a connection is there.
        presence.depart(phone, "key")

        advanceTimeBy(keep + 1.hours)
        assertEquals(emptyList(), removed)
    }

    @Test
    fun aStartOfTheServerCountsTheTimeThatTheDeviceWasSilentBefore() = runTest {
        val removed = mutableListOf<String>()
        val presence = Presence(backgroundScope, keep) { _, key -> removed += key }
        presence.depart(phone, "key", silent = keep - 1.hours)
        presence.depart(Device(id = "old", address = "100.90.0.3"), "old-key", silent = keep + 1.days)
        runCurrent()
        assertEquals(listOf("old-key"), removed)

        advanceTimeBy(2.hours)
        assertEquals(listOf("old-key", "key"), removed)
    }
}
