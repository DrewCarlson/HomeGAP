package homegap

import homegap.acme.CertNames
import homegap.acme.CertOrderStore
import homegap.acme.CertificateStatus
import homegap.acme.DnsChallenge
import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.config.ConfigLoader
import homegap.model.Planner
import homegap.model.ResourceMode
import homegap.provision.NodeSecrets
import homegap.provision.NodeTemplates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtraDomainsTest {
    private val extraDomains = """
        extra_domains:
          - domain: other.example.org
          - domain: apps.example.io
            zone: example.io
    """.trimIndent()
    private val config = ConfigLoader.parse("$TEST_CONFIG\n$extraDomains\n")

    private val otherDoor = container(
        "other-traefik",
        "homegap.expose" to "other.example.org",
        "homegap.wildcard" to "true",
        "homegap.protocol" to "https",
        "homegap.tls" to "terminate",
    )
    private val appsDoor = container(
        "apps-traefik",
        "homegap.expose" to "apps.example.io",
        "homegap.wildcard" to "true",
        "homegap.protocol" to "https",
        "homegap.tls" to "passthrough",
    )

    @Test
    fun eachExtraDomainHasItsZone() {
        assertEquals(listOf("test.example.net", "other.example.org", "apps.example.io"), config.serviceDomains)
        assertEquals(listOf("example.net", "other.example.org", "example.io"), config.zones)
        assertEquals("example.net", config.zoneOf("wiki.test.example.net"))
        assertEquals("other.example.org", config.zoneOf("*.other.example.org"))
        assertEquals("example.io", config.zoneOf("shop.apps.example.io"))
        assertEquals("", config.relativeName("other.example.org"))
        assertEquals("*", config.relativeName("*.other.example.org"))
        assertEquals("shop.apps", config.relativeName("shop.apps.example.io"))
    }

    @Test
    fun anExtraDomainThatOverlapsOrHasAWrongZoneIsRefused() {
        val inside = "$TEST_CONFIG\nextra_domains:\n  - domain: apps.test.example.net\n"
        val twice = "$TEST_CONFIG\nextra_domains:\n  - domain: other.example.org\n  - domain: other.example.org\n"
        val wrongZone = "$TEST_CONFIG\nextra_domains:\n  - domain: other.example.org\n    zone: example.io\n"

        val overlap = assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(inside) }
        assertTrue("overlaps the domain 'test.example.net'" in overlap.message.orEmpty(), overlap.message)
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(twice) }
        assertFailsWith<IllegalArgumentException> { ConfigLoader.parse(wrongZone) }
    }

    @Test
    fun aServiceCanUseAnExtraDomainButNotAnUnknownOne() {
        val shop = container("shop", "homegap.expose" to "shop.other.example.org", "homegap.port" to "8080")
        val stray = container("stray", "homegap.expose" to "app.unknown.example", "homegap.port" to "8080")
        val plan = Planner.plan(config, listOf(TRAEFIK, shop, stray))

        assertTrue(plan.services.any { it.hostname == "shop.other.example.org" })
        assertFalse(plan.services.any { it.hostname == "app.unknown.example" })
        val refusal = plan.labelErrors.single { it.container == "stray" }.message
        val managed = "'test.example.net', 'other.example.org', 'apps.example.io'"
        assertEquals("homegap.expose 'app.unknown.example' is outside the managed domains $managed", refusal)

        val node = plan.nodes.getValue("na1")
        val resource = node.publicResources.single { it.fullDomain == "shop.other.example.org" }
        assertEquals(ResourceMode.HTTP, resource.mode)
        val records = plan.dns.filter { it.fqdn == "shop.other.example.org" }
        assertEquals(setOf("shop"), records.map { it.name }.toSet(), "the name is relative to the zone of its domain")
        assertTrue("shop.other.example.org" in CertNames.of(config, plan))
    }

    @Test
    fun anExtraDomainHasItsOwnFrontDoor() {
        val plan = Planner.plan(config, listOf(TRAEFIK, otherDoor, appsDoor))

        assertEquals("traefik", plan.frontDoor?.containerName)
        assertEquals(listOf("other-traefik", "apps-traefik"), plan.extraFrontDoors.map { it.containerName })
        assertTrue(plan.labelErrors.isEmpty(), plan.labelErrors.toString())

        val node = plan.nodes.getValue("na1")
        val terminated = node.publicResources.filter { it.mode == ResourceMode.HTTP }.mapNotNull { it.fullDomain }
        assertTrue("other.example.org" in terminated && "*.other.example.org" in terminated, terminated.toString())

        val appsRoute = node.sniRoutes.single { it.wildcardOf == "apps.example.io" }
        assertEquals(listOf("apps.example.io"), appsRoute.exactHosts, "an extra front door carries no page of the node")
        assertTrue(appsRoute.resourceNiceId.startsWith("${Planner.FRONTDOOR_TCP}-"), appsRoute.resourceNiceId)
        assertTrue(node.publicResources.any { it.niceId == appsRoute.resourceNiceId && it.mode == ResourceMode.TCP })
        assertEquals(listOf("apps.example.io", "test.example.net"), node.wildcardRedirectDomains)

        val mainRoute = node.sniRoutes.single { it.wildcardOf == "test.example.net" }
        assertEquals(Planner.FRONTDOOR_TCP, mainRoute.resourceNiceId, "the front door of the platform keeps its id")
    }

    @Test
    fun theRecordsOfAnExtraFrontDoorAreInItsZone() {
        val plan = Planner.plan(config, listOf(TRAEFIK, otherDoor, appsDoor))
        fun names(fqdn: String) = plan.dns.filter { it.fqdn == fqdn && it.type == "A" }.map { it.name to it.purpose }

        assertEquals(listOf("" to "root", "" to "root"), names("other.example.org"))
        assertEquals(listOf("*" to "wildcard", "*" to "wildcard"), names("*.other.example.org"))
        assertEquals(listOf("apps" to "root", "apps" to "root"), names("apps.example.io"))
        assertEquals(listOf("*.apps" to "wildcard", "*.apps" to "wildcard"), names("*.apps.example.io"))

        val caaAt = plan.dns.filter { it.type == "CAA" }.map { it.fqdn }.toSet()
        assertEquals(setOf("test.example.net", "other.example.org", "apps.example.io"), caaAt)
        assertNull(plan.dns.firstOrNull { it.fqdn.startsWith("pangolin-") && !it.fqdn.endsWith(".test.example.net") })

        val onCertificate = CertNames.of(config, plan)
        assertTrue("*.other.example.org" in onCertificate && "other.example.org" in onCertificate)
        assertFalse("apps.example.io" in onCertificate, "the node does not end TLS for a passthrough front door")
    }

    @Test
    fun aPassthroughServiceUnderATerminatedExtraFrontDoorIsRefused() {
        val covered = container(
            "vault",
            "homegap.expose" to "vault.other.example.org",
            "homegap.protocol" to "https",
            "homegap.tls" to "passthrough",
        )
        val plan = Planner.plan(config, listOf(TRAEFIK, otherDoor, covered))

        assertFalse(plan.services.any { it.hostname == "vault.other.example.org" })
        assertTrue(plan.labelErrors.single().message.contains("*.other.example.org"))
    }

    @Test
    fun pangolinKnowsEachDomain() {
        val secrets = NodeSecrets("t".repeat(32), "server", "admin@test.example.net", "pw", "bk")
        val yml = NodeTemplates.pangolinConfig(config, config.nodes.first(), secrets, slot = 0)

        for (domain in config.serviceDomains) {
            assertTrue("base_domain: \"$domain\"" in yml || "base_domain: $domain" in yml, "missing $domain in:\n$yml")
        }
        val plain = NodeTemplates.pangolinConfig(testConfig(), config.nodes.first(), secrets, slot = 0)
        assertEquals(1, Regex("base_domain").findAll(plain).count(), "no extra domain, no extra entry")
    }

    @Test
    fun theCaaRecordsOfEachDomainAreCheckedAndNamed() {
        val wanted = listOf("issue", "issuewild").map { "0 $it \"letsencrypt.org; validationmethods=dns-01\"" }
        val stray = "0 issue \"ca.example\""
        val inputs = CertificateStatus.Inputs(
            config = config,
            desired = Planner.plan(config, listOf(TRAEFIK)),
            meta = null,
            info = null,
            currentHash = null,
            renewAt = null,
            order = CertOrderStore.State(),
            running = false,
            due = null,
            history = emptyList(),
            contact = "admin@test.example.net",
            probes = emptyMap(),
            reachable = emptyMap(),
            caaLive = mapOf(
                "test.example.net" to wanted,
                "other.example.org" to listOf(wanted.first(), stray),
            ),
            nowMs = 0,
        )
        val caa = CertificateStatus.build(inputs).caa

        assertFalse(caa.known, "the zone of apps.example.io is not read yet")
        assertEquals(6, caa.desired.size)
        assertEquals(listOf("other.example.org ${wanted.last()}"), caa.missing)
        assertEquals(listOf("other.example.org $stray"), caa.foreign)
    }

    @Test
    fun aChallengeRecordGoesToTheZoneOfItsName() = runTest {
        val added = mutableListOf<Pair<Long, BunnyRecord>>()
        val bunny = object : BunnyClient(HttpClient(MockEngine { respond("") }), "https://bunny.invalid", "k") {
            override suspend fun addRecord(zoneId: Long, record: BunnyRecord): BunnyRecord {
                added += zoneId to record
                return record
            }
        }
        val challenge = DnsChallenge(
            bunny = bunny,
            zones = mapOf("example.net" to 1L, "other.example.org" to 2L),
            names = listOf("wiki.test.example.net", "*.other.example.org"),
        )
        val value = "a".repeat(43)

        challenge.present("_acme-challenge.wiki.test.example.net.", value)
        challenge.present("_acme-challenge.other.example.org.", value)

        assertEquals(listOf(1L to "_acme-challenge.wiki.test", 2L to "_acme-challenge"), added.map { it.first to it.second.name })
        assertFailsWith<IllegalArgumentException> { challenge.present("_acme-challenge.elsewhere.example.com.", value) }
    }
}
