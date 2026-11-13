package homegap

import homegap.acme.CertNames
import homegap.acme.CertOrderStore
import homegap.acme.CertProbes
import homegap.acme.CertificateStatus
import homegap.acme.NodeCertificate
import homegap.acme.OrderCause
import homegap.acme.X509
import homegap.api.CertificatesDto
import homegap.api.Health
import homegap.api.NodeStatusDto
import homegap.api.StatusSnapshot
import homegap.api.TunnelStatusDto
import homegap.config.ClusterConfig
import homegap.model.Planner
import homegap.reconcile.attentionItems
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `certificates` part of the status: its facts, its states, and what it never holds. */
class CertificatesStatusTest {
    private val cfg = testConfig().copy(cluster = ClusterConfig(image = "example/cluster:7"))
    private val wiki = container("wiki", "homegap.expose" to "wiki.test.example.net", "homegap.port" to "80")
    private val plan = Planner.plan(cfg, listOf(TRAEFIK, wiki))
    private val now = X509.epochSeconds(2026, 12, 1, 0, 0, 0)

    @Test
    fun theReaderGivesThePublicFactsOfACertificate() {
        val info = assertNotNull(X509.info(PEM))
        assertEquals("R99 · Test Issuer", info.issuer)
        assertEquals("EC P-256", info.keyType)
        assertEquals("52:32:30:B5:B6:B5:C4:2E:D0:6C:7F:1C:EA:26:A2:E4:D2:80:C5:AD", info.serial)
        assertEquals("11:E7:CC:7C:AE:35:B8:80:CA:ED:D2:B1:46:25:F0:94:A9:F1:BE:20:25:8D:5B:9D:EB:ED:24:82:04:2E:8B:93", info.fingerprint)
        assertEquals(X509.epochSeconds(2027, 2, 11, 4, 29, 46), info.validity.notAfter)
        assertEquals("11e7cc", NodeCertificate.versionOf(info.fingerprint))
    }

    @Test
    fun eachNameHasTheReasonThatPutsItOnTheCertificate() {
        val entries = CertNames.entries(cfg, plan).associateBy { it.name }
        assertEquals("management", entries.getValue("pangolin-na1.test.example.net").reason)
        assertEquals("na1", entries.getValue("pangolin-na1.test.example.net").subject)
        assertEquals("client", entries.getValue(cfg.clientHost!!).reason)
        assertEquals("service", entries.getValue("wiki.test.example.net").reason)
        assertEquals(CertNames.of(cfg, plan), CertNames.entries(cfg, plan).map { it.name })
        assertTrue("test.example.net" in CertNames.passthrough(plan), "the passthrough front door: ${CertNames.passthrough(plan)}")
    }

    @Test
    fun aFailedOrderHasAShortCauseFromAFixedSet() {
        assertEquals(OrderCause.DNS_TIMEOUT, OrderCause.of("lego exit 1: time limit exceeded: last error: NS ns1.bunny.net did not return the expected TXT record"))
        assertEquals(OrderCause.RATE_LIMIT, OrderCause.of("urn:ietf:params:acme:error:rateLimited: too many failed authorizations recently"))
        assertEquals(OrderCause.CAA, OrderCause.of("CAA record for test.example.net prevents issuance"))
        assertEquals(OrderCause.BUNNY_KEY, OrderCause.of("the Bunny API key \$HOMEGAP_TEST_BUNNY is not set"))
        assertEquals(OrderCause.ACME_UNREACHABLE, OrderCause.of("get directory at 'https://acme-v02.api.letsencrypt.org/directory': dial tcp: i/o timeout"))
        assertEquals(OrderCause.TOO_MANY_NAMES, OrderCause.of("112 names do not fit on one certificate (the limit is 100)"))
        assertEquals(OrderCause.OTHER, OrderCause.of("something else"))
        val redacted = OrderCause.redact("token Xh1pWm4RtvQ2s8bKzLaPoNcYdEfGhIjKlMnOpQrStU9 at https://acme/acct/12")
        assertFalse("Xh1pWm4RtvQ2s8bKzLaPoNcYdEfGhIjKlMnOpQrStU9" in redacted)
        assertTrue("https://acme/acct/12" in redacted)
    }

