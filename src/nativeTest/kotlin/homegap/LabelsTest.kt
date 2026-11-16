package homegap

import homegap.discovery.*
import kotlin.test.*

class LabelsTest {
    private fun parse(vararg labels: Pair<String, String>) =
        parseLabels("id", "svc", labels.toMap(), listOf("test.example.net"))

    @Test
    fun notExposedReturnsNothing() {
        val result = parse("traefik.enable" to "true")
        assertNull(result.spec)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun defaultsMatchSpec() {
        val spec = parse("homegap.expose" to "app.test.example.net", "homegap.port" to "8080").spec!!
        assertEquals(Protocol.HTTP, spec.protocol)
        assertEquals(TlsMode.TERMINATE, spec.tls, "plain http is always terminated on the node")
        assertEquals(Access.PUBLIC, spec.access)
        assertNull(spec.regions)
        assertNull(spec.healthcheck)
        assertEquals("svc", spec.targetHost)
    }

    @Test
    fun httpsDefaultsToPassthroughAndPort443() {
        val spec = parse(
            "homegap.expose" to "test.example.net",
            "homegap.protocol" to "https",
            "homegap.wildcard" to "true",
        ).spec!!
        assertEquals(TlsMode.PASSTHROUGH, spec.tls)
        assertEquals(443, spec.port)
        assertTrue(spec.wildcard)
    }

    @Test
    fun rawRequiresPortAndRejectsReservedPublicPort() {
        val withoutPort = parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp")
        assertTrue(withoutPort.errors.any { "port is required" in it })
        val reserved = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "8091",
        )
        assertTrue(reserved.errors.any { "public port 8091 is reserved" in it }, reserved.errors.toString())
        for (port in listOf(80, 443, 8092)) {
            assertTrue(port in RESERVED_NODE_PORTS, "the node uses $port itself")
        }
        val ok = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "22",
            "homegap.public_port" to "2222",
        ).spec!!
        assertEquals(2222, ok.publicPort)
        // A private raw service never binds a public port, so 22 is fine.
        val privateService = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "22",
            "homegap.access" to "private",
        )
        assertNotNull(privateService.spec)
    }

    @Test
    fun theLabelsOfTheSignInOfANodeAreNotKnown() {
        for (label in listOf("homegap.auth" to "sso", "homegap.pin_env" to "PIN")) {
            val result = parse("homegap.expose" to "a.test.example.net", label)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertTrue(
                result.warnings.any { "unknown label '${label.first}' ignored" in it },
                result.warnings.toString(),
            )
        }
    }

    @Test
    fun rejectsForeignDomainsAndBadValues() {
        assertTrue(parse("homegap.expose" to "a.other.invalid").errors.any { "outside the managed domain" in it })
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "ftp").errors.isNotEmpty())
        val wildcard = parse("homegap.expose" to "a.test.example.net", "homegap.wildcard" to "true")
        assertTrue(wildcard.errors.any { "root domain" in it })
        val healthcheck = parse("homegap.expose" to "a.test.example.net", "homegap.healthcheck" to "health")
        assertTrue(healthcheck.errors.isNotEmpty())
    }

    @Test
    fun regionsAndExtensions() {
        val spec = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.regions" to "na1, as1",
            "homegap.host" to "10.0.0.5",
            "homegap.roles" to "Member,Family",
            "homegap.healthcheck" to "tcp",
            "homegap.pause" to "yes",
        ).spec!!
        assertEquals(listOf("na1", "as1"), spec.regions)
        assertEquals("10.0.0.5", spec.targetHost)
        assertEquals(listOf("Member", "Family"), spec.roles)
        assertEquals(HealthCheck.Tcp, spec.healthcheck)
        assertTrue(spec.paused)
        assertTrue(spec.servesNode("na1") && !spec.servesNode("eu1"))
    }

    @Test
    fun unknownLabelsWarn() {
        val result = parse("homegap.expose" to "a.test.example.net", "homegap.typo" to "1")
        assertNotNull(result.spec)
        assertTrue(result.warnings.any { "homegap.typo" in it })
    }

    @Test
    fun onlyLabeledContainersTriggerAPass() {
        fun event(action: String, vararg labels: Pair<String, String>): DockerEvent {
            val actor = DockerEventActor("c1", mapOf("name" to "app", "image" to "app:1") + labels)
            return DockerEvent("container", action, actor)
        }
        // A container without HomeGAP labels that restarts in a loop never triggers a pass.
        for (action in listOf("start", "die", "stop", "destroy")) {
            assertFalse(event(action, "com.docker.compose.service" to "app").concernsHomegap(), action)
        }
        // A container with labels does, also when it stops or goes away (its labels come with the event).
        for (action in listOf("start", "die", "stop", "destroy")) {
            assertTrue(event(action, "homegap.expose" to "app.test.example.net").concernsHomegap(), action)
        }
        assertTrue(event("start", "homegap.pause" to "true").concernsHomegap())
    }

    @Test
    fun aPublicPortWithABadPortListIsALabelError() {
        // One entry of the list is not a port. The answer is an error for the label, not a crash.
        val result = parse(
            "homegap.expose" to "x.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "7881/tcp, bogus",
            "homegap.public_port" to "2222",
        )
        assertNull(result.spec)
        assertTrue(
            result.errors.any { "homegap.public_port only works with a single port" in it },
            result.errors.toString(),
        )
    }
}
