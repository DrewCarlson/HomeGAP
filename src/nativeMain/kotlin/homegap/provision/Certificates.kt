package homegap.provision

import homegap.config.CertificatesConfig
import homegap.platform.Files
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

data class CertMaterial(val certPem: String, val keyPem: String, val source: String, val domains: List<String>)

/**
 * Loads the wildcard + root certificate the homelab Traefik already holds, so every node can
 * serve `terminate` mode immediately without its own ACME issuance.
 */
object Certificates {
    fun load(cfg: CertificatesConfig, domain: String): CertMaterial? = when (cfg.source) {
        "files" -> {
            val cert = Files.readText(cfg.certFile!!)
            val key = Files.readText(cfg.keyFile!!)
            CertMaterial(cert, key, "files:${cfg.certFile}", emptyList())
        }
        "acme_json" -> fromAcmeJson(Files.readText(cfg.acmeJson!!), domain, cfg.resolver)
            ?: error("No certificate covering *.$domain in ${cfg.acmeJson}")
        else -> null
    }

    /** Picks the cert whose main/SANs cover the wildcard (preferring one that also covers the apex). */
    fun fromAcmeJson(text: String, domain: String, resolver: String?): CertMaterial? {
        val root = Json.parseToJsonElement(text).jsonObject
        val candidates = mutableListOf<CertMaterial>()
        for ((name, store) in root) {
            if (resolver != null && name != resolver) continue
            val certs = (store as? JsonObject)?.get("Certificates") as? JsonArray ?: continue
            for (c in certs) {
                val o = c.jsonObject
                val d = o["domain"]?.jsonObject ?: continue
                val names = listOfNotNull(d["main"]?.jsonPrimitive?.contentOrNull) +
                    ((d["sans"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList())
                if ("*.$domain" !in names) continue
                val cert = o["certificate"]?.jsonPrimitive?.contentOrNull ?: continue
                val key = o["key"]?.jsonPrimitive?.contentOrNull ?: continue
                candidates += CertMaterial(
                    Base64.decode(cert).decodeToString(),
                    Base64.decode(key).decodeToString(),
                    "acme_json:$name",
                    names,
                )
            }
        }
        return candidates.sortedByDescending { domain in it.domains }.firstOrNull()
    }
}
