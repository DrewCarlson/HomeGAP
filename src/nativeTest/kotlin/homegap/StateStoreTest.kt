package homegap

import homegap.platform.Files
import homegap.state.Pauses
import homegap.state.StateStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StateStoreTest {
    @Test
    fun aMissingFileIsAnEmptyState() {
        val store = StateStore(tmpPath("state"))
        assertTrue(store.snapshot().nodes.isEmpty())
    }

    @Test
    fun aCorruptFileFallsBackToTheLastGoodCopy() = runBlocking {
        val path = tmpPath("state")
        StateStore(path).update { it.copy(pauses = Pauses(global = true)) }
        // The next start keeps a copy of the good file.
        assertTrue(StateStore(path).snapshot().pauses.global)
        Files.writeAtomic(path, "{ not json", "600".toInt(8))
        assertTrue(StateStore(path).snapshot().pauses.global, "the global pause survives a corrupt file")
        listOf(path, "$path.bak", "$path.corrupt").forEach { Files.delete(it) }
    }

    @Test
    fun aCorruptFileWithoutACopyStopsTheStart() {
        val path = tmpPath("state")
        Files.writeAtomic(path, "{ not json", "600".toInt(8))
        val error = assertFailsWith<IllegalStateException> { StateStore(path) }
        assertTrue("cannot be read" in error.message.orEmpty())
        listOf(path, "$path.corrupt").forEach { Files.delete(it) }
    }

    @Test
    fun writesKeepTheirMode() {
        val path = tmpPath("mode")
        Files.writeAtomic(path, "one", "600".toInt(8))
        Files.writeAtomic(path, "two", "600".toInt(8))
        assertEquals("two", Files.readText(path))
        Files.delete(path)
    }
}