    @Test
    fun aProbeTellsTheDefaultCertificateAMismatchAndTheDaysLeft() {
        val out = listOf(
            "X a.example.net ${now + 40 * 86_400}|CN=a.example.net|C=US, O=Let's Encrypt, CN=R11|a.example.net,",
            "X b.example.net ${now + 40 * 86_400}|CN=TRAEFIK DEFAULT CERT|CN=TRAEFIK DEFAULT CERT|",
            "X c.example.net none",
            "X d.example.net ${now + 10 * 86_400}|CN=other.example.net|CN=R11|other.example.net,",
            "X *.apps.example.net ${now + 5 * 86_400}|CN=x|CN=R11|*.apps.example.net,",
            "I 1a2b3c4d5e6f7a8b 1794500000",
        )
        val results = CertProbes.parse(out)
        assertEquals("ok", CertProbes.state("a.example.net", results.getValue("a.example.net"), now))
        assertEquals("default", CertProbes.state("b.example.net", results.getValue("b.example.net"), now))
        assertEquals("none", CertProbes.state("c.example.net", results.getValue("c.example.net"), now))
        assertEquals("mismatch", CertProbes.state("d.example.net", results.getValue("d.example.net"), now))
        assertEquals("expiring", CertProbes.state("*.apps.example.net", results.getValue("*.apps.example.net"), now))
        assertEquals("1a2b3c4d5e6f7a8b" to 1794500000L, CertProbes.installed(out))
        assertEquals(null to null, CertProbes.installed(listOf("I  0")))
        assertTrue("homegap-probe.apps.example.net" in CertProbes.script(listOf("*.apps.example.net"), "/opt/x/tls.yml"))
    }

    @Test
    fun deliveryTellsCurrentBehindAndANewNodeThatWaits() {
        val names = CertNames.of(cfg, plan).filter { "na1" !in it }
        val status = build(
            meta = NodeCertificate.Meta(names, cfg.acme.directoryUrl(), "homegap-1", now - 10 * 86_400, now + 80 * 86_400),
            history = listOf(
                CertOrderStore.Record("b", "order", 2, "the names changed", names, ok = true, version = "11e7cc", notBefore = now - 10 * 86_400, hash = "current"),
                CertOrderStore.Record("a", "order", 1, "first", names - "wiki.test.example.net", ok = true, version = "0a0a0a", notBefore = now - 70 * 86_400, hash = "old"),
            ),
            probes = mapOf(
                "na1" to probes(installed = null, names = CertNames.of(cfg, plan), default = setOf("pangolin-na1.test.example.net")),
                "as1" to probes(installed = "old", names = CertNames.of(cfg, plan)),
            ),
        )
        val na1 = status.delivery.first { it.node == "na1" }
        val as1 = status.delivery.first { it.node == "as1" }
        assertEquals("waiting", na1.state)
        assertEquals("default", na1.management?.state)
        assertEquals("behind", as1.state)
        assertEquals("0a0a0a", as1.installed)
        assertEquals(listOf("wiki.test.example.net"), as1.missing)
        assertEquals(listOf("pangolin-na1.test.example.net"), status.pendingAdd)
        assertEquals("homelab", status.probes.first { it.name == "test.example.net" }.kind)
        assertEquals("11e7cc", status.issued?.version)
    }

    @Test
    fun theStatusNeverHoldsKeyMaterialOrAChallengeValue() {
        val status = build(
            meta = NodeCertificate.Meta(CertNames.of(cfg, plan), cfg.acme.directoryUrl(), "homegap-1", now - 86_400, now + 89 * 86_400),
            history = listOf(CertOrderStore.Record("a", "order", 1, "first", CertNames.of(cfg, plan), ok = true, hash = "fnv-of-cert-and-key")),
            probes = mapOf("na1" to probes(installed = "fnv-of-cert-and-key", names = CertNames.of(cfg, plan))),
        )
        val json = Json.encodeToString(CertificatesDto.serializer(), status)
        for (secret in listOf("BEGIN", "PRIVATE", "fnv-of-cert-and-key", "MIIB", "_acme-challenge")) {
            assertFalse(secret in json, "the status holds $secret")
        }
        assertTrue(status.issued!!.fingerprint in json, "the certificate's own fingerprint is public")
    }

