package homegap.presentation

import homegap.presentation.search.SearchKind
import homegap.presentation.search.fuzzyScore
import homegap.presentation.search.search
import homegap.presentation.search.searchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    @Test
    fun prefixBeatsWordStartBeatsScattered() {
        val prefix = fuzzyScore("sg", "sg1")!!
        val word = fuzzyScore("sg", "speed test sg1")!!
        val scattered = fuzzyScore("sg", "settings page")!!
        assertTrue(prefix > word && word > scattered, "$prefix $word $scattered")
        assertNull(fuzzyScore("xyz", "sg1"))
    }

    @Test
    fun findsNodesAndPagesGroupedByKind() {
        val index = searchIndex(
            snapshot = snapshot(nodes = listOf(node("sg1"), node("fra1"))),
            log = emptyList(),
            actions = emptyList(),
        )
        val hits = search(index, "sg")
        assertEquals("sg1", hits.first().title)
        assertEquals(SearchKind.Node, hits.first().kind)
        assertTrue(search(index, "diag").any { it.kind == SearchKind.Page && it.title == "Diagnostics" })
        assertTrue(search(index, "").isEmpty())
    }

    @Test
    fun aLiteralMatchHidesTheLooseOnes() {
        val index = searchIndex(
            snapshot = snapshot(nodes = listOf(node("git1"), node("pangolin-test"))),
            log = emptyList(),
            actions = emptyList(),
        )
        assertEquals(listOf("git1"), search(index, "git").filter { it.kind == SearchKind.Node && it.location?.selection != null }.map { it.title })
        assertTrue(search(index, "homelab").any { it.title == "Homelab" })
        assertTrue(search(index, "mesh").any { it.title == "Nodes" }, "a page answers for what it shows")
    }
}
