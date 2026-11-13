package homegap.reconcile

import homegap.clients.DnsWire
import homegap.model.DesiredDnsRecord
import homegap.platform.nowMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** One nameserver's answer for a name: its addresses, or null when it gave none in time. */
fun interface NameserverProbe {
    suspend fun answers(nameserver: String, name: String, type: Int): List<String>?
}

/**
 * Checks that each of the zone's nameservers serves the records Bunny's API reports, by asking
 * them directly. Bunny can take a long time to copy a new record to every nameserver. Until then,
 * some lookups get an older answer (for a new name, the wildcard's, which can be any node).
 *
 * The check covers only plain records: a geo or latency-steered record set answers differently by
 * location, so no single answer is "the" right one. A record that matched gets its next check
 * after [recheck]. A record that did not match gets a check in every pass. The controller keeps
 * one instance for all passes.
 */
class NameserverCheck(
    private val probe: NameserverProbe,
    private val recheck: Duration = 10.minutes,
) {
    private val matchedAt = mutableMapOf<String, Long>()
    private val lastProblem = mutableMapOf<String, String>()

    /**
     * [problems] gives the reason for each record that not every nameserver serves. A record
     * without an entry is served, or its state is unknown. [changed] has the records whose problem
     * is new, different or gone since the last call, for the log.
     */
    data class Result(val problems: Map<DesiredDnsRecord, String>, val changed: Set<DesiredDnsRecord>)

    suspend fun check(nameservers: List<String>, records: List<DesiredDnsRecord>): Result {
        val now = nowMillis()
        val due = records.filter { isDue(it, now) }
        val checked = coroutineScope {
            due.map { record -> async { record to problem(nameservers, record) } }.awaitAll()
        }
        val problems = mutableMapOf<DesiredDnsRecord, String>()
        val changed = mutableSetOf<DesiredDnsRecord>()
        for ((record, problem) in checked) {
            val key = key(record)
            if (problem == null) {
                matchedAt[key] = now
                if (lastProblem.remove(key) != null) changed += record
            } else {
                matchedAt.remove(key)
                if (lastProblem.put(key, problem) != problem) changed += record
                problems[record] = problem
            }
        }
        // Keep the cache to the records still desired.
        val live = records.map(::key).toSet()
        matchedAt.keys.retainAll(live)
        lastProblem.keys.retainAll(live)
        return Result(problems, changed)
    }

    private fun isDue(record: DesiredDnsRecord, now: Long): Boolean {
        if (record.geo || DnsWire.typeCode(record.type) == null) return false
        val matched = matchedAt[key(record)] ?: return true
        return now - matched >= recheck.inWholeMilliseconds
    }

    /** The nameserver answers that differ from the value of [record], or null when all match (or none answered). */
    private suspend fun problem(nameservers: List<String>, record: DesiredDnsRecord): String? = coroutineScope {
        val type = DnsWire.typeCode(record.type) ?: return@coroutineScope null
        val wanted = DnsWire.canonical(record.value)
        val answers = nameservers
            .map { nameserver ->
                async { nameserver to probe.answers(nameserver, record.fqdn, type)?.map(DnsWire::canonical) }
            }
            .awaitAll()
        // Only a disagreement counts: one nameserver serving the record shows the zone is served
        // there, so another without it is behind. When none has it (the zone is not delegated to
        // them, as in tests), there is nothing to compare. Unreachable servers tell us nothing.
        if (answers.none { (_, answer) -> answer != null && wanted in answer }) return@coroutineScope null
        val wrong = answers.mapNotNull { (nameserver, answer) ->
            when {
                answer == null || wanted in answer -> null
                answer.isEmpty() -> "$nameserver has no ${record.type} record yet"
                else -> "$nameserver still answers ${answer.joinToString()}"
            }
        }
        wrong.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun key(record: DesiredDnsRecord) = "${record.fqdn}|${record.type}|${record.value}"
}
