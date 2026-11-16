package homegap

import homegap.config.VpnConfig
import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.Resource
import homegap.mesh.LivenessResponder
import homegap.model.RawPort
import homegap.platform.Crypto
import homegap.provision.Mesh
import homegap.reconcile.MeshForwards
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpnTest {
    private val member = listOf("Member")
    private val wiki = Mesh.VpnTarget("100.96.128.10", "172.18.0.5", tcpPorts = listOf(443, 80), roles = member)
    private val vault = Mesh.VpnTarget("100.96.128.11", "172.18.0.6", listOf(22), listOf(5353), listOf("Ops"))
    private val vpn = Mesh.Vpn("100.90.0.0/16", "100.90.0.1", 51830, listOf(wiki, vault))
    private val spec = Mesh.AgentSpec("10.77.0.0/24", 51821, "10.77.0.1", emptyList(), emptyList(), vpn = vpn)
    private val key = "A".repeat(43) + "="

    @Test
    fun aDeviceReachesOnlyTheTargetsOfItsRoles() {
        val member = Mesh.VpnPeer(key, "100.90.0.2", listOf("Member"))
        val admin = Mesh.VpnPeer(key, "100.90.0.3", listOf("Admin"))
        val guest = Mesh.VpnPeer(key, "100.90.0.4", listOf("Guest"))
        val rules = Mesh.firewallRules(spec, listOf(member, admin, guest))
        val toWiki = "-d 100.96.128.10 -p tcp -m multiport --dports 80,443 -j DNAT --to-destination 172.18.0.5"
        val toVault = "-d 100.96.128.11 -p tcp -m multiport --dports 22 -j DNAT --to-destination 172.18.0.6"
        assertTrue("-A HG-DNAT -i hg-vpn -s 100.90.0.2 $toWiki" in rules)
        assertFalse("-s 100.90.0.2 -d 100.96.128.11" in rules, "a member has no role of the vault")
        assertTrue("-A HG-DNAT -i hg-vpn -s 100.90.0.3 $toWiki" in rules)
        assertTrue("-A HG-DNAT -i hg-vpn -s 100.90.0.3 $toVault" in rules)
        assertTrue("-s 100.90.0.3 -d 100.96.128.11 -p udp -m multiport --dports 5353" in rules)
        assertFalse("-s 100.90.0.4" in rules, "a device with no role of a target gets no rule")
    }

    @Test
    fun eachOtherPacketOfADeviceIsRefused() {
        val lines = Mesh.firewallRules(spec).lines()
        val forward = lines.filter { it.startsWith("-A HG-FWD") }
        // The first two rules are for the devices: only what a rule sent to a target, and never to the mesh.
        assertEquals("-A HG-FWD -i hg-vpn ! -o hg-mesh -m conntrack --ctstate DNAT -j ACCEPT", forward[0])
        assertEquals("-A HG-FWD -i hg-vpn -j DROP", forward[1])
        assertEquals("-A HG-FWD -j DROP", forward.last())
        // WireGuard of the VPN comes in over the mesh, and the liveness probe comes in from a device.
        assertTrue("-A HG-IN -i hg-mesh -p udp --dport 51830 -j ACCEPT" in lines)
        assertTrue("-A HG-IN -i hg-vpn -d 100.90.0.1 -p udp --dport 51831 -j ACCEPT" in lines)
        assertEquals("-A HG-IN -j DROP", lines.last { it.startsWith("-A HG-IN") })
        // Without the VPN, no rule names its interface.
        assertFalse("hg-vpn" in Mesh.firewallRules(spec.copy(vpn = null)))
    }

    @Test
    fun anExitAtTheHomelabReachesTheInternetAndNoPrivateRange() {
        val access = vpn.copy(exitRoles = member, resolvers = listOf("9.9.9.9"), resolverRoles = listOf("Ops"))
        val exit = spec.copy(vpn = access)
        val peers = listOf(
            Mesh.VpnPeer(key, "100.90.0.2", listOf("Member")),
            Mesh.VpnPeer(key, "100.90.0.3", listOf("Ops")),
            Mesh.VpnPeer(key, "100.90.0.4", listOf("Guest")),
        )
        val lines = Mesh.firewallRules(exit, peers).lines()
        val forward = lines.filter { it.startsWith("-A HG-FWD") }
        // The member leaves here. The chain of the exit drops each private range first, and the mesh.
        assertTrue("-A HG-FWD -i hg-vpn -s 100.90.0.2 -j HG-VPN-OUT" in forward)
        val chain = lines.filter { it.startsWith("-A HG-VPN-OUT") }
        assertEquals("-A HG-VPN-OUT -j ACCEPT", chain.last())
        for (range in listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10")) {
            assertTrue("-A HG-VPN-OUT -d $range -j DROP" in chain, range)
        }
        assertTrue("-A HG-VPN-OUT -o hg-mesh -j DROP" in chain)
        // Ops has the DNS server and no exit. The guest has none of the two.
        assertTrue("-A HG-FWD -i hg-vpn -s 100.90.0.3 -d 9.9.9.9 ! -o hg-mesh -p udp --dport 53 -j ACCEPT" in forward)
        assertFalse(forward.any { "-s 100.90.0.3 -j HG-VPN-OUT" in it })
        assertFalse(forward.any { "-s 100.90.0.4" in it })
        // The rules of a device come before the rule that refuses each other packet of a device.
        assertTrue(forward.indexOf("-A HG-FWD -i hg-vpn -j DROP") > forward.indexOfLast { "-s 100.90.0." in it })
        assertTrue("-A HG-NAT -s 100.90.0.0/16 ! -o hg-mesh -j MASQUERADE" in lines)
        // With no exit in the config, also an admin gets no exit rule.
        val none = Mesh.firewallRules(spec, listOf(Mesh.VpnPeer(key, "100.90.0.9", listOf("Admin"))))
        assertFalse("-j HG-VPN-OUT" in none)
    }

    @Test
    fun theExitOfANodeReachesTheInternetAndNoPrivateRange() {
        val address = Mesh.exitAddress("100.90.0.0/16", "10.77.0.11")
        assertEquals("100.90.255.11", address)
        val exit = Mesh.NodeExit("100.90.0.0/16", address, 51840, "10.77.0.11:8091", "10.77.0.1")
        val lines = Mesh.exitFirewall(exit).lines()
        // WireGuard from each address, the probe of a device, and the list of devices only from the homelab.
        assertTrue("-A HG-EXIT-IN ! -i hg-mesh -p udp --dport 51840 -j ACCEPT" in lines)
        assertTrue("-A HG-EXIT-IN -i hg-exit -d 100.90.255.11 -p udp --dport 51841 -j ACCEPT" in lines)
        assertTrue("-A HG-EXIT-IN -i hg-mesh -s 10.77.0.1 -p tcp --dport 8091 -j ACCEPT" in lines)
        assertTrue("-A HG-EXIT-IN -p tcp --dport 8091 -j DROP" in lines)
        // A device reaches nothing on the node itself but the probe and ping.
        assertTrue("-A HG-EXIT-IN -i hg-exit -j DROP" in lines)
        val out = lines.filter { it.startsWith("-A HG-EXIT-OUT") }
        assertEquals("-A HG-EXIT-OUT -j ACCEPT", out.last())
        for (target in listOf("-d 10.0.0.0/8", "-d 172.16.0.0/12", "-d 192.168.0.0/16", "-o hg-mesh", "-o hg-exit")) {
            assertTrue("-A HG-EXIT-OUT $target -j DROP" in out, target)
        }
        assertTrue("-A HG-EXIT-NAT -s 100.90.0.0/16 ! -o hg-exit -j MASQUERADE" in lines)
        val config = Mesh.exitConfig(exit, "private", listOf(Mesh.VpnPeer(key, "100.90.0.2")))
        assertTrue("ListenPort = 51840" in config && "AllowedIPs = 100.90.0.2/32" in config)
    }

    @Test
    fun aTargetWithManyPortsGetsOneRuleForEach15() {
        val many = Mesh.VpnTarget("100.96.128.12", "172.18.0.7", tcpPorts = (8000..8019).toList(), roles = member)
        val peer = Mesh.VpnPeer(key, "100.90.0.2", listOf("Member"))
        val rules = Mesh.firewallRules(spec.copy(vpn = vpn.copy(targets = listOf(many))), listOf(peer))
        assertEquals(2, rules.lines().count { "-d 100.96.128.12" in it })
    }

    @Test
    fun theInterfaceAdmitsEachDeviceAtItsOwnAddress() {
        val config = Mesh.vpnConfig(vpn, "private", listOf(Mesh.VpnPeer(key, "100.90.0.2")))
        assertTrue("ListenPort = 51830" in config)
        assertTrue("PublicKey = $key\nAllowedIPs = 100.90.0.2/32" in config)
        assertEquals("100.90.0.1", Mesh.vpnAddress("100.90.0.0/16"))
    }

    @Test
    fun eachNodeForwardsThePortOfTheVpn() {
        val result = MeshForwards.Result(nodePorts = mapOf("na1" to listOf(RawPort(3478, "udp")), "as1" to emptyList()))
        val target = MeshForwards.PrivateTarget("wiki.example.net", "172.18.0.5", listOf(443), emptyList(), member)
        val resources = listOf(Resource("wiki.example.net", "100.96.128.10", listOf("Member")))
        val planned = result.withVpn(VpnConfig(), listOf(target), resources)
        assertEquals(listOf(RawPort(3478, "udp"), RawPort(51830, "udp")), planned.nodePorts["na1"])
        assertEquals(listOf(RawPort(51830, "udp")), planned.nodePorts["as1"])
        val expected = Mesh.VpnTarget("100.96.128.10", "172.18.0.5", listOf(443), emptyList(), member)
        assertEquals(listOf(expected), planned.vpn?.targets)
    }

    @Test
    fun aPrivateNameKeepsItsAddress() {
        val root = "/tmp/homegap-vpn-test-${Keys.encode(Crypto.randomBytes(6))}"
        val store = IdentityStore("$root/authority", "$root/identity")
        val subnet = "100.96.128.0/24"
        val first = store.setResources(mapOf("b.example.net" to listOf("Member")), subnet)
        assertEquals("100.96.128.10", first.single().address)
        val second = store.setResources(mapOf("a.example.net" to listOf("Ops"), "b.example.net" to member), subnet)
        assertEquals("100.96.128.10", second.single { it.name == "b.example.net" }.address)
        assertEquals("100.96.128.11", second.single { it.name == "a.example.net" }.address)
        // The address of a name that is gone is free again.
        val third = store.setResources(mapOf("a.example.net" to listOf("Ops"), "c.example.net" to emptyList()), subnet)
        assertEquals("100.96.128.10", third.single { it.name == "c.example.net" }.address)
        assertTrue(third.single { it.name == "c.example.net" }.allows(listOf("Admin")))
        assertFalse(third.single { it.name == "a.example.net" }.allows(listOf("Member")))
        val restarted = IdentityStore("$root/authority", "$root/identity")
        assertEquals(third, restarted.setResources(third.associate { it.name to it.roles }, subnet))
    }

    @Test
    fun theLivenessProbeGetsItsBytesBackWithTheTypeOfAnAnswer() {
        val probe = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 1, 1, 2, 3, 4, 5, 6, 7, 8)
        val answer = LivenessResponder.answer(probe + ByteArray(20), probe.size)
        assertContentEquals(probe.copyOf().also { it[4] = 2 }, answer)
        assertNull(LivenessResponder.answer(probe, 12), "too short")
        assertNull(LivenessResponder.answer(probe.copyOf().also { it[4] = 2 }, probe.size), "an answer gets no answer")
        assertNull(LivenessResponder.answer(probe.copyOf().also { it[0] = 0 }, probe.size))
    }
}
