package homegap

import homegap.identity.Directory
import homegap.identity.Keys
import homegap.identity.Signed
import homegap.identity.User
import homegap.identity.VpnAccess
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.provision.Mesh
import homegap.signin.Device
import homegap.signin.HomeTunnel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HomeTunnelTest {
    private val dir = "/tmp/homegap-home-tunnel-test-${Keys.encode(Crypto.randomBytes(6))}"
    private val key = "prC0ViSqZD8cjD3aVDH34ZtyhCdYCCqb7pZb1jPNcTM="
    private val device = Device(id = "d1", userId = "u1", name = "Pixel", address = "100.90.0.2")
    private val directory = Directory(
        users = listOf(User(id = "u1", email = "ada@example.net", roles = listOf("Member"))),
        vpn = VpnAccess(nodeExitRoles = listOf("Member")),
    )
    private val tunnel = HomeTunnel(dir, "vpn.example.net") { directory }

    private fun exit(node: String, last: Int, vararg peers: String) = Mesh.ExitStatus(
        publicKey = "exit-$node",
        address = "100.90.255.$last",
        port = 51840,
        peers = peers.toList(),
        node = node,
        host = "203.0.113.$last",
    )

    private fun writeStatus(vararg exits: Mesh.ExitStatus) {
        val status = Mesh.VpnStatus("home", 51830, "100.90.0.1", listOf(key), exits.toList())
        Files.mkdirs(dir)
        Files.writeAtomic("$dir/${Mesh.VPN_STATUS_FILE}", Signed.JSON.encodeToString(status))
    }

    @Test
    fun aNodeThatGetsTheKeyLateStillBecomesAnExit() = runBlocking {
        writeStatus(exit("in-mumbai", 12, key), exit("us-virginia", 11))

        val connection = tunnel.admit(device, key, "in-mumbai")
        assertEquals(listOf("homelab", "in-mumbai"), connection.sites.map { it.name }, "only a node with the key at the start")

        writeStatus(exit("in-mumbai", 12, key), exit("us-virginia", 11, key))
        assertEquals(listOf("in-mumbai", "us-virginia"), tunnel.exitSites(device, key).map { it.name })
        assertEquals(11, tunnel.exitSites(device, key).last().siteId)
    }

    @Test
    fun noExitsWithoutTheRole() {
        writeStatus(exit("us-virginia", 11, key))
        val guest = HomeTunnel(dir, "vpn.example.net") { directory.copy(vpn = VpnAccess(nodeExitRoles = listOf("Admin"))) }
        assertEquals(emptyList(), guest.exitSites(device, key))
    }

    @Test
    fun aChangeOfTheExitsReachesTheConnections() = runBlocking {
        writeStatus(exit("us-virginia", 11))
        val watch = launch { tunnel.watch() }
        try {
            val changed = async { withTimeout(10.seconds) { tunnel.changes.first() } }
            // The watch reads the old status first; only the change of the exits counts.
            delay(500)
            writeStatus(exit("us-virginia", 11, key))
            changed.await()
        } finally {
            watch.cancel()
        }
    }
}
