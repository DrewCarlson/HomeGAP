package homegap

import homegap.config.ConfigLoader
import homegap.discovery.DiscoveryMemory
import homegap.model.Planner
import homegap.platform.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A container that is gone keeps its services for a grace period. */
class DiscoveryMemoryTest {
    private val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80")
    private val grace = 180_000L

    @Test
    fun aContainerThatIsGoneStaysForTheGracePeriod() {
        val memory = DiscoveryMemory(tmpPath("discovery"))
        assertEquals(listOf("traefik", "wiki"), memory.merge(listOf(TRAEFIK, wiki), 1000, grace)!!.containers.map { it.name })

        // The homelab starts again, and the orchestrator sees no container yet.
        val empty = memory.merge(emptyList(), 2000, grace)!!
        assertEquals(setOf("traefik", "wiki"), empty.containers.map { it.name }.toSet())
        assertEquals(setOf("traefik", "wiki"), empty.held.keys)
        assertEquals(grace, empty.held.getValue("wiki"))
        val before = Planner.plan(testConfig(), listOf(TRAEFIK, wiki))
        assertEquals(before.dns, Planner.plan(testConfig(), empty.containers).dns, "no record goes away")

        // One container comes back. The other stays until the grace period ends.
        val partly = memory.merge(listOf(TRAEFIK), 2000 + grace - 1, grace)!!
        assertEquals(setOf("wiki"), partly.held.keys)
        val after = memory.merge(listOf(TRAEFIK), 2000 + grace, grace)!!
        assertEquals(listOf("traefik"), after.containers.map { it.name })
        assertEquals(listOf("wiki"), after.dropped)
    }

    @Test
    fun aFailedDiscoveryPlansFromTheLastOne() {
        val path = tmpPath("discovery")
        assertNull(DiscoveryMemory(path).merge(null, 1000, grace), "nothing is known yet")
        DiscoveryMemory(path).merge(listOf(TRAEFIK, wiki), 1000, grace)
        // A new process reads the file.
        val restarted = DiscoveryMemory(path)
        assertEquals(setOf("traefik", "wiki"), restarted.merge(null, 999_999_000, grace)!!.containers.map { it.name }.toSet())
        Files.delete(path)
    }

    @Test
    fun aNewContainerForTheSameHostnameReplacesTheOldOneAtOnce() {
        val memory = DiscoveryMemory(tmpPath("discovery"))
        memory.merge(listOf(wiki), 1000, grace)
        val renamed = container("wiki-2", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80")
        val result = memory.merge(listOf(renamed), 2000, grace)!!
        assertEquals(listOf("wiki-2"), result.containers.map { it.name })
        assertTrue(result.held.isEmpty())
    }

    @Test
    fun aDryRunDoesNotWriteTheFile() {
        val path = tmpPath("discovery")
        DiscoveryMemory(path).merge(listOf(wiki), 1000, grace, save = false)
        assertTrue(!Files.exists(path))
    }

    @Test
    fun theGracePeriodIsADuration() {
        assertEquals(180, testConfig().reconcile.removalGraceDuration.inWholeSeconds)
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse("$TEST_CONFIG\nreconcile:\n  removal_grace: soon\n") }
    }
}
