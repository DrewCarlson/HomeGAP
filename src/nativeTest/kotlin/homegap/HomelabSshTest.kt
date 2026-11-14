package homegap

import homegap.config.ConfigLoader
import homegap.model.HomelabServices
import homegap.model.Planner
import homegap.tunnels.HostGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomelabSshTest {
    private val config = ConfigLoader.parse("$TEST_CONFIG\nhomelab:\n  ssh:\n    enabled: true\n    roles: [Member]\n")

    @Test
    fun theGatewayOfTheDefaultRouteIsTheAddressOfTheHost() {
        val routeTable = listOf(
            "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
            "eth0\t00000000\t010020AC\t0003\t0\t0\t0\t00000000\t0\t0\t0",
            "eth0\t000020AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0",
            "eth1\t000021AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0",
        ).joinToString("\n")

        assertEquals("172.32.0.1", HostGateway.parse(routeTable))
        assertNull(HostGateway.parse(routeTable.lines().drop(2).joinToString("\n")), "no default route")
        assertNull(HostGateway.parse(""))
    }

    @Test
    fun theConfigTurnsSshToTheHostIntoAPrivateService() {
        val host = HomelabServices.containers(config, hostAddress = "172.32.0.1")
        val plan = Planner.plan(config, listOf(TRAEFIK) + host)

        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        val resource = plan.nodes.getValue("na1").privateResources.single()
        assertEquals("172.32.0.1", resource.destination)
        assertEquals("ssh.test.example.net", resource.alias)
        assertEquals("22", resource.tcpPorts)
        assertEquals(listOf("Member"), resource.roles)
        assertTrue(plan.dns.none { it.fqdn == "ssh.test.example.net" }, "a private service has no public record")
        assertEquals(HomelabServices.HOST_NAME, plan.services.single { it.hostname == "ssh.test.example.net" }.containerName)
    }

    @Test
    fun thereIsNoServiceWhileItIsOffOrTheHostHasNoAddress() {
        assertTrue(HomelabServices.containers(testConfig(), hostAddress = "172.32.0.1").isEmpty(), "off by default")
        assertTrue(HomelabServices.containers(config, hostAddress = null).isEmpty(), "the supervisor has no address yet")
    }

    @Test
    fun aWrongHostnameOrPortIsRefused() {
        val badName = "$TEST_CONFIG\nhomelab:\n  ssh:\n    enabled: true\n    hostname: My.Ssh\n"
        val badPort = "$TEST_CONFIG\nhomelab:\n  ssh:\n    enabled: true\n    port: 70000\n"

        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(badName) }
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(badPort) }
        ConfigLoader.parse("$TEST_CONFIG\nhomelab:\n  ssh:\n    hostname: My.Ssh\n")
    }
}
