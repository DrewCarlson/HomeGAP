package homegap

import homegap.clients.SshPool
import homegap.config.ConfigLoader
import homegap.provision.TunnelDns
import homegap.reconcile.DiffCollector
import homegap.reconcile.ReconcileContext
import homegap.reconcile.ReconcileLog
import homegap.reconcile.ResourceReconciler
import homegap.state.StateStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelDnsTest {
    private fun clusterConfig(cluster: String = "") =
        ConfigLoader.parse("$TEST_CONFIG\ncluster:\n  image: example/cluster:7\n$cluster")
    private val exits = mapOf("na1" to "exit-na1", "as1" to "exit-as1")
    private val homelab = mapOf("na1" to "homelab-na1", "as1" to "homelab-as1")

    @Test
    fun theAppsDefaultResolverIsReachableFromTheNearestNode() {
        // With node exits, lookups leave from the node the device relays through.
        val config = clusterConfig("  exit_nodes:\n    nodes: true\n")
        val (dns, sites) = TunnelDns.resources(config, exits, homelab).entries.single().toPair()
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
        val resources = TunnelDns.resources(clusterConfig(), exits, homelab)
        assertEquals(listOf("homelab-na1", "homelab-as1"), resources.values.single())
        // Node exits on but no exit site yet: the homelab sites, rather than nothing.
        val nodeExits = clusterConfig("  exit_nodes:\n    nodes: true\n")
        val withoutExitSites = TunnelDns.resources(nodeExits, exitSites = emptyMap(), homelabSites = homelab)
        assertEquals(listOf("homelab-na1", "homelab-as1"), withoutExitSites.values.single())
    }

    @Test
    fun resolversAreConfigurableAndCanBeTurnedOff() {
        val config = clusterConfig(
            "  tunnel_dns:\n" +
                "    resolvers: [\"1.1.1.1\", \"2606:4700:4700::1111\"]\n" +
                "    roles: [Member, Family]\n",
        )
        val resources = TunnelDns.resources(config, exits, homelab).keys.associateBy { it.niceId }
        assertEquals(setOf("hg-dns-1-1-1-1", "hg-dns-2606-4700-4700-1111"), resources.keys)
        assertEquals("2606:4700:4700::1111/128", resources.getValue("hg-dns-2606-4700-4700-1111").destination)
        assertEquals(listOf("Member", "Family"), resources.getValue("hg-dns-1-1-1-1").roles)
        assertEquals(resources.keys, TunnelDns.wanted(config))

        val noResolvers = clusterConfig("  tunnel_dns:\n    resolvers: []\n")
        assertTrue(TunnelDns.resources(noResolvers, exits, homelab).isEmpty())
        assertTrue(TunnelDns.resources(ConfigLoader.parse(TEST_CONFIG), exits, homelab).isEmpty(), "cluster only")
        assertFailsWith<IllegalArgumentException> { clusterConfig("  tunnel_dns:\n    resolvers: [dns.google]\n") }
    }

    @Test
    fun theBlueprintHasNoAlias() {
        // Pangolin rejects an empty alias. A client reaches a resolver by its address.
        val (dns, sites) = TunnelDns.resources(clusterConfig(), exits, homelab).entries.single().toPair()
        val context = ReconcileContext(
            config = clusterConfig(),
            state = StateStore(tmpPath("state")),
            log = ReconcileLog(),
            ssh = SshPool(controlDir = tmpPath("ssh"), knownHostsFile = tmpPath("kh")),
            localHttp = HttpClient(MockEngine { error("no calls") }),
            dryRun = false,
            diff = DiffCollector(),
        )
        val blueprint = ResourceReconciler(context).buildBlueprint(emptyMap(), mapOf(dns to sites))
        val resolver = blueprint["private-resources"]!!.jsonObject["hg-dns-8-8-8-8"]!!.jsonObject
        assertNull(resolver["alias"])
        assertEquals("8.8.8.8/32", resolver["destination"]!!.jsonPrimitive.content)
        val resolverSites = resolver["sites"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("homelab-na1", "homelab-as1"), resolverSites)
    }
}
