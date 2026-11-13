package homegap.acme

import homegap.platform.Files
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The state of the certificate order and the last orders, kept in files so that a restart does
 * not forget a failure and its retry time. Times are epoch ms.
 */
class CertOrderStore(dir: String) {
    /** [state] is `idle`, `running` or `failed`. [failures] holds the times of recent failed orders. */
    @Serializable
    data class State(
        val state: String = IDLE,
        val step: String? = null,
        val startedAt: Long? = null,
        val reason: String? = null,
        val names: List<String> = emptyList(),
        val cause: String? = null,
        val causeText: String? = null,
        val failedNames: List<String> = emptyList(),
        val failedAt: Long? = null,
        val retryAt: Long? = null,
        val failures: List<Long> = emptyList(),
    ) {
        fun failuresSince(since: Long): Int = failures.count { it >= since }
    }

    /** One past order (`order`) or revocation (`revoke`). [hash] names the material and stays on the homelab. */
    @Serializable
    data class Record(
        val id: String,
        val kind: String,
        val at: Long,
        val reason: String,
        val names: List<String> = emptyList(),
        val ok: Boolean,
        val durationMs: Long? = null,
        val cause: String? = null,
        val causeText: String? = null,
        val version: String? = null,
        val notBefore: Long? = null,
        val hash: String? = null,
    )

    private val stateFile = "$dir/order-state.json"
    private val historyFile = "$dir/order-history.json"
    private val lock = SynchronizedObject()
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedState: State? = null
    private var cachedHistory: List<Record>? = null

    fun state(): State = synchronized(lock) {
        cachedState ?: (Files.readTextOrNull(stateFile)?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() } ?: State())
            .also { cachedState = it }
    }

    fun update(change: (State) -> State): State = synchronized(lock) {
        val next = change(state())
        cachedState = next
        write(stateFile, json.encodeToString(next))
        next
    }

    /** Newest first. */
    fun history(): List<Record> = synchronized(lock) {
        cachedHistory ?: (Files.readTextOrNull(historyFile)?.let { runCatching { json.decodeFromString<List<Record>>(it) }.getOrNull() } ?: emptyList())
            .also { cachedHistory = it }
    }

    fun add(record: Record) = synchronized(lock) {
        val next = (listOf(record) + history()).take(HISTORY)
        cachedHistory = next
        write(historyFile, json.encodeToString(next))
    }

    private fun write(path: String, text: String) {
        Files.mkdirs(path.substringBeforeLast('/'))
        Files.writeAtomic(path, text, "600".toInt(8))
    }

    companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        const val FAILED = "failed"
        const val HISTORY = 20
    }
}

/** Why an order failed: a code from a fixed set and a short text for the screen. */
data class OrderCause(val code: String, val text: String) {
    companion object {
        val DNS_TIMEOUT = OrderCause("dns-timeout", "DNS did not show the challenge record in time")
        val RATE_LIMIT = OrderCause("rate-limit", "Let's Encrypt refused: too many orders")
        val CAA = OrderCause("caa", "A CAA record forbids the order")
        val BUNNY_KEY = OrderCause("bunny-key", "The Bunny key is missing or refused")
        val ACME_UNREACHABLE = OrderCause("acme-unreachable", "The ACME service did not answer")
        val TOO_MANY_NAMES = OrderCause("too-many-names", "More than ${CertNames.MAX} names")
        val OTHER = OrderCause("other", "The ACME client failed")

        /** Reads the cause from the error text of an order. */
        fun of(message: String): OrderCause {
            val m = message.lowercase()
            return when {
                "do not fit on one certificate" in m -> TOO_MANY_NAMES
                "bunny" in m && ("key" in m || "zone" in m || "401" in m || "unauthorized" in m) -> BUNNY_KEY
                "ratelimited" in m || "rate limit" in m || "too many" in m -> RATE_LIMIT
                "caa" in m -> CAA
                "propagation" in m || "time limit exceeded" in m || "txt record" in m || "dns problem" in m || "nxdomain" in m -> DNS_TIMEOUT
                "connection refused" in m || "no such host" in m || "i/o timeout" in m || "dial tcp" in m ||
                    "get directory" in m || "could not create client" in m || "service unavailable" in m -> ACME_UNREACHABLE
                else -> OTHER
            }
        }

        /**
         * The error text without anything that looks like a challenge value or a token. An ACME
         * client can print one in an error; the activity log never holds it.
         */
        fun redact(text: String): String = TOKEN.replace(text, "[hidden]")

        private val TOKEN = Regex("[A-Za-z0-9_-]{40,}")
    }
}
