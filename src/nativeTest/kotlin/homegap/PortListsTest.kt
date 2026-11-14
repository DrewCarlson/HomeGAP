package homegap

import homegap.discovery.*
import homegap.model.Planner
import homegap.model.RawPort
import homegap.model.ResourceMode
import kotlin.test.*

/** Port lists (`7881/tcp,7882/udp`), `tcp+udp`, and several named services on one container. */
class PortListsTest {
    private fun parse(vararg labels: Pair<String, String>) =
        parseLabels("id", "svc", labels.toMap(), listOf("test.example.net"))

    private fun parseAll(vararg labels: Pair<String, String>) =
        parseContainerLabels("id", "svc", labels.toMap(), listOf("test.example.net"))

    private fun portErrors(ports: String) =
        parse("homegap.expose" to "x.test.example.net", "homegap.port" to ports).errors

    @Test
    fun aPortListWithProtocolsIsRaw() {
        val spec = parse("homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp, 7882/udp").spec!!
        assertEquals(Protocol.TCP_UDP, spec.protocol)
        assertEquals(
            listOf(PortMapping(7881, 7881, setOf(Protocol.TCP)), PortMapping(7882, 7882, setOf(Protocol.UDP))),
            spec.ports,
        )
        assertEquals(listOf("7881/tcp", "7882/udp"), spec.ports.map { it.toString() })
    }

    @Test
    fun oneProtocolLabelCoversEveryPort() {
        val both = parse(
            "homegap.expose" to "turn.test.example.net",
            "homegap.protocol" to "tcp+udp",
            "homegap.port" to "3478, 5349",
        ).spec!!
        assertEquals(Protocol.TCP_UDP, both.protocol)
        assertTrue(both.ports.all { it.protocols == setOf(Protocol.TCP, Protocol.UDP) })
        // A port can still say otherwise. The protocol of the service is the sum of its ports.
        val udp = parse(
            "homegap.expose" to "g.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "27015/udp",
        ).spec!!
        assertEquals(Protocol.UDP, udp.protocol)
        val reversed = parse(
            "homegap.expose" to "g.test.example.net",
            "homegap.protocol" to "udp+tcp",
            "homegap.port" to "1",
        )
        assertEquals(Protocol.TCP_UDP, reversed.spec!!.protocol)
    }

