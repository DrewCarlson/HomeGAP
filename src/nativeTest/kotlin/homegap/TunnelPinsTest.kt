package homegap

import homegap.model.Planner
import homegap.tunnels.HostsPins
import homegap.tunnels.TunnelFiles
import homegap.tunnels.TunnelSpec
import homegap.tunnels.TunnelSupervisor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelPinsTest {
    private fun spec(node: String, vararg addresses: String) = TunnelSpec(
        nodeId = node,
        endpoint = "https://pangolin-$node.test.example.net",
        newtId = "id",
        newtSecret = "secret",
        adminPort = 47900,
        addresses = addresses.toList(),
    )

    @Test
    fun pinsAreTheAddressesPublishedInDns() {
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK))
        for (node in listOf("na1", "as1")) {
            val records = plan.dns.filter { it.fqdn == "pangolin-$node.test.example.net" }
            assertEquals(records.map { it.value }.toSet(), plan.managementAddresses(node).toSet())
        }
        assertEquals(listOf("203.0.113.10"), plan.managementAddresses("na1"))
        assertEquals(setOf("198.51.100.20", "2001:db8::20"), plan.managementAddresses("as1").toSet())
    }

    @Test
    fun blockIsAddedReplacedAndRemovedWithoutTouchingOtherLines() {
        val docker = "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost\n172.30.0.5\tabc123\n"
        val first = HostsPins.render(docker, HostsPins.entries(listOf(spec("na1", "203.0.113.10"))))
        val versionCheck = "127.0.0.1\tapi.fossorial.io\n${HostsPins.END}\n"
        assertEquals(
            docker + "${HostsPins.BEGIN}\n203.0.113.10\tpangolin-na1.test.example.net\n" + versionCheck,
            first,
        )
        // The address changed and a node was added: the block is rewritten in place, once.
        val second = HostsPins.render(
            first,
            HostsPins.entries(listOf(spec("as1", "198.51.100.20", "2001:db8::20"), spec("na1", "203.0.113.99"))),
        )
        assertEquals(
            docker + "${HostsPins.BEGIN}\n" +
                "198.51.100.20\tpangolin-as1.test.example.net\n2001:db8::20\tpangolin-as1.test.example.net\n" +
                "203.0.113.99\tpangolin-na1.test.example.net\n" + versionCheck,
            second,
        )
        assertEquals(1, second.lines().count { it == HostsPins.BEGIN })
        // Unchanged pins render the same text, so the file is not rewritten.
        val sameSpecs = listOf(spec("na1", "203.0.113.99"), spec("as1", "198.51.100.20", "2001:db8::20"))
        assertEquals(second, HostsPins.render(second, HostsPins.entries(sameSpecs)))
        // The version check of Newt goes to the loopback address, also before a node has a tunnel.
        assertEquals(listOf("127.0.0.1" to "api.fossorial.io"), HostsPins.entries(emptyList()))
        // Without entries, the result is the file as Docker wrote it.
        assertEquals(docker, HostsPins.render(second, emptyList()))
    }

    @Test
    fun specsWithoutPinsStayCompatible() {
        val old = listOf(
            """{"nodeId":"na1","endpoint":"https://pangolin-na1.test.example.net:443/",""",
            """"newtId":"i","newtSecret":"s","adminPort":47900}""",
        ).joinToString("")
        val spec = TunnelFiles.json.decodeFromString(TunnelSpec.serializer(), old)
        assertEquals(emptyList(), spec.addresses)
        assertEquals("pangolin-na1.test.example.net", spec.host)
        assertTrue(HostsPins.entries(listOf(spec)).none { it.second == spec.host })
        // New pins in the spec of a connector that runs are not a reason to restart it.
        assertEquals(spec.withoutPins(), spec.copy(addresses = listOf("203.0.113.10")).withoutPins())
    }

    @Test
    fun loginRejectionsCountUntilAConnection() {
        val rejected = "ERROR: 2026/11/10 17:37:53 Failed to connect: failed to get token: " +
            "failed to get token with status code: 404, body: 404 page not found\n. Retrying in 3s...\n"
        assertEquals(3, TunnelSupervisor.loginRejections(rejected.repeat(3), 0))
        assertEquals(5, TunnelSupervisor.loginRejections(rejected.repeat(2), 3))
        // A node that starts refuses a few logins and then connects. That is not a reason to restart.
        val recovered = rejected.repeat(2) + "INFO: 2026/11/10 17:42:47 Websocket connected\n"
        assertEquals(0, TunnelSupervisor.loginRejections(recovered, 2))
        // An answer from a different service, which is not the JSON of Pangolin, counts the same.
        // The count does not include the separate "Failed to decode" line of Newt.
        val notPangolin = listOf(
            "ERROR: 2026/11/10 18:16:18 Failed to decode token response.\n",
            "ERROR: 2026/11/10 18:16:18 Failed to connect: failed to get token: ",
            "failed to decode token response: invalid character 'H' looking for beginning of value. ",
            "Retrying in 3s...\n",
        ).joinToString("")
        assertEquals(2, TunnelSupervisor.loginRejections(notPangolin.repeat(2), 0))
        // Other errors (a wrong secret is a 401) are not this failure.
        assertEquals(0, TunnelSupervisor.loginRejections("failed to get token with status code: 401\n", 0))
    }
}
