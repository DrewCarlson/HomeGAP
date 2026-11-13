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
        val record = BunnyRecord(type = txt, ttl = TTL, value = value, name = relative(fqdn), comment = COMMENT)
        bunny.addRecord(zoneId, record)
        onPresent()
    }

    suspend fun cleanup(fqdn: String, value: String) {
        val name = relative(fqdn)
        owned()
            .filter { it.name.equals(name, ignoreCase = true) && it.value == value }
            .forEach { bunny.deleteRecord(zoneId, it.id) }
        onCleanup()
    }

    /** Removes each challenge record that an earlier order did not remove. */
    suspend fun sweep() {
        owned().forEach { bunny.deleteRecord(zoneId, it.id) }
    }

    private suspend fun owned() = bunny.getZone(zoneId).records.filter { it.type == txt && it.comment == COMMENT }

    companion object {
        const val LABEL = "_acme-challenge"
        const val COMMENT = "${DesiredDnsRecord.OWNER_TAG} acme-challenge"
        private const val TTL = 60
        private val VALUE = Regex("^[A-Za-z0-9_-]{20,100}$")
    }
}
