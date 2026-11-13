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

    /**
     * The shell lines that print one `X` line for each of [names], and one `I` line with the
     * version of the certificate in [tlsFile] and the time it was written. A wildcard is probed
     * with a name one label under it.
     */
    fun script(names: List<String>, tlsFile: String): String = buildString {
        appendLine("""hg_xp() { c=${'$'}(echo | timeout 6 openssl s_client -connect 127.0.0.1:443 -servername "${'$'}1" 2>/dev/null | openssl x509 -noout -enddate -subject -issuer -ext subjectAltName 2>/dev/null) || { echo "X ${'$'}2 none"; return; }; end=${'$'}(echo "${'$'}c" | sed -n 's/^notAfter=//p'); sub=${'$'}(echo "${'$'}c" | sed -n 's/^subject=//p'); iss=${'$'}(echo "${'$'}c" | sed -n 's/^issuer=//p'); san=${'$'}(echo "${'$'}c" | grep -o 'DNS:[^,]*' | sed 's/^DNS://' | tr '\n' ',' ); echo "X ${'$'}2 ${'$'}(date -d "${'$'}end" +%s 2>/dev/null || echo 0)|${'$'}sub|${'$'}iss|${'$'}san"; }""")
        names.forEachIndexed { index, name ->
            val sni = if (name.startsWith("*.")) "homegap-probe.${name.removePrefix("*.")}" else name
            appendLine("hg_xp ${shQuote(sni)} ${shQuote(name)} &")
            if ((index + 1) % BATCH == 0) appendLine("wait")
        }
        appendLine("wait")
        val q = shQuote(tlsFile)
        append("echo \"I \$(sed -n 's/^#* *certificate \\([0-9a-f]*\\).*/\\1/p' $q 2>/dev/null | head -1) \$(stat -c %Y $q 2>/dev/null || echo 0)\"")
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
                    sans = parts.getOrNull(3).orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                ),
            )
        }
    }

    /** Reads the `I` line: the installed version (null when there is none) and when it was written. */
    fun installed(lines: List<String>): Pair<String?, Long?>? {
        val line = lines.lastOrNull { it.startsWith("I ") } ?: return null
        val parts = line.removePrefix("I ").trim().split(' ').filter { it.isNotEmpty() }
        val hash = parts.firstOrNull()?.takeIf { parts.size == 2 && it.all { c -> c in "0123456789abcdef" } }
        val at = parts.lastOrNull()?.toLongOrNull()?.takeIf { it > 0 }
        return hash to at.takeIf { hash != null }
    }

    /**
     * The state of [result] for [name] at [nowSeconds]: `ok`, `soon` (fewer than 14 days),
     * `expiring` (fewer than 7), `none`, `default` or `mismatch`.
     */
    fun state(name: String, result: Result, nowSeconds: Long): String {
        if (!result.served) return "none"
        if (result.subject.orEmpty().contains(DEFAULT_SUBJECT, ignoreCase = true)) return "default"
        if (!covers(name, result)) return "mismatch"
        val days = daysLeft(result, nowSeconds) ?: return "ok"
        return when {
            days < 7 -> "expiring"
            days < 14 -> "soon"
            else -> "ok"
        }
    }

    fun daysLeft(result: Result, nowSeconds: Long): Int? = result.notAfter?.let { ((it - nowSeconds) / 86_400).toInt() }

    /** True if a name on the certificate matches [name]. A wildcard [name] needs the same wildcard. */
    fun covers(name: String, result: Result): Boolean {
        val n = name.lowercase()
        val cn = result.subject?.substringAfter("CN", "")?.trimStart(' ', '=')?.substringBefore(',')?.trim()?.lowercase()
        val all = result.sans.ifEmpty { listOfNotNull(cn) }
        if (n.startsWith("*.")) return n in all
        return all.any { it == n || (it.startsWith("*.") && CertNames.covers(it.removePrefix("*."), n)) }
    }
}
