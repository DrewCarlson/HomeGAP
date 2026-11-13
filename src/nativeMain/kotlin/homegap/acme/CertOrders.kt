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
        cachedState ?: (readState() ?: State()).also { cachedState = it }
    }

    private fun readState(): State? =
        Files.readTextOrNull(stateFile)?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() }

    fun update(change: (State) -> State): State = synchronized(lock) {
        val next = change(state())
        cachedState = next
        write(stateFile, json.encodeToString(next))
        next
    }

    /** Newest first. */
    fun history(): List<Record> = synchronized(lock) {
        cachedHistory ?: readHistory().orEmpty().also { cachedHistory = it }
    }

    private fun readHistory(): List<Record>? =
        Files.readTextOrNull(historyFile)?.let { runCatching { json.decodeFromString<List<Record>>(it) }.getOrNull() }

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
            val text = message.lowercase()
            fun has(vararg marks: String) = marks.any { it in text }
            return when {
                has("do not fit on one certificate") -> TOO_MANY_NAMES
                "bunny" in text && has("key", "zone", "401", "unauthorized") -> BUNNY_KEY
                has("ratelimited", "rate limit", "too many") -> RATE_LIMIT
                has("caa") -> CAA
                has(*DNS_TIMEOUT_MARKS) -> DNS_TIMEOUT
                has(*ACME_UNREACHABLE_MARKS) -> ACME_UNREACHABLE
                else -> OTHER
            }
        }

        /**
         * The error text without the parts that look like a challenge value or a token. An ACME
         * client can print one in an error, and the activity log must not hold it.
         */
        fun redact(text: String): String = TOKEN.replace(text, "[hidden]")

        private val TOKEN = Regex("[A-Za-z0-9_-]{40,}")

        // Parts of the error text of Lego, in lower case.
        private val DNS_TIMEOUT_MARKS =
            arrayOf("propagation", "time limit exceeded", "txt record", "dns problem", "nxdomain")
        private val ACME_UNREACHABLE_MARKS = arrayOf(
            "connection refused",
            "no such host",
            "i/o timeout",
            "dial tcp",
            "get directory",
            "could not create client",
            "service unavailable",
        )
    }
}
