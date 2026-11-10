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
 * them directly. Bunny can take a long time to copy a new record to every nameserver; until it
 * has, some lookups get an older answer (for a new name, the wildcard's, which can be any node).
 *
 * Only plain records are checked: a geo or latency-steered record set answers differently by
 * location, so no single answer is "the" right one. Records that matched are checked again after
 * [recheck]; ones that did not, every pass. Lives across passes (held by the controller).
 */
class NameserverCheck(
    private val probe: NameserverProbe,
    private val recheck: Duration = 10.minutes,
) {
    private val matchedAt = mutableMapOf<String, Long>()
    private val lastProblem = mutableMapOf<String, String>()

    /**
     * Why each record is not served by every nameserver ([problems]; absent = it is, or unknown),
     * and which of those problems are new or changed since the last call ([changed], for the log).
     */
    data class Result(val problems: Map<DesiredDnsRecord, String>, val changed: Set<DesiredDnsRecord>)

    suspend fun check(nameservers: List<String>, records: List<DesiredDnsRecord>): Result {
        val now = nowMillis()
        val due = records.filter { r ->
            !r.geo && DnsWire.typeCode(r.type) != null &&
                (matchedAt[key(r)]?.let { now - it >= recheck.inWholeMilliseconds } ?: true)
        }
        val checked = coroutineScope {
            due.map { r -> async { r to problem(nameservers, r) } }.awaitAll()
        }
        val out = mutableMapOf<DesiredDnsRecord, String>()
        val changed = mutableSetOf<DesiredDnsRecord>()
        for ((r, problem) in checked) {
            if (problem == null) {
                matchedAt[key(r)] = now
                if (lastProblem.remove(key(r)) != null) changed += r
            } else {
                matchedAt.remove(key(r))
                if (lastProblem.put(key(r), problem) != problem) changed += r
                out[r] = problem
            }
        }
        // Keep the cache to the records still desired.
        val live = records.map(::key).toSet()
        matchedAt.keys.retainAll(live)
        lastProblem.keys.retainAll(live)
        return Result(out, changed)
    }

    /** The nameserver answers that differ from [r]'s value, or null when all match (or none answered). */
    private suspend fun problem(nameservers: List<String>, r: DesiredDnsRecord): String? = coroutineScope {
        val type = DnsWire.typeCode(r.type) ?: return@coroutineScope null
        val want = DnsWire.canonical(r.value)
        val answers = nameservers.map { ns -> async { ns to probe.answers(ns, r.fqdn, type)?.map(DnsWire::canonical) } }.awaitAll()
        // Only a disagreement counts: one nameserver serving the record shows the zone is served
        // there, so another without it is behind. When none has it (the zone is not delegated to
        // them, as in tests), there is nothing to compare. Unreachable servers tell us nothing.
        if (answers.none { (_, have) -> have != null && want in have }) return@coroutineScope null
        val wrong = answers.mapNotNull { (ns, have) ->
            when {
                have == null || want in have -> null
                have.isEmpty() -> "$ns has no ${r.type} record yet"
                else -> "$ns still answers ${have.joinToString()}"
            }
        }
        wrong.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun key(r: DesiredDnsRecord) = "${r.fqdn}|${r.type}|${r.value}"
}
