package homegap.dashboard

import homegap.config.AppConfig
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.speedtest.RateLimiter

/**
 * The dashboard's bearer token. The API is never served without one: when the config names none
 * (or names one that is not available), a token is generated once and kept in the data directory
 * (mode 0600), where the operator reads it.
 */
class DashboardAuth(
    val token: String,
    /** Where the token was generated, or null when it comes from the config. */
    val generatedFile: String?,
) {
    private val failures = RateLimiter(MAX_FAILURES, 60_000)
    private val blocks = RateLimiter(0, 60_000)

    /** Whether [candidate] is the dashboard token (compared in constant time). */
    fun matches(candidate: String?): Boolean = candidate != null && Crypto.constantTimeEquals(candidate, token)

    /**
     * Whether [candidate] from [address] is the dashboard token. An address that sends more than
     * [MAX_FAILURES] wrong tokens in a minute is blocked for the rest of that minute, so a short
     * token is not found by trial.
     */
    fun accepts(candidate: String?, address: String): Boolean {
        if (blocked(address)) return false
        if (matches(candidate)) return true
        if (!failures.allow(address)) blocks.allow(address)
        return false
    }

    /** True while [address] is blocked after too many wrong tokens. */
    fun blocked(address: String): Boolean = blocks.seen(address)

    companion object {
        private const val LENGTH = 40
        const val MAX_FAILURES = 20

        fun tokenFile(config: AppConfig): String = "${config.dataDir}/dashboard-token"

        /** The configured token, else the generated one (created now if there is none yet). */
        fun resolve(config: AppConfig): DashboardAuth {
            config.dashboard.authToken()?.resolve()?.let { return DashboardAuth(it, null) }
            val file = tokenFile(config)
            val kept = stored(file)
            if (kept != null) return DashboardAuth(kept, file)
            val created = Crypto.token(LENGTH)
            Files.writeAtomic(file, created + "\n", "600".toInt(8))
            return DashboardAuth(created, file)
        }

        /** The token a running orchestrator uses, without creating one (for the CLI). */
        fun current(config: AppConfig): String? =
            config.dashboard.authToken()?.resolve() ?: stored(tokenFile(config))

        private fun stored(file: String): String? =
            if (Files.exists(file)) Files.readText(file).trim().takeIf { it.isNotEmpty() } else null
    }
}
