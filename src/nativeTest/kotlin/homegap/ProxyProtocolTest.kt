package homegap

import homegap.node.ProxyProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProxyProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun theHeaderOfAnIpv4ConnectionHasBothEndpoints() {
        val source = ProxyProtocol.Endpoint(ProxyProtocol.ipv4("203.0.113.7")!!, 51000)
        val destination = ProxyProtocol.Endpoint(ProxyProtocol.ipv4("198.51.100.20")!!, 2222)
        val expected = bytes(
            0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
            0x21, 0x11, 0x00, 0x0C,
            203, 0, 113, 7,
            198, 51, 100, 20,
            0xC7, 0x38,
            0x08, 0xAE,
        )
        assertContentEquals(expected, ProxyProtocol.header(source, destination))
    }

    @Test
    fun theHeaderOfAnIpv6ConnectionHasTheLongAddresses() {
        val source = ProxyProtocol.Endpoint(ByteArray(16) { 1 }, 1)
        val destination = ProxyProtocol.Endpoint(ByteArray(16) { 2 }, 2)
        val header = ProxyProtocol.header(source, destination)
        assertContentEquals(bytes(0x21, 0x21, 0x00, 0x24), header.copyOfRange(12, 16))
        assertContentEquals(ByteArray(16) { 2 }, header.copyOfRange(32, 48))
    }

    @Test
    fun theTwoEndpointsMustHaveOneFamily() {
        val short = ProxyProtocol.Endpoint(ByteArray(4), 1)
        val long = ProxyProtocol.Endpoint(ByteArray(16), 1)
        assertFailsWith<IllegalArgumentException> { ProxyProtocol.header(short, long) }
    }

    @Test
    fun anIpv4AddressParsesAlsoInItsMappedForm() {
        assertContentEquals(bytes(10, 77, 0, 1), ProxyProtocol.ipv4("10.77.0.1"))
        assertContentEquals(bytes(203, 0, 113, 7), ProxyProtocol.ipv4("::ffff:203.0.113.7"))
        assertNull(ProxyProtocol.ipv4("2001:db8::1"))
        assertNull(ProxyProtocol.ipv4("10.77.0.256"))
        assertNull(ProxyProtocol.ipv4("node.example.net"))
    }
}
