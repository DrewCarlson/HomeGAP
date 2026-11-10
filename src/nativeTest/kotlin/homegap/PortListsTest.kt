package homegap

import homegap.discovery.*
import homegap.model.Planner
import homegap.model.RawPort
import homegap.model.ResourceMode
import kotlin.test.*

/** Port lists (`7881/tcp,7882/udp`), `tcp+udp`, and several named services on one container. */
class PortListsTest {
    private fun parse(vararg l: Pair<String, String>) = parseLabels("id", "svc", l.toMap(), "test.example.net")
    private fun parseAll(vararg l: Pair<String, String>) = parseContainerLabels("id", "svc", l.toMap(), "test.example.net")

    @Test
    fun aPortListWithProtocolsIsRaw() {
        val s = parse("homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp, 7882/udp").spec!!
        assertEquals(Protocol.TCP_UDP, s.protocol)
        assertEquals(listOf(PortMapping(7881, 7881, setOf(Protocol.TCP)), PortMapping(7882, 7882, setOf(Protocol.UDP))), s.ports)
        assertEquals(listOf("7881/tcp", "7882/udp"), s.ports.map { it.toString() })
    }

    @Test
    fun oneProtocolLabelCoversEveryPort() {
        val both = parse("homegap.expose" to "turn.test.example.net", "homegap.protocol" to "tcp+udp", "homegap.port" to "3478, 5349").spec!!
        assertEquals(Protocol.TCP_UDP, both.protocol)
        assertTrue(both.ports.all { it.protocols == setOf(Protocol.TCP, Protocol.UDP) })
        // A port can still say otherwise; the service is what its ports add up to.
        val udp = parse("homegap.expose" to "g.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "27015/udp").spec!!
        assertEquals(Protocol.UDP, udp.protocol)
        assertEquals(Protocol.TCP_UDP, parse("homegap.expose" to "g.test.example.net", "homegap.protocol" to "udp+tcp", "homegap.port" to "1").spec!!.protocol)
    }

    @Test
    fun publicAndContainerPortsCanDiffer() {
        val s = parse("homegap.expose" to "ssh.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "2222:22").spec!!
        assertEquals(PortMapping(2222, 22, setOf(Protocol.TCP)), s.ports.single())
        assertEquals(2222, s.publicPort)
        assertEquals(22, s.port)
        // The single-port label still works, and only for a single port.
        assertEquals(2222, parse("homegap.expose" to "ssh.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "22", "homegap.public_port" to "2222").spec!!.ports.single().publicPort)
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "1,2", "homegap.public_port" to "3").errors.any { "single port" in it })
    }

    @Test
    fun badPortListsAreReported() {
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "80,443").errors.any { "one port number for http" in it })
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "7881/tcp, 7882").errors.any { "'7882' needs a protocol" in it })
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "7881/sctp").errors.any { "is not a port" in it })
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "70000/tcp").errors.any { "is not a port" in it })
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "53/udp, 53/tcp+udp").errors.any { "53/udp is listed twice" in it })
        val reserved = parse("homegap.expose" to "x.test.example.net", "homegap.port" to "443/udp, 7882/udp")
        assertTrue(reserved.errors.any { "public port 443 is reserved" in it })
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "8443:443/udp").errors.isEmpty(), "mapped away from the reserved port")
        // A private service publishes nothing on the nodes, so its ports are not reserved there.
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.port" to "22/tcp", "homegap.access" to "private").errors.isEmpty())
    }

    @Test
    fun namedServicesShareTheContainersDefaults() {
        val r = parseAll(
            "homegap.regions" to "na1",
            "homegap.host" to "livekit",
            "homegap.services.rtc.expose" to "voice.test.example.net",
            "homegap.services.rtc.port" to "7881/tcp,7882/udp",
            "homegap.services.web.expose" to "lk.test.example.net",
            "homegap.services.web.protocol" to "http",
            "homegap.services.web.port" to "7880",
            "homegap.services.web.regions" to "all",
        )
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val (rtc, web) = r.specs.sortedBy { it.name }.let { it[0] to it[1] }
        assertEquals("rtc", rtc.name)
        assertEquals("voice-test-example-net--rtc", rtc.key)
        assertEquals(listOf("na1"), rtc.regions)
        assertEquals("livekit", rtc.targetHost)
        assertNull(web.regions, "a named service can set its own")
        assertEquals(7880, web.port)
    }

    @Test
    fun aNamedServicesErrorsCarryItsName() {
        val r = parseAll(
            "homegap.expose" to "app.test.example.net",
            "homegap.services.ok.expose" to "ok.test.example.net",
            "homegap.services.ok.port" to "9000/udp",
            "homegap.services.bad.expose" to "bad.test.example.net",
            "homegap.services.bad.port" to "22/tcp",
            "homegap.services.nohost.port" to "1/tcp",
            "homegap.services.Up.expose" to "x.test.example.net",
            "homegap.services.ok.colour" to "red",
        )
        assertEquals(setOf(null, "ok"), r.specs.map { it.name }.toSet(), "the others still publish")
        assertTrue(r.errors.any { it.startsWith("services.bad: public port 22 is reserved") }, r.errors.toString())
        assertTrue(r.errors.any { it.startsWith("services.nohost: homegap.expose is required") })
        assertTrue(r.errors.any { it.startsWith("services.Up: the name") })
        assertTrue(r.warnings.any { "services.ok: unknown label 'homegap.services.ok.colour'" in it }, r.warnings.toString())
    }

    @Test
    fun eachPortAndProtocolIsAResourceAndAPublishedPort() {
        val voice = container(
            "livekit", "homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp, 7882/udp, 3478/tcp+udp",
        )
        val legacy = container("game", "homegap.expose" to "game.test.example.net", "homegap.protocol" to "udp", "homegap.port" to "27015")
        val n = Planner.plan(testConfig(), listOf(voice, legacy)).nodes.getValue("na1")
        val res = n.publicResources.associateBy { it.niceId }
        // A single-port service keeps the id it always had.
        assertEquals(ResourceMode.UDP, res.getValue("hg-game-test-example-net-udp").mode)
        val k = "hg-voice-test-example-net"
        assertEquals(setOf("$k-7881-tcp", "$k-7882-udp", "$k-3478-tcp", "$k-3478-udp"), res.keys.filter { it.startsWith(k) }.toSet())
        assertEquals(ResourceMode.UDP, res.getValue("$k-3478-udp").mode)
        assertEquals(3478, res.getValue("$k-3478-udp").target.port)
        assertTrue(listOf(RawPort(7881, "tcp"), RawPort(7882, "udp"), RawPort(3478, "tcp"), RawPort(3478, "udp"), RawPort(27015, "udp")).all { it in n.rawPorts })
    }

    @Test
    fun rawServicesShareAHostnameButNotAPort() {
        val tcp = container("a", "homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp")
        val udp = container("b", "homegap.services.rtc.expose" to "voice.test.example.net", "homegap.services.rtc.port" to "7882/udp")
        val again = container("c", "homegap.expose" to "other.test.example.net", "homegap.port" to "7882/udp")
        val web = container("d", "homegap.services.web.expose" to "voice.test.example.net", "homegap.services.web.protocol" to "https")
        val web2 = container("e", "homegap.expose" to "voice.test.example.net", "homegap.protocol" to "https")
        val plan = Planner.plan(testConfig(), listOf(tcp, udp, again, web, web2))
        assertEquals(setOf("a", "b", "d"), plan.services.map { it.containerName }.toSet())
        assertTrue(plan.labelErrors.any { it.container == "c" && "public port 7882/udp is already used by b (rtc)" in it.message }, plan.labelErrors.toString())
        assertTrue(plan.labelErrors.any { it.container == "e" && "already claimed by d (web)" in it.message })
        // One record set for the shared name.
        assertEquals(1, plan.dns.count { it.fqdn == "voice.test.example.net" && it.type == "A" && it.nodeId == "na1" })
        // On different nodes the same port is fine.
        val eu = container("f", "homegap.expose" to "eu.test.example.net", "homegap.port" to "7881/tcp", "homegap.regions" to "eu1")
        val apart = Planner.plan(testConfig(), listOf(container("a", "homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp", "homegap.regions" to "na1"), eu))
        assertTrue(apart.labelErrors.isEmpty(), apart.labelErrors.toString())
    }

    @Test
    fun aPrivateServiceListsItsContainerPortsByProtocol() {
        val p = container("turn", "homegap.expose" to "turn.test.example.net", "homegap.access" to "private", "homegap.port" to "3478/tcp+udp, 5349/tcp, 4000:40/udp")
        val r = Planner.plan(testConfig(), listOf(p)).nodes.getValue("na1").privateResources.single()
        assertEquals("3478,5349", r.tcpPorts)
        assertEquals("40,3478", r.udpPorts)
    }

    @Test
    fun aRawServiceOnTheRootIsNotTheFrontDoor() {
        val raw = container("dns", "homegap.services.dns.expose" to "test.example.net", "homegap.services.dns.port" to "5353/udp")
        val plan = Planner.plan(testConfig(), listOf(raw, TRAEFIK))
        assertEquals("traefik", plan.frontDoor?.containerName)
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        assertEquals(1, plan.dns.count { it.fqdn == "test.example.net" && it.type == "A" && it.nodeId == "na1" })
        // Two top-level services on one name share its key: one of them needs a name.
        val unnamed = Planner.plan(testConfig(), listOf(container("dns", "homegap.expose" to "test.example.net", "homegap.port" to "5353/udp"), TRAEFIK))
        assertTrue(unnamed.labelErrors.any { it.container == "traefik" && "give this service a name" in it.message }, unnamed.labelErrors.toString())
    }
}
