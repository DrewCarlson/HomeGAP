package homegap

import homegap.identity.NodeNames
import homegap.signin.Alias
import homegap.signin.SiteData
import homegap.signin.publicAliases
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicAliasesTest {
    private val names = mapOf(
        "na1" to NodeNames("203.0.113.10", listOf("*.example.net", "example.net", "status-na1.example.net")),
        "as1" to NodeNames("203.0.113.20", listOf("*.example.net", "example.net", "status-as1.example.net")),
    )

    @Test
    fun theNamesOfTheNodeOfTheDeviceAnswerWithThatNode() {
        val aliases = publicAliases(names, "as1")
        assertEquals("203.0.113.20", aliases.single { it.alias == "*.example.net" }.aliasAddress)
        assertEquals("203.0.113.20", aliases.single { it.alias == "example.net" }.aliasAddress)
    }

    @Test
    fun aNameOfAnotherNodeOnlyKeepsThatNode() {
        val aliases = publicAliases(names, "as1")
        val other = aliases.single { it.alias.startsWith("status-na1") }
        assertEquals(Alias("status-na1.example.net", "203.0.113.10"), other)
    }

    @Test
    fun aDeviceWithNoNodeGetsNoPublicNames() {
        assertEquals(emptyList(), publicAliases(names, null))
        assertEquals(emptyList(), publicAliases(names, "gone"))
    }

    @Test
    fun aChangeRemovesTheOldAddressOfANameBeforeItAddsTheNewOne() {
        val old = listOf(Alias("a.example.net", "10.0.0.1"), Alias("b.example.net", "10.0.0.2"))
        val new = listOf(Alias("a.example.net", "10.0.0.9"), Alias("c.example.net", "10.0.0.3"))
        val before = SiteData(1, listOf("8.8.8.8/32"), old)
        val now = SiteData(1, listOf("9.9.9.9/32"), new)
        val (removed, added) = now.changeFrom(before)
        assertEquals(old, removed.aliases)
        assertEquals(new, added.aliases)
        assertEquals(listOf("8.8.8.8/32"), removed.remoteSubnets)
        assertEquals(listOf("9.9.9.9/32"), added.remoteSubnets)
        assertEquals(true, now.changeFrom(now).first.isEmpty)
    }
}
