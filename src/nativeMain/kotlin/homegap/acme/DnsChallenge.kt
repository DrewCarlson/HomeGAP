package homegap.acme

import homegap.clients.BunnyClient
import homegap.clients.BunnyRecord
import homegap.model.DesiredDnsRecord

/**
 * Writes the TXT records of the DNS challenge. The ACME client asks for each record through
 * [ChallengeServer], so it never holds the DNS key. Only the challenge names of [names] are
 * accepted.
 */
class DnsChallenge(
    private val bunny: BunnyClient,
    private val zoneId: Long,
    private val zone: String,
    names: List<String>,
    private val onPresent: () -> Unit = {},
    private val onCleanup: () -> Unit = {},
) {
    private val allowed = names.map { "$LABEL.${it.removePrefix("*.")}".lowercase() }.toSet()
    private val txt = BunnyRecord.typeCode("TXT")

    private fun relative(fqdn: String): String {
        val name = fqdn.trimEnd('.').lowercase()
        require(name in allowed) { "$name is not a challenge name of this order" }
        require(name.endsWith(".$zone")) { "$name is outside the zone $zone" }
        return name.removeSuffix(".$zone")
    }

    suspend fun present(fqdn: String, value: String) {
        require(VALUE.matches(value)) { "not a challenge value" }
        bunny.addRecord(zoneId, BunnyRecord(type = txt, ttl = TTL, value = value, name = relative(fqdn), comment = COMMENT))
        onPresent()
    }

    suspend fun cleanup(fqdn: String, value: String) {
        val name = relative(fqdn)
        for (r in owned()) if (r.name.equals(name, ignoreCase = true) && r.value == value) bunny.deleteRecord(zoneId, r.id)
        onCleanup()
    }

    /** Removes each challenge record that an earlier order left behind. */
    suspend fun sweep() {
        for (r in owned()) bunny.deleteRecord(zoneId, r.id)
    }

    private suspend fun owned() = bunny.getZone(zoneId).records.filter { it.type == txt && it.comment == COMMENT }

    companion object {
        const val LABEL = "_acme-challenge"
        const val COMMENT = "${DesiredDnsRecord.OWNER_TAG} acme-challenge"
        private const val TTL = 60
        private val VALUE = Regex("^[A-Za-z0-9_-]{20,100}$")
    }
}
