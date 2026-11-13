package homegap

import homegap.reconcile.isolationAfter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsolationTest {
    @Test
    fun startsWhenNoNodeAnswersAndEndsWhenOneDoes() {
        val oneDown = isolationAfter(current = null, reachable = listOf(true, false), now = 1)
        assertNull(oneDown, "one node down is that node's problem")
        val started = isolationAfter(current = null, reachable = listOf(false, false), now = 100)
        assertEquals(100L to null, started)
        assertEquals(started, isolationAfter(started, listOf(false, false), 200), "still the same episode")
        val ended = isolationAfter(started, listOf(false, true), 700)
        assertEquals(100L to 700L, ended)
        assertEquals(ended, isolationAfter(ended, listOf(true, true), 800), "kept for the dashboard")
        assertEquals(900L to null, isolationAfter(ended, listOf(false, false), 900), "a new episode")
        val singleNode = isolationAfter(current = null, reachable = listOf(false), now = 1)
        assertNull(singleNode, "a single node can't tell the homelab apart from itself")
    }
}
