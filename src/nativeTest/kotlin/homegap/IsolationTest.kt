package homegap

import homegap.reconcile.isolationAfter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsolationTest {
    @Test
    fun startsWhenNoNodeAnswersAndEndsWhenOneDoes() {
        assertNull(isolationAfter(null, listOf(true, false), 1), "one node down is that node's problem")
        val started = isolationAfter(null, listOf(false, false), 100)
        assertEquals(100L to null, started)
        assertEquals(started, isolationAfter(started, listOf(false, false), 200), "still the same episode")
        val ended = isolationAfter(started, listOf(false, true), 700)
        assertEquals(100L to 700L, ended)
        assertEquals(ended, isolationAfter(ended, listOf(true, true), 800), "kept for the dashboard")
        assertEquals(900L to null, isolationAfter(ended, listOf(false, false), 900), "a new episode")
        assertNull(isolationAfter(null, listOf(false), 1), "a single node can't tell the homelab apart from itself")
    }
}