    @Test
    fun publicAndContainerPortsCanDiffer() {
        val spec = parse(
            "homegap.expose" to "ssh.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "2222:22",
        ).spec!!
        assertEquals(PortMapping(2222, 22, setOf(Protocol.TCP)), spec.ports.single())
        assertEquals(2222, spec.publicPort)
        assertEquals(22, spec.port)
        // The single-port label still works, and only for a single port.
        val singlePort = parse(
            "homegap.expose" to "ssh.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "22",
            "homegap.public_port" to "2222",
        )
        assertEquals(2222, singlePort.spec!!.ports.single().publicPort)
        val twoPorts = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "1,2",
            "homegap.public_port" to "3",
        )
        assertTrue(twoPorts.errors.any { "single port" in it })
    }

    @Test
    fun badPortListsAreReported() {
        assertTrue(portErrors("80,443").any { "one port number for http" in it })
        assertTrue(portErrors("7881/tcp, 7882").any { "'7882' needs a protocol" in it })
        assertTrue(portErrors("7881/sctp").any { "is not a port" in it })
        assertTrue(portErrors("70000/tcp").any { "is not a port" in it })
        assertTrue(portErrors("53/udp, 53/tcp+udp").any { "53/udp is listed twice" in it })
        val reserved = portErrors("443/udp, 7882/udp")
        assertTrue(reserved.any { "public port 443 is reserved" in it })
        assertTrue(portErrors("8443:443/udp").isEmpty(), "mapped away from the reserved port")
        // A private service publishes nothing on the nodes, so its ports are not reserved there.
        val private = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.port" to "22/tcp",
            "homegap.access" to "private",
        )
        assertTrue(private.errors.isEmpty())
    }

    @Test
    fun namedServicesShareTheContainersDefaults() {
        val parsed = parseAll(
            "homegap.regions" to "na1",
            "homegap.host" to "livekit",
            "homegap.services.rtc.expose" to "voice.test.example.net",
            "homegap.services.rtc.port" to "7881/tcp,7882/udp",
            "homegap.services.web.expose" to "lk.test.example.net",
            "homegap.services.web.protocol" to "http",
            "homegap.services.web.port" to "7880",
            "homegap.services.web.regions" to "all",
        )
        assertTrue(parsed.errors.isEmpty(), parsed.errors.toString())
        val (rtc, web) = parsed.specs.sortedBy { it.name }.let { it[0] to it[1] }
        assertEquals("rtc", rtc.name)
        assertEquals("voice-test-example-net--rtc", rtc.key)
        assertEquals(listOf("na1"), rtc.regions)
        assertEquals("livekit", rtc.targetHost)
        assertNull(web.regions, "a named service can set its own")
        assertEquals(7880, web.port)
    }

    @Test
    fun aNamedServicesErrorsCarryItsName() {
        val parsed = parseAll(
            "homegap.expose" to "app.test.example.net",
            "homegap.services.ok.expose" to "ok.test.example.net",
            "homegap.services.ok.port" to "9000/udp",
            "homegap.services.bad.expose" to "bad.test.example.net",
            "homegap.services.bad.port" to "22/tcp",
            "homegap.services.nohost.port" to "1/tcp",
            "homegap.services.Up.expose" to "x.test.example.net",
            "homegap.services.ok.colour" to "red",
        )
        assertEquals(setOf(null, "ok"), parsed.specs.map { it.name }.toSet(), "the others still publish")
        assertTrue(
            parsed.errors.any { it.startsWith("services.bad: public port 22 is reserved") },
            parsed.errors.toString(),
        )
        assertTrue(parsed.errors.any { it.startsWith("services.nohost: homegap.expose is required") })
        assertTrue(parsed.errors.any { it.startsWith("services.Up: the name") })
        assertTrue(
            parsed.warnings.any { "services.ok: unknown label 'homegap.services.ok.colour'" in it },
            parsed.warnings.toString(),
        )
    }

    @Test
    fun eachPortAndProtocolIsAResourceAndAPublishedPort() {
        val voice = container(
            "livekit",
            "homegap.expose" to "voice.test.example.net",
            "homegap.port" to "7881/tcp, 7882/udp, 3478/tcp+udp",
        )
        val legacy = container(
            "game",
            "homegap.expose" to "game.test.example.net",
            "homegap.protocol" to "udp",
            "homegap.port" to "27015",
        )
        val node = Planner.plan(testConfig(), listOf(voice, legacy)).nodes.getValue("na1")
        val resources = node.publicResources.associateBy { it.niceId }
        // A single-port service keeps the id it always had.
        assertEquals(ResourceMode.UDP, resources.getValue("hg-game-test-example-net-udp").mode)
        val voiceId = "hg-voice-test-example-net"
        assertEquals(
            setOf("$voiceId-7881-tcp", "$voiceId-7882-udp", "$voiceId-3478-tcp", "$voiceId-3478-udp"),
            resources.keys.filter { it.startsWith(voiceId) }.toSet(),
        )
        assertEquals(ResourceMode.UDP, resources.getValue("$voiceId-3478-udp").mode)
        assertEquals(3478, resources.getValue("$voiceId-3478-udp").target.port)
        val published = listOf(
            RawPort(7881, "tcp"),
            RawPort(7882, "udp"),
            RawPort(3478, "tcp"),
            RawPort(3478, "udp"),
            RawPort(27015, "udp"),
        )
        assertTrue(published.all { it in node.rawPorts })
    }

    @Test
    fun rawServicesShareAHostnameButNotAPort() {
        val tcp = container("a", "homegap.expose" to "voice.test.example.net", "homegap.port" to "7881/tcp")
        val udp = container(
            "b",
            "homegap.services.rtc.expose" to "voice.test.example.net",
            "homegap.services.rtc.port" to "7882/udp",
        )
        val again = container("c", "homegap.expose" to "other.test.example.net", "homegap.port" to "7882/udp")
        val web = container(
            "d",
            "homegap.services.web.expose" to "voice.test.example.net",
            "homegap.services.web.protocol" to "https",
        )
        val web2 = container("e", "homegap.expose" to "voice.test.example.net", "homegap.protocol" to "https")
        val plan = Planner.plan(testConfig(), listOf(tcp, udp, again, web, web2))
        assertEquals(setOf("a", "b", "d"), plan.services.map { it.containerName }.toSet())
        assertTrue(
            plan.labelErrors.any {
                it.container == "c" && "public port 7882/udp is already used by b (rtc)" in it.message
            },
            plan.labelErrors.toString(),
        )
        assertTrue(plan.labelErrors.any { it.container == "e" && "already claimed by d (web)" in it.message })
        // The shared name has one record set.
        assertEquals(1, plan.dns.count { it.fqdn == "voice.test.example.net" && it.type == "A" && it.nodeId == "na1" })
        // On different nodes the same port is fine.
        val eu = container(
            "f",
            "homegap.expose" to "eu.test.example.net",
            "homegap.port" to "7881/tcp",
            "homegap.regions" to "eu1",
        )
        val na = container(
            "a",
            "homegap.expose" to "voice.test.example.net",
            "homegap.port" to "7881/tcp",
            "homegap.regions" to "na1",
        )
        val apart = Planner.plan(testConfig(), listOf(na, eu))
        assertTrue(apart.labelErrors.isEmpty(), apart.labelErrors.toString())
    }

    @Test
    fun aPrivateServiceListsItsContainerPortsByProtocol() {
        val turn = container(
            "turn",
            "homegap.expose" to "turn.test.example.net",
            "homegap.access" to "private",
            "homegap.port" to "3478/tcp+udp, 5349/tcp, 4000:40/udp",
        )
        val resource = Planner.plan(testConfig(), listOf(turn)).nodes.getValue("na1").privateResources.single()
        assertEquals("3478,5349", resource.tcpPorts)
        assertEquals("40,3478", resource.udpPorts)
    }

    @Test
    fun aRawServiceOnTheRootIsNotTheFrontDoor() {
        val raw = container(
            "dns",
            "homegap.services.dns.expose" to "test.example.net",
            "homegap.services.dns.port" to "5353/udp",
        )
        val plan = Planner.plan(testConfig(), listOf(raw, TRAEFIK))
        assertEquals("traefik", plan.frontDoor?.containerName)
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())
        assertEquals(1, plan.dns.count { it.fqdn == "test.example.net" && it.type == "A" && it.nodeId == "na1" })
        // Two top-level services on one name share its key: one of them needs a name.
        val topLevel = container("dns", "homegap.expose" to "test.example.net", "homegap.port" to "5353/udp")
        val unnamed = Planner.plan(testConfig(), listOf(topLevel, TRAEFIK))
        assertTrue(
            unnamed.labelErrors.any { it.container == "traefik" && "give this service a name" in it.message },
            unnamed.labelErrors.toString(),
        )
    }

    @Test
    fun aContainerWithOnlyNamedServicesDeclaresAService() {
        assertTrue(declaresService(mapOf("homegap.services.web.expose" to "lk.test.example.net")))
        assertTrue(declaresService(mapOf("homegap.expose" to "app.test.example.net")))
        assertFalse(declaresService(mapOf("homegap.port" to "80", "traefik.enable" to "true")))
    }

    @Test
    fun aNamedServiceNamesItsOwnTraefikApi() {
        val labels = mapOf(
            "homegap.expose" to "app.test.example.net",
            "homegap.services.web.expose" to "lk.test.example.net",
            "homegap.services.web.private_network" to "p",
            "homegap.services.web.private_middleware" to "private-only@file",
            "homegap.services.web.traefik_api" to "http://other:9000",
        )
        assertEquals(listOf("http://other:9000"), traefikApiUrls("front", labels))
        val parsed = parseContainerLabels("id", "front", labels, listOf("test.example.net"))
        assertEquals("http://other:9000", parsed.specs.single { it.name == "web" }.traefikApi)
        assertEquals(emptyList(), traefikApiUrls("front", mapOf("homegap.expose" to "app.test.example.net")))
    }
}
