package homegap

import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.Sessions
import homegap.identity.Signed
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.provision.Mesh
import homegap.signin.Device
import homegap.signin.Devices
import homegap.signin.SignIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeControlTest {
    private val root = "/tmp/homegap-nodecontrol-test-${Keys.encode(Crypto.randomBytes(6))}"

    @Test
    fun aSessionOfANodeGivesNoAccessToTheHomelab() {
        val store = IdentityStore("$root/authority", "$root/identity")
        val (user, password) = store.create("ada@example.net", "Ada", listOf("Member"))
        val file = "$root/identity/${IdentityStore.DIRECTORY_FILE}"
        val signed = { Signed.directory(Files.readText(file), store.publicKey) }

        // The node: its own key, and a copy of the directory that also names that key.
        lateinit var node: SignIn
        val own = { signed()?.let { it.copy(issuers = it.issuers + ("node:na1" to node.publicKey)) } }
        node = SignIn("$root/node", own, issuer = "node:na1")
        val code = node.start("Pixel", "Pangolin")!!
        assertIs<SignIn.Outcome.SignedIn>(node.signIn(code, "ada@example.net", password, "203.0.113.7"))
        val session = node.collect(code)!!
        assertEquals(user.id, node.user(session)?.id, "the node accepts its own session")

        // The homelab: the directory with the signature of the authority does not name the key of the node.
        val homelab = SignIn("$root/home", signed)
        store.setIssuer(SignIn.ISSUER, homelab.publicKey)
        assertNull(homelab.user(session))
        assertNull(Sessions.read(session, signed()!!, 0))
        // A node cannot claim the name of the homelab either: the key is not the one of the directory.
        val forged = SignIn("$root/forger", signed, issuer = SignIn.ISSUER)
        val other = forged.start("Pixel", "Pangolin")!!
        forged.signIn(other, "ada@example.net", password, "203.0.113.7")
        assertNull(homelab.user(forged.collect(other)!!))
    }

    @Test
    fun aNodeGivesAddressesFromItsOwnPartOfTheSubnet() {
        val homelabDevice = Device("known", "u1", "phone", "hash", "100.90.0.2")
        val home = Devices("$root/home-devices", "100.90.0.0/16")
        assertEquals("100.90.0.2", home.create("u1", "phone").first.address)

        val pool = Devices.Pool(node = 11, known = { listOf(homelabDevice) })
        val node = Devices("$root/node-devices", "100.90.0.0/16", pool)
        val (created, _) = node.create("u2", "laptop")
        assertEquals("100.90.111.1", created.address)
        assertEquals("100.90.111.2", node.create("u2", "tablet").first.address)
        // The node accepts a device of the homelab as it is.
        assertEquals(homelabDevice, node.get("known"))
        assertNotNull(node.get(created.id))
        // A subnet with no room for the pools of the nodes.
        val small = Devices("$root/small", "100.90.0.0/24", Devices.Pool(node = 11))
        assertFailsWith<IllegalStateException> { small.create("u1", "phone") }
    }

    @Test
    fun theAgentOfANodeTakesItsCallsOnlyFromTheRightPlaces() {
        val utility = "100.96.128.0/24"
        val control = Mesh.NodeControl("vpn.example.net", "10.77.0.11:8092", "key", "as1", "203.0.113.11", utility)
        val exit = Mesh.NodeExit("100.90.0.0/16", "100.90.255.11", 51840, "10.77.0.11:8091", "10.77.0.1", control)
        val lines = Mesh.exitFirewall(exit).lines()
        // The Traefik of the node, from a Docker network. No other source, and not over the mesh.
        assertTrue("-A HG-EXIT-IN ! -i hg-mesh -s 172.16.0.0/12 -p tcp --dport 8092 -j ACCEPT" in lines)
        assertTrue("-A HG-EXIT-IN -p tcp --dport 8092 -j DROP" in lines)
        val none = Mesh.exitFirewall(exit.copy(control = null))
        assertTrue("8092" !in none)
    }
}
