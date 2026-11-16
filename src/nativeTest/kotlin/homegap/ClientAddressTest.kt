package homegap

import homegap.discovery.TraefikEntryPoint
import homegap.discovery.TraefikTrust
import homegap.discovery.clientAddressIssue
import homegap.model.Planner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientAddressTest {
    private val source = "172.24.0.250"

    private fun door(vararg labels: Pair<String, String>) =
        Planner.plan(testConfig(), listOf(TRAEFIK.copy(labels = TRAEFIK.labels + labels))).frontDoor!!

    private fun entry(proxy: TraefikTrust? = null, forwarded: TraefikTrust? = null) =
        listOf(TraefikEntryPoint("web", ":80"), TraefikEntryPoint("websecure", ":443/tcp", proxy, forwarded))

    @Test
    fun aRangeTrustsTheAddressesInIt() {
        assertTrue(TraefikTrust(trustedIPs = listOf("172.24.0.250")).trusts(source))
        assertTrue(TraefikTrust(trustedIPs = listOf("10.0.0.0/8", "172.24.0.0/16")).trusts(source))
        assertTrue(TraefikTrust(insecure = true).trusts(source))
        assertEquals(false, TraefikTrust(trustedIPs = listOf("172.24.0.251/32", "not an address")).trusts(source))
        assertEquals(false, TraefikTrust().trusts(source))
    }

    @Test
    fun aFrontDoorWithTheProxyProtocolMustTrustTheMeshContainer() {
        val proxied = door("homegap.proxy_protocol" to "true", "homegap.port" to "443")
        val issue = clientAddressIssue(proxied, entry(proxy = TraefikTrust(trustedIPs = listOf("10.0.0.1"))), source)
        assertTrue("websecure" in issue.orEmpty() && source in issue.orEmpty() && "proxyProtocol" in issue.orEmpty())
        assertTrue("proxyProtocol" in clientAddressIssue(proxied, entry(), source).orEmpty())
        assertNull(clientAddressIssue(proxied, entry(proxy = TraefikTrust(trustedIPs = listOf("172.24.0.0/24"))), source))
    }

    @Test
    fun aFrontDoorWithNoHeaderFromHomegapHasNoFinding() {
        assertNull(clientAddressIssue(door("homegap.port" to "443"), entry(), source))
        val otherPort = door("homegap.proxy_protocol" to "true", "homegap.port" to "8443")
        assertNull(clientAddressIssue(otherPort, entry(), source), "no entry point has the port")
    }

    @Test
    fun aTerminatedFrontDoorMustTrustTheForwardedHeaders() {
        val terminated = door("homegap.tls" to "terminate", "homegap.port" to "443")
        assertTrue("forwardedHeaders" in clientAddressIssue(terminated, entry(), source).orEmpty())
        assertNull(clientAddressIssue(terminated, entry(forwarded = TraefikTrust(trustedIPs = listOf(source))), source))
    }
}
