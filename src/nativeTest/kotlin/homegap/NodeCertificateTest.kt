package homegap

import homegap.acme.CertNames
import homegap.acme.NodeCertificate
import homegap.acme.X509
import homegap.config.ClusterConfig
import homegap.discovery.parseContainerLabels
import homegap.model.Planner
import homegap.provision.MaintenancePage
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

/** The certificate of the nodes: its names, its dates, and what a node may terminate. */
class NodeCertificateTest {
    private val cfg = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
    private val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80")
    private val git = container("git", "homegap.expose" to "git.test.example.net", "homegap.protocol" to "https", "homegap.port" to "8443")
    private val terminateDoor = container(
        "traefik",
        "homegap.expose" to "test.example.net", "homegap.wildcard" to "true",
        "homegap.protocol" to "https", "homegap.tls" to "terminate",
    )

    @Test
    fun aPassthroughNameIsNeverOnTheCertificate() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK, wiki, git))
        val names = CertNames.of(cfg, plan)
        assertTrue("wiki.test.example.net" in names, "the node ends TLS for a service in terminate mode")
        assertTrue("pangolin-na1.test.example.net" in names && "pangolin-as1.test.example.net" in names)
        assertTrue(cfg.clientHost in names)
        assertFalse("git.test.example.net" in names, "a dedicated passthrough name")
        assertFalse("test.example.net" in names, "the passthrough front door")
        assertTrue(names.none { it.startsWith("*") }, "no wildcard: $names")
        assertEquals(names.sorted(), names)
    }

    @Test
    fun theFrontDoorInTerminateModeWithAWildcardIsTheOnlyWildcard() {
        val plan = Planner.plan(cfg, listOf(terminateDoor, wiki, git))
        val names = CertNames.of(cfg, plan)
        assertEquals("*.test.example.net", names.last())
        assertTrue("test.example.net" in names)
        assertFalse("wiki.test.example.net" in names, "the wildcard covers it, and an ACME server refuses both")
        // The nodes hold a certificate that covers git.test.example.net: passthrough protects nothing.
        assertTrue(plan.services.none { it.hostname == "git.test.example.net" })
        assertTrue(plan.labelErrors.any { it.container == "git" && "passthrough is refused" in it.message }, plan.labelErrors.toString())
        // A raw service under the domain is not refused.
        val echo = container("echo", "homegap.expose" to "echo.test.example.net", "homegap.protocol" to "tcp", "homegap.port" to "9000")
        assertTrue(Planner.plan(cfg, listOf(terminateDoor, echo)).services.any { it.hostname == "echo.test.example.net" })
    }

    @Test
    fun wildcardsCoverOneLabel() {
        assertTrue(CertNames.covers("example.net", "a.example.net"))
        assertFalse(CertNames.covers("example.net", "a.b.example.net"))
        assertFalse(CertNames.covers("example.net", "example.net"))
    }

    @Test
    fun readsTheDatesOfACertificate() {
        val v = X509.validity(SAMPLE)!!
        assertEquals(1794469908, v.notBefore)
        assertEquals(1794988307, v.notAfter)
        assertNull(X509.validity("not a certificate"))
        assertEquals(0, X509.epochSeconds(1970, 1, 1, 0, 0, 0))
        assertEquals(951782400, X509.epochSeconds(2000, 2, 29, 0, 0, 0))
    }

    @Test
    fun ordersAgainWhenNamesChangeOrAThirdOfTheLifetimeRemains() {
        val nc = NodeCertificate(tmpPath("acme"))
        val meta = NodeCertificate.Meta(listOf("a.example.net"), "https://ca.example/dir", "homegap-1", notBefore = 0, notAfter = 9000)
        assertEquals("there is no certificate", nc.due(null, meta.names, meta.directory, 10))
        assertNull(nc.due(meta, meta.names, meta.directory, 5999))
        assertEquals("it expires soon", nc.due(meta, meta.names, meta.directory, 6000))
        assertEquals("the names changed", nc.due(meta, listOf("b.example.net"), meta.directory, 10))
        assertEquals("the ACME directory changed", nc.due(meta, meta.names, "https://other.example/dir", 10))
        assertNull(nc.current(), "nothing ordered yet")
    }

    @Test
    fun nodesHaveNoCertificateResolver() {
        val plan = Planner.plan(cfg, listOf(TRAEFIK, wiki))
        val nd = plan.nodes.getValue("na1")
        val secrets = homegap.provision.NodeSecrets("t", "s", "a@b", "p", "k")
        val files = NodeTemplates.staticFiles(cfg, nd.node, secrets, nd.rawPorts, 0).associateBy { it.path }
        val static = files.getValue("config/traefik/traefik_config.yml").content
        assertFalse("certificatesResolvers" in static || "acme" in static || "certResolver" in static, static)
        assertFalse("letsencrypt:/letsencrypt" in files.getValue("compose.yml").content)
        assertTrue("cert_resolver: \"\"" in files.getValue("config/config.yml").content)
        assertFalse("prefer_wildcard_cert" in files.getValue("config/config.yml").content)
        val routes = NodeTemplates.dynamicRoutes(nd, mapOf(Planner.FRONTDOOR_TCP to 7), cluster = true)
        assertFalse("certResolver" in routes, routes)
        assertTrue(files.keys.none { it.endsWith(".crt") || it.endsWith(".key") }, "the certificate is not part of the stack")
        assertTrue("certificate abc" in NodeTemplates.tlsDynamic("abc"))
    }

    @Test
    fun theMaintenancePageCoversOnlyNamesTheNodeTerminates() {
        val st = cfg.copy(statusPage = cfg.statusPage.copy(enabled = true))
        val plan = Planner.plan(st, listOf(TRAEFIK, wiki, git))
        val nd = plan.nodes.getValue("na1")
        assertEquals(listOf("status.test.example.net", "wiki.test.example.net"), nd.maintenanceHosts)
        val ids = mapOf(Planner.FRONTDOOR_TCP to 7, "hg-wiki-test-example-net" to 8, "hg-git-test-example-net" to 9)
        val tree = ResourceReconciler.yamlToJson(MaintenancePage.routes(nd, ids)).jsonObject
        val tcp = tree["tcp"]!!.jsonObject["routers"]!!.jsonObject
        assertTrue(tcp.values.all { it.jsonObject["tls"]!!.jsonObject["passthrough"]!!.jsonPrimitive.content == "true" }, "no router ends TLS for a passthrough name")
        assertEquals(setOf("homegap-frontdoor-unreachable", "homegap-git-test-example-net-unreachable"), tcp.keys)
        val router = tree["http"]!!.jsonObject["routers"]!!.jsonObject["homegap-maintenance"]!!.jsonObject
        val rule = router["rule"]!!.jsonPrimitive.content
        assertTrue("Host(`wiki.test.example.net`)" in rule && "Host(`status.test.example.net`)" in rule, rule)
        assertFalse("git.test.example.net" in rule || "HostRegexp" in rule, rule)
        assertTrue(rule.endsWith(" && !Host(`${nd.managementHost}`) && !Host(`${st.clientHost}`)"), rule)
        assertTrue(router["tls"]!!.jsonObject.isEmpty())
        val targets = Json.parseToJsonElement(MaintenancePage.probes(nd, ids)).jsonObject["targets"]!!.jsonArray.map { it.jsonObject["service_suffix"]!!.jsonPrimitive.content }
        assertTrue("-${Planner.FRONTDOOR_TCP}-service" in targets && "-hg-wiki-test-example-net-service" in targets, targets.toString())
        // Before Pangolin has a resource, there is nothing to check and so nothing to cover.
        assertFalse("routers" in MaintenancePage.routes(nd, emptyMap()))
        assertTrue(Json.parseToJsonElement(MaintenancePage.probes(nd, emptyMap())).jsonObject["targets"]!!.jsonArray.isEmpty())
        // With the page off for the only terminated service and no shared name, nothing is covered.
        val off = Planner.plan(cfg, listOf(TRAEFIK, container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80", "homegap.maintenance_page" to "false")))
        assertTrue(off.nodes.getValue("na1").maintenanceHosts.isEmpty())
    }

    @Test
    fun theMaintenancePageLabelNeedsTerminateMode() {
        fun parse(vararg labels: Pair<String, String>) = parseContainerLabels("id", "c", labels.toMap(), "test.example.net")
        val passthrough = parse("homegap.expose" to "git.test.example.net", "homegap.protocol" to "https", "homegap.maintenance_page" to "true")
        assertTrue(passthrough.errors.any { "homegap.maintenance_page needs homegap.tls=terminate" in it }, passthrough.errors.toString())
        val silent = parse("homegap.expose" to "git.test.example.net", "homegap.protocol" to "https")
        assertTrue(silent.errors.isEmpty())
        assertFalse(silent.specs.single().maintenancePage)
        assertTrue(parse("homegap.expose" to "wiki.test.example.net").specs.single().maintenancePage, "on by default in terminate mode")
    }

    @Test
    fun caaRecordsAllowOnlyTheDnsChallenge() {
        val caa = Planner.plan(cfg, listOf(TRAEFIK)).dns.filter { it.type == "CAA" }
        assertEquals(listOf("issue", "issuewild"), caa.map { it.tag })
        assertTrue(caa.all { it.value == "letsencrypt.org; validationmethods=dns-01" && it.name == "test" && it.fqdn == "test.example.net" })
        val off = cfg.copy(acme = cfg.acme.copy(caa = false))
        assertTrue(Planner.plan(off, listOf(TRAEFIK)).dns.none { it.type == "CAA" })
    }

    private companion object {
        /** Issued by a local test server for this test. */
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
