package homegap

import homegap.reconcile.PassTimes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PassTimesTest {
    @Test
    fun thePartsOfAPassAddUpAndStartAgainAfterADrain() {
        val times = PassTimes()
        times.add("mesh", 120)
        times.add("node:na1", 40)
        times.add("mesh", 30)

        assertEquals(mapOf("ms_mesh" to "150", "ms_node:na1" to "40"), times.drain())
        assertTrue(times.drain().isEmpty(), "a drain starts the times again")
    }

    @Test
    fun aPartThatFailsStillHasATime() {
        val times = PassTimes()
        val result = times.measure("dns") { "done" }
        assertFailsWith<IllegalStateException> { times.measure("cluster") { error("no answer") } }

        assertEquals("done", result)
        assertEquals(setOf("ms_dns", "ms_cluster"), times.drain().keys)
    }
}
