package homegap

import homegap.acme.CertNames
import homegap.acme.NodeCertificate
import homegap.acme.X509
import homegap.config.ClusterConfig
import homegap.discovery.parseContainerLabels
import homegap.model.Planner
import homegap.provision.MaintenancePage
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import homegap.reconcile.ResourceReconciler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The certificate of the nodes: its names, its dates, and what a node can terminate. */
class NodeCertificateTest {
    private val config = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
    private val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80")
    private val git = container(
        "git",
        "homegap.expose" to "git.test.example.net",
        "homegap.protocol" to "https",
        "homegap.port" to "8443",
    )
    private val terminateDoor = container(
        "traefik",
        "homegap.expose" to "test.example.net",
        "homegap.wildcard" to "true",
        "homegap.protocol" to "https",
        "homegap.tls" to "terminate",
    )

    @Test
    fun aPassthroughNameIsNeverOnTheCertificate() {
        val plan = Planner.plan(config, listOf(TRAEFIK, wiki, git))
        val names = CertNames.of(config, plan)
        assertTrue("wiki.test.example.net" in names, "the node ends TLS for a service in terminate mode")
        assertTrue("pangolin-na1.test.example.net" in names && "pangolin-as1.test.example.net" in names)
        assertTrue(config.clientHost in names)
        assertFalse("git.test.example.net" in names, "a dedicated passthrough name")
        assertFalse("test.example.net" in names, "the passthrough front door")
        assertTrue(names.none { it.startsWith("*") }, "no wildcard: $names")
        assertEquals(names.sorted(), names)
    }

    @Test
    fun theFrontDoorInTerminateModeWithAWildcardIsTheOnlyWildcard() {
        val plan = Planner.plan(config, listOf(terminateDoor, wiki, git))
        val names = CertNames.of(config, plan)
        assertEquals("*.test.example.net", names.last())
        assertTrue("test.example.net" in names)
        assertFalse("wiki.test.example.net" in names, "the wildcard covers it, and an ACME server refuses both")
        // The nodes hold a certificate that covers git.test.example.net: passthrough protects nothing.
        assertTrue(plan.services.none { it.hostname == "git.test.example.net" })
        assertTrue(
            plan.labelErrors.any { it.container == "git" && "passthrough is refused" in it.message },
            plan.labelErrors.toString(),
        )
        // The planner does not refuse a raw service under the domain.
        val echo = container(
            "echo",
            "homegap.expose" to "echo.test.example.net",
            "homegap.protocol" to "tcp",
            "homegap.port" to "9000",
        )
        val withEcho = Planner.plan(config, listOf(terminateDoor, echo))
        assertTrue(withEcho.services.any { it.hostname == "echo.test.example.net" })
    }

    @Test
    fun wildcardsCoverOneLabel() {
        assertTrue(CertNames.covers("example.net", "a.example.net"))
        assertFalse(CertNames.covers("example.net", "a.b.example.net"))
        assertFalse(CertNames.covers("example.net", "example.net"))
    }

    @Test
    fun readsTheDatesOfACertificate() {
        val validity = X509.validity(SAMPLE)!!
        assertEquals(1794469908, validity.notBefore)
        assertEquals(1794988307, validity.notAfter)
        assertNull(X509.validity("not a certificate"))
        assertEquals(0, X509.epochSeconds(year = 1970, month = 1, day = 1, hour = 0, minute = 0, second = 0))
        assertEquals(951782400, X509.epochSeconds(year = 2000, month = 2, day = 29, hour = 0, minute = 0, second = 0))
    }

    @Test
    fun ordersAgainWhenNamesChangeOrAThirdOfTheLifetimeRemains() {
        val certificate = NodeCertificate(tmpPath("acme"))
        val meta = NodeCertificate.Meta(
            names = listOf("a.example.net"),
            directory = "https://ca.example/dir",
            certName = "homegap-1",
            notBefore = 0,
            notAfter = 9000,
        )
        assertEquals("there is no certificate", certificate.due(null, meta.names, meta.directory, 10))
        assertNull(certificate.due(meta, meta.names, meta.directory, 5999))
        assertEquals("it expires soon", certificate.due(meta, meta.names, meta.directory, 6000))
        assertEquals("the names changed", certificate.due(meta, listOf("b.example.net"), meta.directory, 10))
        assertEquals("the ACME directory changed", certificate.due(meta, meta.names, "https://other.example/dir", 10))
        assertNull(certificate.current(), "nothing ordered yet")
    }

