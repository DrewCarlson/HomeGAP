package homegap

import homegap.config.ConfigLoader
import homegap.provision.TunnelDns
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelDnsTest {
    private fun cfg(cluster: String = "") = ConfigLoader.parse(TEST_CONFIG + "\ncluster:\n  image: example/cluster:7\n$cluster")
    private val exits = mapOf("na1" to "exit-na1", "as1" to "exit-as1")
    private val homelab = mapOf("na1" to "homelab-na1", "as1" to "homelab-as1")

    @Test
    fun theAppsDefaultResolverIsReachableFromTheNearestNode() {
        // With node exits, lookups leave from the node the device relays through.
        val c = cfg("  exit_nodes:\n    nodes: true\n")
        val (dns, sites) = TunnelDns.resources(c, exits, homelab).entries.single().toPair()
        assertEquals("hg-dns-8-8-8-8", dns.niceId)
        assertEquals("cidr", dns.mode)
        assertEquals("8.8.8.8/32", dns.destination)
        assertEquals("53", dns.tcpPorts)
        assertEquals("53", dns.udpPorts)
        assertEquals(listOf("Member"), dns.roles)
        assertEquals(listOf("exit-na1", "exit-as1"), sites, "every node's exit site, in config order")
    }

    @Test
    fun withoutNodeExitsTheyLeaveFromHome() {
        val res = TunnelDns.resources(cfg(), exits, homelab)
        assertEquals(listOf("homelab-na1", "homelab-as1"), res.values.single())
        // Node exits on but no exit site yet: the homelab sites, rather than nothing.
        assertEquals(listOf("homelab-na1", "homelab-as1"), TunnelDns.resources(cfg("  exit_nodes:\n    nodes: true\n"), emptyMap(), homelab).values.single())
    }

    @Test
    fun resolversAreConfigurableAndCanBeTurnedOff() {
        val c = cfg("  tunnel_dns:\n    resolvers: [\"1.1.1.1\", \"2606:4700:4700::1111\"]\n    roles: [Member, Family]\n")
        val res = TunnelDns.resources(c, exits, homelab).keys.associateBy { it.niceId }
        assertEquals(setOf("hg-dns-1-1-1-1", "hg-dns-2606-4700-4700-1111"), res.keys)
        assertEquals("2606:4700:4700::1111/128", res.getValue("hg-dns-2606-4700-4700-1111").destination)
        assertEquals(listOf("Member", "Family"), res.getValue("hg-dns-1-1-1-1").roles)
        assertEquals(res.keys, TunnelDns.wanted(c))

        assertTrue(TunnelDns.resources(cfg("  tunnel_dns:\n    resolvers: []\n"), exits, homelab).isEmpty())
        assertTrue(TunnelDns.resources(ConfigLoader.parse(TEST_CONFIG), exits, homelab).isEmpty(), "cluster only")
        assertFailsWith<IllegalArgumentException> { cfg("  tunnel_dns:\n    resolvers: [dns.google]\n") }
    }

    @Test
    fun theBlueprintHasNoAlias() {
        // Pangolin rejects an empty alias; a resolver is reached by its address.
        val (dns, sites) = TunnelDns.resources(cfg(), exits, homelab).entries.single().toPair()
        val rr = homegap.reconcile.ResourceReconciler(
            homegap.reconcile.ReconcileContext(
                cfg(), homegap.state.StateStore(tmpPath("state")), homegap.reconcile.ReconcileLog(),
                homegap.clients.SshPool(tmpPath("ssh"), tmpPath("kh")),
                io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no calls") }),
                io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("no calls") }),
                false, homegap.reconcile.DiffCollector(),
            ),
        )
        val bp = rr.buildBlueprint(emptyMap(), mapOf(dns to sites))["private-resources"]!!.jsonObject["hg-dns-8-8-8-8"]!!.jsonObject
        assertNull(bp["alias"])
        assertEquals("8.8.8.8/32", bp["destination"]!!.jsonPrimitive.content)
        assertEquals(listOf("homelab-na1", "homelab-as1"), bp["sites"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
