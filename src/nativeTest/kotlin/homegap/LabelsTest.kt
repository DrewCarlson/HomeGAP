package homegap

import homegap.discovery.*
import kotlin.test.*

class LabelsTest {
    private fun parse(vararg l: Pair<String, String>) = parseLabels("id", "svc", l.toMap(), "test.example.net")

    @Test
    fun notExposedReturnsNothing() {
        val r = parse("traefik.enable" to "true")
        assertNull(r.spec)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun defaultsMatchSpec() {
        val s = parse("homegap.expose" to "app.test.example.net", "homegap.port" to "8080").spec!!
        assertEquals(Protocol.HTTP, s.protocol)
        assertEquals(TlsMode.TERMINATE, s.tls, "plain http is always terminated on the node")
        assertEquals(Access.PUBLIC, s.access)
        assertEquals(AuthMode.NONE, s.auth)
        assertNull(s.regions)
        assertNull(s.healthcheck)
        assertEquals("svc", s.targetHost)
    }

    @Test
    fun httpsDefaultsToPassthroughAndPort443() {
        val s = parse("homegap.expose" to "test.example.net", "homegap.protocol" to "https", "homegap.wildcard" to "true").spec!!
        assertEquals(TlsMode.PASSTHROUGH, s.tls)
        assertEquals(443, s.port)
        assertTrue(s.wildcard)
    }

    @Test
    fun rawRequiresPortAndRejectsReservedPublicPort() {
        assertTrue(parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp").errors.any { "port is required" in it })
        val r = parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "22")
        assertTrue(r.errors.any { "reserved" in it })
        val ok = parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "22", "homegap.public_port" to "2222").spec!!
        assertEquals(2222, ok.publicPort)
        // private raw services never bind a public port, so 22 is fine
        assertNotNull(parse("homegap.expose" to "x.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "22", "homegap.access" to "private").spec)
    }

    @Test
    fun authNeedsTerminate() {
        val r = parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "https", "homegap.auth" to "sso")
        assertTrue(r.errors.any { "requires homegap.tls=terminate" in it })
        assertNotNull(parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "https", "homegap.tls" to "terminate", "homegap.auth" to "sso").spec)
    }

    @Test
    fun rejectsForeignDomainsAndBadValues() {
        assertTrue(parse("homegap.expose" to "a.other.invalid").errors.any { "outside the managed domain" in it })
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.protocol" to "ftp").errors.isNotEmpty())
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.wildcard" to "true").errors.any { "root domain" in it })
        assertTrue(parse("homegap.expose" to "a.test.example.net", "homegap.healthcheck" to "health").errors.isNotEmpty())
    }

    @Test
    fun regionsAndExtensions() {
        val s = parse(
            "homegap.expose" to "a.test.example.net", "homegap.regions" to "na1, as1", "homegap.host" to "10.0.0.5",
            "homegap.roles" to "Member,Family", "homegap.healthcheck" to "tcp", "homegap.pause" to "yes",
        ).spec!!
        assertEquals(listOf("na1", "as1"), s.regions)
        assertEquals("10.0.0.5", s.targetHost)
        assertEquals(listOf("Member", "Family"), s.roles)
        assertEquals(HealthCheck.Tcp, s.healthcheck)
        assertTrue(s.paused)
        assertTrue(s.servesNode("na1") && !s.servesNode("eu1"))
    }

    @Test
    fun unknownLabelsWarn() {
        val r = parse("homegap.expose" to "a.test.example.net", "homegap.typo" to "1")
        assertNotNull(r.spec)
        assertTrue(r.warnings.any { "homegap.typo" in it })
    }

    @Test
    fun onlyLabeledContainersTriggerAPass() {
        fun event(action: String, vararg labels: Pair<String, String>) =
            DockerEvent("container", action, DockerEventActor("c1", mapOf("name" to "app", "image" to "app:1") + labels))
        // A crash-looping container without HomeGAP labels never triggers a pass.
        for (action in listOf("start", "die", "stop", "destroy")) {
            assertFalse(event(action, "com.docker.compose.service" to "app").concernsHomegap(), action)
        }
        // A labeled one does, including when it stops or is removed (its labels come with the event).
        for (action in listOf("start", "die", "stop", "destroy")) {
            assertTrue(event(action, "homegap.expose" to "app.test.example.net").concernsHomegap(), action)
        }
        assertTrue(event("start", "homegap.pause" to "true").concernsHomegap())
    }

    @Test
    fun nodesPullTheExactImageTheHomelabRuns() {
        val pulled = DockerImage("sha256:1", repoDigests = listOf("ghcr.io/example/homegap-cluster@sha256:abc"))
        assertEquals("ghcr.io/example/homegap-cluster@sha256:abc", pulled.pinned("ghcr.io/example/homegap-cluster:latest"))
        assertEquals("ghcr.io/example/homegap-cluster@sha256:abc", pulled.pinned("ghcr.io/example/homegap-cluster"))
        // A registry with a port: the colon there is not a tag.
        val local = DockerImage("sha256:2", repoDigests = listOf("registry.example:5000/cluster@sha256:def"))
        assertEquals("registry.example:5000/cluster@sha256:def", local.pinned("registry.example:5000/cluster:7"))
        // Built locally (never pulled): other hosts get the reference as it is.
        assertEquals("homegap-cluster:e2e", DockerImage("sha256:3", null).pinned("homegap-cluster:e2e"))
        assertEquals("other/repo:1", pulled.pinned("other/repo:1"))
    }
}