    @Test
    fun aFailedOrderShowsTheErrorLinesOfLego() {
        val certificate = NodeCertificate(tmpPath("acme"))
        val infoLine = "time=1 level=INFO msg=\"Deactivating authorization.\"\n"
        val errorLine = "time=2 level=ERROR msg=Error error=\"dns01: time limit exceeded\"\n"
        val output = infoLine + errorLine
        assertEquals("msg=Error error=\"dns01: time limit exceeded\"", certificate.failure(output))
        assertEquals("plain text", certificate.failure("plain text\n"))
    }

    @Test
    fun nodesHaveNoCertificateResolver() {
        val plan = Planner.plan(config, listOf(TRAEFIK, wiki))
        val desired = plan.nodes.getValue("na1")
        val secrets = NodeSecrets(
            setupToken = "t",
            serverSecret = "s",
            adminEmail = "a@b",
            adminPassword = "p",
            crowdsecBouncerKey = "k",
        )
        val files = NodeTemplates.staticFiles(config, desired.node, secrets, desired.rawPorts, slot = 0)
            .associateBy { it.path }
        val static = files.getValue("config/traefik/traefik_config.yml").content
        assertFalse("certificatesResolvers" in static || "acme" in static || "certResolver" in static, static)
        assertFalse("letsencrypt:/letsencrypt" in files.getValue("compose.yml").content)
        assertTrue("cert_resolver: \"\"" in files.getValue("config/config.yml").content)
        assertFalse("prefer_wildcard_cert" in files.getValue("config/config.yml").content)
        val routes = NodeTemplates.dynamicRoutes(desired, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertFalse("certResolver" in routes, routes)
        assertTrue(
            files.keys.none { it.endsWith(".crt") || it.endsWith(".key") },
            "the certificate is not part of the stack",
        )
        assertTrue("certificate abc" in NodeTemplates.tlsDynamic("abc"))
    }

    @Test
    fun theMaintenancePageCoversOnlyNamesTheNodeTerminates() {
        val withStatusPage = config.copy(statusPage = config.statusPage.copy(enabled = true))
        val plan = Planner.plan(withStatusPage, listOf(TRAEFIK, wiki, git))
        val desired = plan.nodes.getValue("na1")
        assertEquals(listOf("status.test.example.net", "wiki.test.example.net"), desired.maintenanceHosts)
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7, "hg-wiki-test-example-net" to 8, "hg-git-test-example-net" to 9)
        val tree = ResourceReconciler.yamlToJson(MaintenancePage.routes(desired, ids)).jsonObject
        val tcp = tree["tcp"]!!.jsonObject["routers"]!!.jsonObject
        assertTrue(
            tcp.values.all { it.jsonObject["tls"]!!.jsonObject["passthrough"]!!.jsonPrimitive.content == "true" },
            "no router ends TLS for a passthrough name",
        )
        assertEquals(setOf("homegap-frontdoor-unreachable", "homegap-git-test-example-net-unreachable"), tcp.keys)
        val router = tree["http"]!!.jsonObject["routers"]!!.jsonObject["homegap-maintenance"]!!.jsonObject
        val rule = router["rule"]!!.jsonPrimitive.content
        assertTrue("Host(`wiki.test.example.net`)" in rule && "Host(`status.test.example.net`)" in rule, rule)
        assertFalse("git.test.example.net" in rule || "HostRegexp" in rule, rule)
        val excluded = " && !Host(`${desired.managementHost}`) && !Host(`${withStatusPage.clientHost}`)"
        assertTrue(rule.endsWith(excluded), rule)
        assertTrue(router["tls"]!!.jsonObject.isEmpty())
        val targets = Json.parseToJsonElement(MaintenancePage.probes(desired, ids))
            .jsonObject["targets"]!!
            .jsonArray
            .map { it.jsonObject["service_suffix"]!!.jsonPrimitive.content }
        assertTrue(
            "-${Planner.FRONTDOOR_TCP}-service" in targets && "-hg-wiki-test-example-net-service" in targets,
            targets.toString(),
        )
        // Before Pangolin has a resource, there is nothing to check and so nothing to cover.
        assertFalse("routers" in MaintenancePage.routes(desired, emptyMap()))
        val noProbes = Json.parseToJsonElement(MaintenancePage.probes(desired, emptyMap())).jsonObject
        assertTrue(noProbes["targets"]!!.jsonArray.isEmpty())
        // With the page off for the only terminated service and no shared name, the page covers nothing.
        val wikiWithoutPage = container(
            "wiki",
            "homegap.expose" to "wiki.test.example.net",
            "homegap.port" to "80",
            "homegap.maintenance_page" to "false",
        )
        val off = Planner.plan(config, listOf(TRAEFIK, wikiWithoutPage))
        assertTrue(off.nodes.getValue("na1").maintenanceHosts.isEmpty())
    }

