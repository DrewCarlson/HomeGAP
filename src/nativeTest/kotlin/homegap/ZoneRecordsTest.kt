package homegap

import homegap.acme.DnsChallenge
import homegap.api.ZoneRecordKind
import homegap.api.ZoneRecordRequest
import homegap.clients.BunnyRecord
import homegap.config.ConfigLoader
import homegap.model.DesiredDnsRecord
import homegap.model.Planner
import homegap.zone.ZoneRecordRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneRecordsTest {
    private fun request(type: String, name: String, value: String, ttl: Int = 300) =
        ZoneRecordRequest(type = type, name = name, value = value, ttlSeconds = ttl)

    private fun record(type: String, name: String, comment: String? = null) =
        BunnyRecord(id = 7, type = BunnyRecord.typeCode(type), name = name, value = "203.0.113.10", comment = comment)

    @Test
    fun theCommentOfARecordGivesItsOwner() {
        val managed = record("A", "", "${DesiredDnsRecord.OWNER_TAG} root node=na1")
        assertEquals(ZoneRecordKind.MANAGED, ZoneRecordRules.kind(managed))
        assertEquals(ZoneRecordKind.CHALLENGE, ZoneRecordRules.kind(record("TXT", "_acme-challenge", DnsChallenge.COMMENT)))
        assertEquals(ZoneRecordKind.OWN, ZoneRecordRules.kind(record("TXT", "_verify")))
        assertEquals(ZoneRecordKind.OWN, ZoneRecordRules.kind(record("A", "www", "a note of the user")))

        val shown = ZoneRecordRules.toDto(managed)
        assertEquals("@", shown.name)
        assertEquals("front door", shown.purpose)
        assertEquals("na1", shown.nodeId)
        val caa = record("CAA", "", "${DesiredDnsRecord.OWNER_TAG} caa node=${Planner.CAA_OWNER}")
        assertEquals("CAA", ZoneRecordRules.toDto(caa).purpose)
        assertNull(ZoneRecordRules.toDto(caa).nodeId, "the CAA records belong to no node")
        assertNull(ZoneRecordRules.toDto(record("A", "www")).purpose)
    }

    @Test
    fun aRequestMustBeAValidRecord() {
        assertNull(ZoneRecordRules.problem(request("TXT", "_verify.app", "google-site-verification=abc")))
        assertNull(ZoneRecordRules.problem(request("A", "@", "198.51.100.20")))
        assertNull(ZoneRecordRules.problem(request("A", "*.dev", "198.51.100.20")))
        assertNull(ZoneRecordRules.problem(request("AAAA", "www", "2001:db8::20")))
        assertNull(ZoneRecordRules.problem(request("CNAME", "www", "example.org.")))
        assertNotNull(ZoneRecordRules.problem(request("A", "www", "198.51.100.300")))
        assertNotNull(ZoneRecordRules.problem(request("AAAA", "www", "198.51.100.20")))
        assertNotNull(ZoneRecordRules.problem(request("CNAME", "@", "example.org")), "no CNAME at the root")
        assertNotNull(ZoneRecordRules.problem(request("A", "bad name", "198.51.100.20")))
        assertNotNull(ZoneRecordRules.problem(request("TXT", "x", " ")))
        assertNotNull(ZoneRecordRules.problem(request("TXT", "x", "v", ttl = 5)))
        assertNotNull(ZoneRecordRules.problem(request("SRV", "x", "v")), "a type that the dashboard does not edit")

        val mail = request("MX", "@", "mail.example.org")
        assertNotNull(ZoneRecordRules.problem(mail), "an MX record needs a priority")
        assertNull(ZoneRecordRules.problem(mail.copy(priority = 10)))
        val authority = request("CAA", "@", "letsencrypt.org")
        assertNotNull(ZoneRecordRules.problem(authority), "a CAA record needs a tag")
        assertNull(ZoneRecordRules.problem(authority.copy(tag = "issue", flags = 0)))
        assertNotNull(ZoneRecordRules.problem(authority.copy(tag = "issue", flags = 3)))
    }

    @Test
    fun aRecordThatTheDnsPassDeletesIsRefused() {
        val managed = setOf("app" to "A", "app" to "AAAA", "" to "CAA")
        val collision = ZoneRecordRules.collision(request("A", "APP", "198.51.100.20"), managed, "app.example.net")
        assertEquals("HomeGAP manages A records for app.example.net.", collision)
        val shadow = ZoneRecordRules.collision(request("CNAME", "app", "example.org"), managed, "app.example.net")
        assertTrue(shadow!!.endsWith("so a CNAME cannot go there."))
        assertNull(ZoneRecordRules.collision(request("TXT", "app", "v=spf1 -all"), managed, "app.example.net"))
        assertNull(ZoneRecordRules.collision(request("A", "@", "198.51.100.20"), managed, "example.net"))
        assertNotNull(ZoneRecordRules.collision(request("CAA", "@", "x").copy(tag = "issue"), managed, "example.net"))
    }

    @Test
    fun anEditKeepsTheFieldsThatTheDashboardDoesNotShow() {
        val existing = record("TXT", "_verify", "a note of the user").copy(disabled = true, weight = 50)
        val changed = ZoneRecordRules.toBunny(request("TXT", "_Verify", " new value ", ttl = 3600), existing)
        assertEquals(existing.copy(ttl = 3600, value = "new value"), changed)

        val mail = ZoneRecordRules.toBunny(request("MX", "@", "mail.example.org").copy(priority = 10))
        assertEquals("", mail.name)
        assertEquals(10, mail.priority)
        assertNull(mail.comment, "a record of the user has no ownership tag")
        val authority = ZoneRecordRules.toBunny(request("CAA", "@", "letsencrypt.org").copy(tag = "issue"))
        assertEquals(0, authority.flags)
        assertEquals("issue", authority.tag)
    }

    @Test
    fun anExtraDomainCanKeepItsOwnCaaRecordsAndItsOwnRoot() {
        val extra = "$TEST_CONFIG\nextra_domains:\n  - domain: other.example.org\n    caa: false\n"
        val config = ConfigLoader.parse(extra)
        assertEquals(listOf("test.example.net"), config.caaDomains)
        val labels = TRAEFIK.labels + mapOf("homegap.services.app.expose" to "app.other.example.org")
        val plan = Planner.plan(config, listOf(TRAEFIK.copy(labels = labels)))
        val records = plan.dns.filter { it.fqdn.endsWith("other.example.org") }
        assertFalse(records.any { it.type == "CAA" }, "no CAA records with caa: false")
        assertFalse(records.any { it.fqdn == "other.example.org" }, "no record at the root without a service there")
        assertTrue(plan.dns.any { it.type == "CAA" && it.fqdn == "test.example.net" })

        val both = ConfigLoader.parse(extra.replace("    caa: false\n", ""))
        assertEquals(listOf("test.example.net", "other.example.org"), both.caaDomains)
        val off = ConfigLoader.parse("$TEST_CONFIG\nacme:\n  caa: false\n")
        assertTrue(off.caaDomains.isEmpty())
    }
}
