package homegap.acme

import homegap.clients.SshSession.Companion.shQuote

/**
 * TLS probes run on a node: a handshake to its own port 443 for each public name, so the result is
 * what a visitor who reaches that node gets. The probe reads only public fields.
 */
object CertProbes {
    /** What a node served for a name. Times are epoch seconds. */
    data class Result(
        val served: Boolean,
        val notAfter: Long? = null,
        val subject: String? = null,
        val issuer: String? = null,
        val sans: List<String> = emptyList(),
    )

    /** The subject of the certificate that Traefik serves when it has none for a name. */
    const val DEFAULT_SUBJECT = "TRAEFIK DEFAULT CERT"

    private const val BATCH = 10
    private const val SECONDS_IN_DAY = 86_400
    private const val EXPIRING_DAYS = 7
    private const val SOON_DAYS = 14
    private const val HEX_DIGITS = "0123456789abcdef"

    /** The shell function `hg_xp <server name> <name>`. It does one handshake and prints the `X` line of the name. */
    private const val PROBE_FUNCTION =
        $$"""hg_xp() { c=$(echo | timeout 6 openssl s_client -connect 127.0.0.1:443 -servername "$1" 2>/dev/null | openssl x509 -noout -enddate -subject -issuer -ext subjectAltName 2>/dev/null) || { echo "X $2 none"; return; }; end=$(echo "$c" | sed -n 's/^notAfter=//p'); sub=$(echo "$c" | sed -n 's/^subject=//p'); iss=$(echo "$c" | sed -n 's/^issuer=//p'); san=$(echo "$c" | grep -o 'DNS:[^,]*' | sed 's/^DNS://' | tr '\n' ',' ); echo "X $2 $(date -d "$end" +%s 2>/dev/null || echo 0)|$sub|$iss|$san"; }"""

    /**
     * The shell lines that print one `X` line for each of [names], and one `I` line with the
     * version of the certificate in [tlsFile] and the time of its last write. The probe of a
     * wildcard uses a name one label under it.
     */
    fun script(names: List<String>, tlsFile: String): String = buildString {
        appendLine(PROBE_FUNCTION)
        names.forEachIndexed { index, name ->
            val sni = if (name.startsWith("*.")) "homegap-probe.${name.removePrefix("*.")}" else name
            appendLine("hg_xp ${shQuote(sni)} ${shQuote(name)} &")
            if ((index + 1) % BATCH == 0) appendLine("wait")
        }
        appendLine("wait")
        val file = shQuote(tlsFile)
        append(
            $$"""echo "I $(sed -n 's/^#* *certificate \([0-9a-f]*\).*/\1/p' $$file 2>/dev/null | head -1) $(stat -c %Y $$file 2>/dev/null || echo 0)"""",
        )
    }

    /** Reads the `X` lines into a result for each name. */
    fun parse(lines: List<String>): Map<String, Result> = buildMap {
        for (line in lines) {
            if (!line.startsWith("X ")) continue
            val name = line.substringAfter("X ").substringBefore(' ')
            val value = line.substringAfter("X $name ", "")
            if (value == "none" || value.isBlank()) {
                put(name, Result(served = false))
                continue
            }
            val parts = value.split('|')
            put(
                name,
                Result(
                    served = true,
                    notAfter = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 },
                    subject = parts.getOrNull(1)?.trim()?.ifEmpty { null },
                    issuer = parts.getOrNull(2)?.trim()?.ifEmpty { null },
                    sans = parts.getOrNull(3)
                        .orEmpty()
                        .split(',')
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() },
                ),
            )
        }
    }

    /** Reads the `I` line: the installed version (null if there is none) and the time of its last write. */
    fun installed(lines: List<String>): Pair<String?, Long?>? {
        val line = lines.lastOrNull { it.startsWith("I ") } ?: return null
        val parts = line.removePrefix("I ").trim().split(' ').filter { it.isNotEmpty() }
        val hash = parts.firstOrNull()?.takeIf { parts.size == 2 && it.all { digit -> digit in HEX_DIGITS } }
        val at = parts.lastOrNull()?.toLongOrNull()?.takeIf { it > 0 }
        return hash to at.takeIf { hash != null }
    }

    /**
     * The state of [result] for [name] at [nowSeconds]: `ok`, `soon` (fewer than 14 days),
     * `expiring` (fewer than 7), `none`, `default` or `mismatch`. [renewAt] is the time of the next
     * order if the certificate is the one that HomeGAP renews. Such a certificate is `expiring`
     * only when its renewal is late.
     */
    fun state(name: String, result: Result, nowSeconds: Long, renewAt: Long? = null): String {
        if (!result.served) return "none"
        if (result.subject.orEmpty().contains(DEFAULT_SUBJECT, ignoreCase = true)) return "default"
        if (!covers(name, result)) return "mismatch"
        val days = daysLeft(result, nowSeconds) ?: return "ok"
        val notAfter = result.notAfter
        if (renewAt != null && notAfter != null && days >= 0) {
            val late = renewalIsLate(notAfter = notAfter, renewAt = renewAt, nowSeconds = nowSeconds)
            return if (late) "expiring" else "ok"
        }
        return when {
            days < EXPIRING_DAYS -> "expiring"
            days < SOON_DAYS -> "soon"
            else -> "ok"
        }
    }

    fun daysLeft(result: Result, nowSeconds: Long): Int? =
        result.notAfter?.let { ((it - nowSeconds) / SECONDS_IN_DAY).toInt() }

    /** True if a name on the certificate matches [name]. A wildcard [name] needs the same wildcard. */
    fun covers(name: String, result: Result): Boolean {
        val wanted = name.lowercase()
        val commonName = result.subject
            ?.substringAfter("CN", "")
            ?.trimStart(' ', '=')
            ?.substringBefore(',')
            ?.trim()
            ?.lowercase()
        val certNames = result.sans.ifEmpty { listOfNotNull(commonName) }
        if (wanted.startsWith("*.")) return wanted in certNames
        return certNames.any {
            it == wanted || (it.startsWith("*.") && CertNames.covers(it.removePrefix("*."), wanted))
        }
    }
}

/**
 * True when half of the time between the renewal and the expiry has passed. A certificate that is
 * valid for a few days is always near its expiry, so the days alone do not tell that a renewal is late.
 */
internal fun renewalIsLate(notAfter: Long, renewAt: Long, nowSeconds: Long): Boolean =
    nowSeconds >= renewAt + (notAfter - renewAt) / 2