    @Test
    fun theMaintenancePageLabelNeedsTerminateMode() {
        fun parse(vararg labels: Pair<String, String>) =
            parseContainerLabels("id", "c", labels.toMap(), listOf("test.example.net"))
        val passthrough = parse(
            "homegap.expose" to "git.test.example.net",
            "homegap.protocol" to "https",
            "homegap.maintenance_page" to "true",
        )
        assertTrue(
            passthrough.errors.any { "homegap.maintenance_page needs homegap.tls=terminate" in it },
            passthrough.errors.toString(),
        )
        val silent = parse("homegap.expose" to "git.test.example.net", "homegap.protocol" to "https")
        assertTrue(silent.errors.isEmpty())
        assertFalse(silent.specs.single().maintenancePage)
        assertTrue(
            parse("homegap.expose" to "wiki.test.example.net").specs.single().maintenancePage,
            "on by default in terminate mode",
        )
    }

    @Test
    fun caaRecordsAllowOnlyTheDnsChallenge() {
        val caa = Planner.plan(config, listOf(TRAEFIK)).dns.filter { it.type == "CAA" }
        assertEquals(listOf("issue", "issuewild"), caa.map { it.tag })
        val policy = "letsencrypt.org; validationmethods=dns-01"
        assertTrue(caa.all { it.value == policy && it.name == "test" && it.fqdn == "test.example.net" })
        val off = config.copy(acme = config.acme.copy(caa = false))
        assertTrue(Planner.plan(off, listOf(TRAEFIK)).dns.none { it.type == "CAA" })
    }

    private companion object {
        /** A certificate from a local test server, made for this test. */
        val SAMPLE = """
            -----BEGIN CERTIFICATE-----
            MIIByjCCAW+gAwIBAgIIVphEFkpaDqwwCgYIKoZIzj0EAwIwKDEmMCQGA1UEAxMd
            UGViYmxlIEludGVybWVkaWF0ZSBDQSA2OTYwNGUwHhcNMjYxMTEyMDc1MTQ4WhcN
            MjYxMTE4MDc1MTQ3WjAAMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcL6j4VTC
            rGYYnSw2BUW1EMKzwmXhqpL3CStxECGE7hFX2ZuqRTdU7g5RT+swKZVb1oPB1ISx
            lhevMmz/D2Gg46OBqjCBpzAOBgNVHQ8BAf8EBAMCB4AwEwYDVR0lBAwwCgYIKwYB
            BQUHAwEwDAYDVR0TAQH/BAIwADAfBgNVHSMEGDAWgBRx2RHs+VbnJ27RZSWpiBnq
            QxqzrzBRBgNVHREBAf8ERzBFgh1wYW5nb2xpbi1uYTEudGVzdC5leGFtcGxlLm5l
            dIIQdGVzdC5leGFtcGxlLm5ldIISKi50ZXN0LmV4YW1wbGUubmV0MAoGCCqGSM49
            BAMCA0kAMEYCIQD1/Gh/yhKAk4rne3uM9rbDZX9Ca0OWEOuqRqQtWu195wIhAOlU
            31DLsxWO2+pKDin22BIw+LR3nmqaD0ULhYHVtMyI
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
