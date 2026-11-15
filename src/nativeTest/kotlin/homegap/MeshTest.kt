package homegap

import homegap.config.ClusterConfig
import homegap.config.ConfigLoader
import homegap.config.MeshConfig
import homegap.model.RawPort
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
        fun errorFor(candidate: MeshConfig) = assertFailsWith<IllegalArgumentException> {
            ConfigLoader.validate(config.copy(mesh = candidate))
        }.message!!
        assertTrue("100.64.0.0/10" in errorFor(MeshConfig(subnet = "100.89.0.0/24")))
        assertTrue("Gerbil" in errorFor(MeshConfig(port = 51820)))
        assertTrue("too small" in errorFor(MeshConfig(subnet = "10.77.0.0/27")))
        ConfigLoader.validate(config.copy(cluster = ClusterConfig()))
    }

    @Test
    fun nodeConfigPeersEveryoneAndLeavesTheKeyToTheNode() {
        val config = Mesh.nodeConfig(
            mesh,
            "10.77.0.10",
            listOf(
                Mesh.Peer("homelab", "HOMELAB=", "10.77.0.1", endpoint = null),
                Mesh.Peer("eu1", "EU1=", "10.77.0.11", endpoint = "203.0.113.20"),
            ),
        )
        assertTrue("Address = 10.77.0.10/24" in config && "ListenPort = 51821" in config)
        assertTrue("PrivateKey = ${Mesh.KEY_PLACEHOLDER}" in config, "the private key is filled in on the node")
        assertTrue("Endpoint = 203.0.113.20:51821" in config)
        val homelab = config.substringAfter("# homelab").substringBefore("# eu1")
        assertFalse("Endpoint" in homelab, "the homelab dials out; nodes never need its address")
    }

    @Test
    fun homelabKeepsEveryPathAlive() {
        val peer = Mesh.Peer("na1", "NA1=", "10.77.0.10", endpoint = "2001:db8::1")
        val config = Mesh.homelabConfig(mesh, "PRIV=", listOf(peer))
        assertTrue("Endpoint = [2001:db8::1]:51821" in config, "IPv6 endpoints are bracketed")
        assertTrue("PersistentKeepalive = 25" in config)
    }

    @Test
    fun nodeScriptAppliesInPlaceAndReportsItsKey() {
        val config = Mesh.nodeConfig(mesh, "10.77.0.10", emptyList())
        val script = Mesh.nodeScript(mesh, "10.77.0.10", config, openFirewall = true)
        assertTrue("wg syncconf" in script, "peer changes apply without tearing the interface down")
        assertTrue("wg genkey" in script && "PUBKEY=" in script)
        assertTrue("allow 51821/udp" in script && $$"ufw $spec comment homegap" in script)
        // A container of the node has no path to the control ports of the other members.
        assertTrue("-I DOCKER-USER -o hg-mesh -p tcp -m multiport --dports 2380,26379 -j DROP" in script)
        assertTrue(
            "PostUp = iptables -N DOCKER-USER" in Mesh.nodeConfig(mesh, "10.77.0.10", emptyList()),
            "the rule comes back at boot",
        )
        // A rule for the bridge that admits more does not stay.
        assertTrue($$"ufw delete $spec" in script)
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
    fun firewallOpensTheMeshPortOnlyWhenItGetsOne() {
        val node = NodeConfig(id = "eu1", host = "203.0.113.30", sshKeyFile = "/k")
        assertTrue("allow 51821/udp" in FirewallRules.specs(node, meshPort = 51821))
        assertFalse(FirewallRules.specs(node).any { "51821" in it })
    }

    @Test
    fun homelabForwardsOnlyTheListedPortsToTheirTargets() {
        val spec = Mesh.AgentSpec(
            subnet = "10.77.0.0/24",
            port = 51821,
            address = "10.77.0.1",
            peers = emptyList(),
            openPorts = listOf(5432),
            forwards = listOf(
                Mesh.Forward("tcp", 41554, "172.20.0.2:443"),
                Mesh.Forward("udp", 3478, "172.20.0.9:3478"),
                Mesh.Forward("tcp", 5432, "172.20.0.2:5432"),
            ),
        )
        val rules = Mesh.firewallRules(spec)

        val toTraefik = "-A HG-DNAT -i hg-mesh -d 10.77.0.1 -p tcp --dport 41554 -j DNAT --to-destination 172.20.0.2:443"
        assertTrue(toTraefik in rules)
        assertTrue("-A HG-FWD -i hg-mesh -d 172.20.0.2 -p tcp --dport 443 -m conntrack --ctstate DNAT -j ACCEPT" in rules)
        assertTrue("-p udp --dport 3478 -j DNAT --to-destination 172.20.0.9:3478" in rules)
        // A port of the cluster gets no rule.
        assertFalse("--to-destination 172.20.0.2:5432" in rules)
        // The namespace refuses all other traffic of a node.
        assertEquals("-A HG-FWD -j DROP", rules.lines().last { it.startsWith("-A HG-FWD") })
        assertEquals("-A HG-IN -j DROP", rules.lines().last { it.startsWith("-A HG-IN") })
    }

    @Test
    fun aSpecWithNoForwardsKeepsTheNamespaceClosed() {
        val spec = Mesh.AgentSpec("10.77.0.0/24", 51821, "10.77.0.1", emptyList(), emptyList())
        assertFalse("DNAT --to-destination" in Mesh.firewallRules(spec))
    }

    @Test
    fun nodeForwardsItsRawPortsToTheHomelabOverTheMesh() {
        val script = Mesh.forwardScript("10.77.0.1", listOf(RawPort(3478, "udp"), RawPort(22, "tcp")))
        val toHome = "-A HG-RAW ! -i hg-mesh -p tcp -m addrtype --dst-type LOCAL --dport 22 -j DNAT --to-destination 10.77.0.1:22"
        assertTrue(toHome in script)
        assertTrue("-p udp -m addrtype --dst-type LOCAL --dport 3478 -j DNAT --to-destination 10.77.0.1:3478" in script)
        // The homelab accepts only the mesh address of the node as the source.
        assertTrue("-A HG-RAW-NAT -o hg-mesh -m conntrack --ctstate DNAT -j MASQUERADE" in script)
        // Before the rules of Docker and of ufw, and only for a connection that the node changed.
        assertTrue("iptables -t filter -I FORWARD 1 -j HG-RAW-FWD" in script)
        assertTrue("-A HG-RAW-FWD -o hg-mesh -m conntrack --ctstate DNAT -j ACCEPT" in script)
        // A node with no raw port still gets the script, so an old rule does not stay.
        assertFalse("DNAT --to-destination" in Mesh.forwardScript("10.77.0.1", emptyList()))
        assertTrue("PostUp = [ ! -x /etc/wireguard/hg-mesh-forwards.sh ]" in Mesh.nodeConfig(mesh, "10.77.0.10", emptyList()))
    }
}
