package homegap

import homegap.identity.NodeNames
import homegap.signin.Alias
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
        assertEquals(Alias("status-na1.example.net", "203.0.113.10"), aliases.single { it.alias.startsWith("status-na1") })
    }

    @Test
    fun aDeviceWithNoNodeGetsNoPublicNames() {
        assertEquals(emptyList(), publicAliases(names, null))
        assertEquals(emptyList(), publicAliases(names, "gone"))
    }
}
