package homegap.acme

import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.model.DesiredDnsRecord

/**
 * Writes the TXT records of the DNS challenge. The ACME client asks for each record through
 * [ChallengeServer], so it never holds the DNS key. This class accepts only the challenge names
 * of [names].
 */
class DnsChallenge(
    private val bunny: BunnyClient,
    /** The id of each zone that has a name of the order, by the name of the zone. */
    private val zones: Map<String, Long>,
    names: List<String>,
    private val onPresent: () -> Unit = {},
    private val onCleanup: () -> Unit = {},
) {
    private val allowed = names.map { "$LABEL.${it.removePrefix("*.")}".lowercase() }.toSet()
    private val txt = BunnyRecord.typeCode("TXT")

    /** The id of the zone of the challenge name [fqdn], and the name in that zone. */
    private fun place(fqdn: String): Pair<Long, String> {
        val name = fqdn.trimEnd('.').lowercase()
        require(name in allowed) { "$name is not a challenge name of this order" }
        // The longest zone wins, for a zone that is inside a different zone.
        val zone = zones.keys.filter { name.endsWith(".$it") }.maxByOrNull { it.length }
        requireNotNull(zone) { "$name is outside the ${zones.keys.joinToString(prefix = "zone ")}" }
        return zones.getValue(zone) to name.removeSuffix(".$zone")
    }

    suspend fun present(fqdn: String, value: String) {
        require(VALUE.matches(value)) { "not a challenge value" }
        val (zoneId, name) = place(fqdn)
        val record = BunnyRecord(type = txt, ttl = TTL, value = value, name = name, comment = COMMENT)
        bunny.addRecord(zoneId, record)
        onPresent()
    }

    suspend fun cleanup(fqdn: String, value: String) {
        val (zoneId, name) = place(fqdn)
        owned(zoneId)
            .filter { it.name.equals(name, ignoreCase = true) && it.value == value }
            .forEach { bunny.deleteRecord(zoneId, it.id) }
        onCleanup()
    }

    /** Removes each challenge record that an earlier order did not remove. */
    suspend fun sweep() {
        for (zoneId in zones.values) {
            owned(zoneId).forEach { bunny.deleteRecord(zoneId, it.id) }
        }
    }

    private suspend fun owned(zoneId: Long) =
        bunny.getZone(zoneId).records.filter { it.type == txt && it.comment == COMMENT }

    companion object {
        const val LABEL = "_acme-challenge"
        const val COMMENT = "${DesiredDnsRecord.OWNER_TAG} acme-challenge"
        private const val TTL = 60
        private val VALUE = Regex("^[A-Za-z0-9_-]{20,100}$")
    }
}
