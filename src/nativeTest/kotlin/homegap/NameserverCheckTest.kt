package homegap

import homegap.clients.DnsWire
import homegap.model.DesiredDnsRecord
import homegap.model.Planner
import homegap.reconcile.NameserverCheck
import homegap.reconcile.NameserverProbe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NameserverCheckTest {
    /** A response to [query] carrying [answers] (type, rdata), its name as a pointer to the question. */
    private fun response(query: ByteArray, rcode: Int = 0, vararg answers: Pair<Int, ByteArray>): ByteArray {
        val out = query.toMutableList()
        out[2] = 0x84.toByte() // response, authoritative
        out[3] = rcode.toByte()
        out[6] = 0; out[7] = answers.size.toByte()
        for ((type, data) in answers) {
            out += listOf(0xc0, 0x0c, 0, type, 0, 1, 0, 0, 0, 60, 0, data.size).map { it.toByte() }
            out += data.toList()
        }
        return out.toByteArray()
    }

    @Test
    fun queriesAndAnswersRoundTrip() {
        val q = DnsWire.encodeQuery(0x1234, "pangolin-na1.test.example.net", DnsWire.TYPE_A)
        // Header, then 3 + 12 + 4 + 7 + 3 label bytes and the root, type and class.
        assertEquals(12 + "pangolin-na1.test.example.net".length + 2 + 4, q.size)
        assertEquals(0, q[2].toInt(), "authoritative servers are asked without recursion")

        val a = response(q, 0, DnsWire.TYPE_A to byteArrayOf(203.toByte(), 0, 113, 10))
        assertEquals(listOf("203.0.113.10"), DnsWire.parseAnswers(a, 0x1234, DnsWire.TYPE_A))
        assertNull(DnsWire.parseAnswers(a, 0x4321, DnsWire.TYPE_A), "another query's answer")
        assertNull(DnsWire.parseAnswers(q, 0x1234, DnsWire.TYPE_A), "the query itself is not an answer")
        assertEquals(emptyList(), DnsWire.parseAnswers(response(q, 3), 0x1234, DnsWire.TYPE_A), "NXDOMAIN")
        assertNull(DnsWire.parseAnswers(response(q, 2), 0x1234, DnsWire.TYPE_A), "SERVFAIL is no answer")

        val q6 = DnsWire.encodeQuery(7, "pangolin-as1.test.example.net", DnsWire.TYPE_AAAA)
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 0x20 }
        assertEquals(listOf("2001:db8::20"), DnsWire.parseAnswers(response(q6, 0, DnsWire.TYPE_AAAA to v6), 7, DnsWire.TYPE_AAAA))
    }

    @Test
    fun ipv6IsComparedInOneForm() {
        assertEquals("2001:db8::20", DnsWire.canonical("2001:0DB8:0:0:0:0:0:0020"))
        assertEquals("2001:db8:0:1::1", DnsWire.canonical("2001:db8:0:1:0:0:0:1"))
        assertEquals("::1", DnsWire.canonical("::1"))
        assertEquals("203.0.113.10", DnsWire.canonical("203.0.113.10"))
    }

    /** Two nameservers: kiki has every record, coco answers new names with the wildcard. */
    private class Zone : NameserverProbe {
        val known = mutableMapOf("pangolin-na1.test.example.net" to "203.0.113.10")
        var cocoHasNew = false
        var cocoReachable = true
        val asked = mutableListOf<String>()
        override suspend fun answers(nameserver: String, name: String, type: Int): List<String>? {
            asked += "$nameserver $name"
            if (type != DnsWire.TYPE_A) return emptyList()
            if (nameserver == "coco" && !cocoReachable) return null
            if (nameserver == "coco" && !cocoHasNew && name != "pangolin-na1.test.example.net") return listOf("198.51.100.20")
            return listOfNotNull(known[name])
        }
    }

    private fun record(name: String, value: String, geo: Boolean = false) =
        DesiredDnsRecord(name.substringBefore(".example.net"), name, "A", value, "na1", geo, null, null, null, "none", 60, "management")

    @Test
    fun aNameserverStillOnTheWildcardIsReported() = runTest {
        val zone = Zone()
        zone.known["pangolin-us1.test.example.net"] = "192.0.2.30"
        val check = NameserverCheck(zone)
        val old = record("pangolin-na1.test.example.net", "203.0.113.10")
        val new = record("pangolin-us1.test.example.net", "192.0.2.30")

        val first = check.check(listOf("kiki", "coco"), listOf(old, new))
        assertEquals(mapOf(new to "coco still answers 198.51.100.20"), first.problems)
        assertEquals(setOf(new), first.changed)

        // Still waiting: reported again, but not as news for the log.
        val second = check.check(listOf("kiki", "coco"), listOf(old, new))
        assertEquals(setOf(new), second.problems.keys)
        assertTrue(second.changed.isEmpty())

        zone.cocoHasNew = true
        val third = check.check(listOf("kiki", "coco"), listOf(old, new))
        assertTrue(third.problems.isEmpty())
        assertEquals(setOf(new), third.changed, "the fix is logged once")
    }

    @Test
    fun matchesAreRecheckedOnlyAfterAWhileAndUnknownIsNotWrong() = runTest {
        val zone = Zone()
        val check = NameserverCheck(zone, recheck = 10.minutes)
        val old = record("pangolin-na1.test.example.net", "203.0.113.10")
        check.check(listOf("kiki", "coco"), listOf(old))
        zone.asked.clear()
        check.check(listOf("kiki", "coco"), listOf(old))
        assertTrue(zone.asked.isEmpty(), "a record that matched is not asked about again right away")

        // An unreachable nameserver tells us nothing: not reported as out of sync.
        zone.known["pangolin-us1.test.example.net"] = "192.0.2.30"
        zone.cocoReachable = false
        val unknown = NameserverCheck(zone, recheck = 0.seconds).check(listOf("kiki", "coco"), listOf(record("pangolin-us1.test.example.net", "192.0.2.30")))
        assertTrue(unknown.problems.isEmpty())

        // No nameserver has the record (the zone is not served by them, as in a test setup):
        // nothing to compare, so nothing is reported.
        zone.cocoReachable = true
        val unserved = NameserverCheck(zone).check(listOf("kiki", "coco"), listOf(record("pangolin-zz1.test.example.net", "192.0.2.40")))
        assertTrue(unserved.problems.isEmpty())

        // One has it and the other has no record at all yet: that is a nameserver behind too.
        val empty = NameserverProbe { ns, _, _ -> if (ns == "kiki") listOf("192.0.2.30") else emptyList() }
        val behind = NameserverCheck(empty).check(listOf("kiki", "coco"), listOf(record("pangolin-us1.test.example.net", "192.0.2.30")))
        assertEquals(setOf("coco has no A record yet"), behind.problems.values.toSet())

        // Geo-steered sets answer by location, so they are never compared.
        zone.asked.clear()
        NameserverCheck(zone).check(listOf("kiki", "coco"), listOf(record("test.example.net", "203.0.113.10", geo = true)))
        assertTrue(zone.asked.isEmpty())
    }

    @Test
    fun theManagementRecordsAreChecked() = runTest {
        // The names the tunnels connect to are plain records, so the check covers them.
        val plan = Planner.plan(testConfig(), listOf(TRAEFIK))
        val zone = Zone()
        NameserverCheck(zone).check(listOf("kiki"), plan.dns)
        assertTrue(zone.asked.contains("kiki pangolin-na1.test.example.net"))
        assertTrue(zone.asked.none { it.endsWith(" test.example.net") }, "the geo root set is skipped")
    }
}
