package homegap.dashboard

import homegap.config.AppConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.speedtest.RateLimiter

/**
 * The dashboard's bearer token. No request gets the API without one. When the config names none,
 * or names one that is not available, the orchestrator generates a token once and keeps it in the
 * data directory (mode 0600), where the operator reads it.
 */
class DashboardAuth(
    val token: String,
    /** The file that holds the generated token, or null when the token comes from the config. */
    val generatedFile: String?,
    /** The addresses of the front door container, the proxy that publishes the dashboard. */
    private val frontDoor: () -> Set<String> = { emptySet() },
) {
    private val failures = RateLimiter(MAX_FAILURES, WINDOW_MILLIS)
    private val blocks = RateLimiter(0, WINDOW_MILLIS)

    /** Whether [candidate] is the dashboard token. The comparison takes constant time. */
    fun matches(candidate: String?): Boolean = candidate != null && Crypto.constantTimeEquals(candidate, token)

    /**
     * Whether [candidate] from [address] is the dashboard token. An address that sends more than
     * [MAX_FAILURES] wrong tokens in a minute gets no access for the rest of that minute, so
     * trials cannot find a short token.
     */
    fun accepts(candidate: String?, address: String): Boolean {
        if (blocked(address)) return false
        if (matches(candidate)) return true
        if (!failures.allow(address)) {
            blocks.allow(address)
        }
        return false
    }

    /**
     * The address of the client of a request from [peer]. The front door adds the address that it
     * got the request from to the end of `X-Forwarded-For`, so each viewer has an own address.
     * The header of each other sender does not count, because a client can write it.
     */
    fun client(peer: String, forwardedFor: String?): String {
        val forwarded = forwardedFor?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        return if (forwarded != null && peer in frontDoor()) forwarded else peer
    }

    /** True while [address] is blocked after too many wrong tokens. */
    fun blocked(address: String): Boolean = blocks.seen(address)

    companion object {
        private const val LENGTH = 40
        private const val WINDOW_MILLIS = 60_000L
        const val MAX_FAILURES = 20

        fun tokenFile(config: AppConfig): String = "${config.dataDir}/dashboard-token"

        /** The configured token, else the generated one (created now if there is none yet). */
        fun resolve(config: AppConfig, frontDoor: () -> Set<String> = { emptySet() }): DashboardAuth {
            config.dashboard.authToken()?.resolve()?.let { return DashboardAuth(it, null, frontDoor) }
            val file = tokenFile(config)
            val kept = stored(file)
            if (kept != null) return DashboardAuth(kept, file, frontDoor)
            val created = Crypto.token(LENGTH)
            Files.writeAtomic(file, created + "\n", "600".toInt(8))
            return DashboardAuth(created, file, frontDoor)
        }

        /** The token a running orchestrator uses, without creating one (for the CLI). */
        fun current(config: AppConfig): String? =
            config.dashboard.authToken()?.resolve() ?: stored(tokenFile(config))

        private fun stored(file: String): String? =
            if (Files.exists(file)) Files.readText(file).trim().takeIf { it.isNotEmpty() } else null
    }
}
