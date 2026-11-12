package homegap

import homegap.config.ClusterConfig
import homegap.config.ConfigLoader
import homegap.config.MeshConfig
import homegap.config.NodeConfig
import homegap.provision.FirewallRules
import homegap.provision.Mesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshTest {
    private val mesh = MeshConfig()

    @Test
    fun addressesAreFixedBySlot() {
        assertEquals("10.77.0.1", Mesh.homelabAddress(mesh))
        assertEquals("10.77.0.10", Mesh.nodeAddress(mesh, 0))
        assertEquals("10.77.0.13", Mesh.nodeAddress(mesh, 3))
        assertEquals("10.77.0.0/24", Mesh.parseSubnet("10.77.0.0/24").toString())
        assertFailsWith<IllegalArgumentException> { Mesh.parseSubnet("10.77.0.5/24") }
        assertFailsWith<IllegalArgumentException> { Mesh.parseSubnet("10.77.0.0/30") }
        assertTrue(Mesh.parseSubnet("100.70.0.0/24").overlapsCgnat())
        assertFalse(Mesh.parseSubnet("10.77.0.0/24").overlapsCgnat())
    }

    @Test
    fun rejectsUnsafeMeshSettings() {
        val config = ConfigLoader.parse(TEST_CONFIG)
        fun errorFor(m: MeshConfig) = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.validate(config.copy(cluster = ClusterConfig(m)))
        }.message!!
        assertTrue("100.64.0.0/10" in errorFor(MeshConfig(subnet = "100.89.0.0/24")))
        assertTrue("Gerbil" in errorFor(MeshConfig(port = 51820)))
        assertTrue("too small" in errorFor(MeshConfig(subnet = "10.77.0.0/27")))
        ConfigLoader.validate(config.copy(cluster = ClusterConfig()))
    }

    @Test
    fun nodeConfigPeersEveryoneAndLeavesTheKeyToTheNode() {
        val conf = Mesh.nodeConfig(
            mesh, "10.77.0.10",
            listOf(
                Mesh.Peer("homelab", "HOMELAB=", "10.77.0.1", null),
                Mesh.Peer("eu1", "EU1=", "10.77.0.11", "203.0.113.20"),
            ),
        )
        assertTrue("Address = 10.77.0.10/24" in conf && "ListenPort = 51821" in conf)
        assertTrue("PrivateKey = ${Mesh.KEY_PLACEHOLDER}" in conf, "the private key is filled in on the node")
        assertTrue("Endpoint = 203.0.113.20:51821" in conf)
        val homelab = conf.substringAfter("# homelab").substringBefore("# eu1")
        assertFalse("Endpoint" in homelab, "the homelab dials out; nodes never need its address")
    }

    @Test
    fun homelabKeepsEveryPathAlive() {
        val conf = Mesh.homelabConfig(mesh, "PRIV=", listOf(Mesh.Peer("na1", "NA1=", "10.77.0.10", "2001:db8::1")))
        assertTrue("Endpoint = [2001:db8::1]:51821" in conf, "IPv6 endpoints are bracketed")
        assertTrue("PersistentKeepalive = 25" in conf)
    }

    @Test
    fun nodeScriptAppliesInPlaceAndReportsItsKey() {
        val script = Mesh.nodeScript(mesh, "10.77.0.10", Mesh.nodeConfig(mesh, "10.77.0.10", emptyList()), openFirewall = true)
        assertTrue("wg syncconf" in script, "peer changes apply without tearing the interface down")
        assertTrue("wg genkey" in script && "PUBKEY=" in script)
        assertTrue("allow 51821/udp" in script && "ufw \$spec comment homegap" in script)
        // A container of the node has no path to the control ports of the other members.
        assertTrue("-I DOCKER-USER -o hg-mesh -p tcp -m multiport --dports 2380,26379 -j DROP" in script)
        assertTrue("PostUp = iptables -N DOCKER-USER" in Mesh.nodeConfig(mesh, "10.77.0.10", emptyList()), "the rule comes back at boot")
        // A rule for the bridge that admits more does not stay.
        assertTrue("ufw delete \$spec" in script)
        assertFalse("ufw allow" in Mesh.nodeScript(mesh, "10.77.0.10", "", openFirewall = false))
    }

    @Test
    fun parsesWireGuardDumps() {
        val output = "noise\nDUMP-BEGIN\n" +
            "PRIVKEY\tPUBKEY\t51821\toff\n" +
            "NA1=\t(none)\t203.0.113.10:51821\t10.77.0.10/32\t1794140000\t1200\t3400\t25\n" +
            "EU1=\t(none)\t(none)\t10.77.0.11/32\t0\t0\t0\t25\n" +
            "DUMP-END\n"
        val peers = Mesh.parseDump(Mesh.dumpSection(output))
        assertEquals(2, peers.size)
        assertEquals("203.0.113.10:51821", peers[0].endpoint)
        assertEquals(1794140000, peers[0].latestHandshake)
        assertNull(peers[1].endpoint)
        assertEquals(0, peers[1].latestHandshake)
    }

    @Test
    fun firewallOpensTheMeshPortOnlyWithACluster() {
        val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k")
        assertTrue("allow 51821/udp" in FirewallRules.specs(node, meshPort = 51821))
        assertFalse(FirewallRules.specs(node).any { "51821" in it })
    }
}