    @Test
    fun aFailedOrderIsOneAttentionItemAndTheNodeItemsFoldUnderIt() {
        val certs = build(
            meta = null,
            history = emptyList(),
            probes = emptyMap(),
            order = CertOrderStore.State(state = CertOrderStore.FAILED, cause = "dns-timeout", causeText = OrderCause.DNS_TIMEOUT.text, retryAt = 1),
        )
        val snapshot = StatusSnapshot(
            generatedAt = now * 1000,
            version = "t",
            dryRun = false,
            project = "p",
            domain = cfg.domain,
            paused = false,
            nodes = listOf(NodeStatusDto("na1", "NA", "", "", "pangolin-na1.test.example.net", Health.DEGRADED, true, waitingForCertificate = true)),
            tunnels = listOf(TunnelStatusDto(nodeId = "na1", siteNiceId = "s", endpoint = "e", up = false, processRunning = false, siteOnline = false)),
            certificates = certs,
        )
        val items = attentionItems(snapshot, emptyMap())
        val order = items.first { it.id == "certificates:order" }
        assertEquals(Health.DOWN, order.severity)
        assertEquals("certificates", order.route)
        assertEquals("certificates:order", items.first { it.id == "node:na1:waiting" }.parent)
        assertEquals("certificates:order", items.first { it.id == "tunnel:na1" }.parent)
        assertNull(items.firstOrNull { it.id == "node:na1:down" })
    }

    private fun probes(installed: String?, names: List<String>, default: Set<String> = emptySet()) = CertificateStatus.NodeProbes(
        at = now * 1000,
        installed = installed,
        installedAt = installed?.let { now - 86_400 },
        results = names.associateWith { name ->
            if (name in default) {
                CertProbes.Result(served = true, notAfter = now + 365 * 86_400, subject = "CN=TRAEFIK DEFAULT CERT")
            } else {
                CertProbes.Result(served = true, notAfter = now + 80 * 86_400, subject = "CN=$name", issuer = "CN=R11", sans = listOf(name))
            }
        },
    )

    private fun build(
        meta: NodeCertificate.Meta?,
        history: List<CertOrderStore.Record>,
        probes: Map<String, CertificateStatus.NodeProbes>,
        order: CertOrderStore.State = CertOrderStore.State(),
    ) = CertificateStatus.build(
        CertificateStatus.Inputs(
            config = cfg,
            desired = plan,
            meta = meta,
            info = meta?.let { X509.info(PEM) },
            currentHash = meta?.let { history.firstOrNull()?.hash },
            renewAt = meta?.let { it.notAfter - (it.notAfter - it.notBefore) / 3 },
            order = order,
            running = false,
            due = null,
            history = history,
            contact = "admin@test.example.net",
            probes = probes,
            reachable = probes.mapValues { true },
            caaLive = listOf("0 issue \"letsencrypt.org; validationmethods=dns-01\""),
            nowMs = now * 1000,
        ),
    )

    companion object {
        private val PEM = """
            -----BEGIN CERTIFICATE-----
            MIIByzCCAXKgAwIBAgIUUjIwtba1xC7QbH8c6iai5NKAxa0wCgYIKoZIzj0EAwIw
            JDEUMBIGA1UECgwLVGVzdCBJc3N1ZXIxDDAKBgNVBAMMA1I5OTAeFw0yNjExMTMw
            NDI5NDZaFw0yNzAyMTEwNDI5NDZaMCQxFDASBgNVBAoMC1Rlc3QgSXNzdWVyMQww
            CgYDVQQDDANSOTkwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAR1fVTZaGxPWtL1
            Ghlcbudc+B224IU+mxieYwBZxySBBL/OIs6kabs5WG9hi/lAanD9Wc9Id7TGBeqB
            mJ25SSJoo4GBMH8wHQYDVR0OBBYEFCp8V4o6iGvmmKXKEp5uZ446ic6UMB8GA1Ud
            IwQYMBaAFCp8V4o6iGvmmKXKEp5uZ446ic6UMA8GA1UdEwEB/wQFMAMBAf8wLAYD
            VR0RBCUwI4INYS5leGFtcGxlLm5ldIISKi5hcHBzLmV4YW1wbGUubmV0MAoGCCqG
            SM49BAMCA0cAMEQCIGLeRCJb5cHn5Mc7t8GlzRuNKae4DYqwagWYLtH8WOdqAiAM
            lvf4Pu51IX098TBvF0FxxEDxMB4kR6D06128v3AhYQ==
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
