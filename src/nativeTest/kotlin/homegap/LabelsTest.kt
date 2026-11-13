package homegap

import homegap.discovery.*
import kotlin.test.*

class LabelsTest {
    private fun parse(vararg labels: Pair<String, String>) =
        parseLabels("id", "svc", labels.toMap(), "test.example.net")

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
        assertEquals(AuthMode.NONE, spec.auth)
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
            "homegap.port" to "22",
        )
        assertTrue(reserved.errors.any { "reserved" in it })
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
    fun authNeedsTerminate() {
        val passthrough = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.protocol" to "https",
            "homegap.auth" to "sso",
        )
        assertTrue(passthrough.errors.any { "requires homegap.tls=terminate" in it })
        val terminate = parse(
            "homegap.expose" to "a.test.example.net",
            "homegap.protocol" to "https",
            "homegap.tls" to "terminate",
            "homegap.auth" to "sso",
        )
        assertNotNull(terminate.spec)
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
    fun nodesPullTheExactImageTheHomelabRuns() {
        val digest = "ghcr.io/example/homegap-cluster@sha256:abc"
        val pulled = DockerImage("sha256:1", repoDigests = listOf(digest))
        assertEquals(digest, pulled.pinned("ghcr.io/example/homegap-cluster:latest"))
        assertEquals(digest, pulled.pinned("ghcr.io/example/homegap-cluster"))
        // A registry with a port: the colon there is not a tag.
        val local = DockerImage("sha256:2", repoDigests = listOf("registry.example:5000/cluster@sha256:def"))
        assertEquals("registry.example:5000/cluster@sha256:def", local.pinned("registry.example:5000/cluster:7"))
        // An image from a local build has no digest: other hosts get the reference as it is.
        assertEquals("homegap-cluster:e2e", DockerImage("sha256:3", repoDigests = null).pinned("homegap-cluster:e2e"))
        assertEquals("other/repo:1", pulled.pinned("other/repo:1"))
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
