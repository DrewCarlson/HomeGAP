package homegap.dashboard

import homegap.api.ShareLinkDto
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Read-only share links to the dashboard. Only a SHA-256 of each token is kept, in [path] (mode
 * 0600), so the file never holds a token that works. The token is returned once, by [create].
 */
class ShareLinks(
    private val path: String,
    private val now: () -> Long = ::nowMillis,
) {
    @Serializable
    private data class Stored(
        val id: String,
        val label: String,
        val hash: String,
        val createdAt: Long,
        val expiresAt: Long? = null,
        val lastUsedAt: Long? = null,
    )

    private val lock = SynchronizedObject()
    private var links: List<Stored> = Files.readTextOrNull(path)
        ?.let { runCatching { json.decodeFromString<List<Stored>>(it) }.getOrNull() }
        .orEmpty()

    /** Every link that has not expired, newest first. */
    fun list(): List<ShareLinkDto> = synchronized(lock) {
        val at = now()
        links.filter { it.live(at) }.map { it.dto() }
    }

    /** A new link for [label], valid for [ttlMillis] (null: until revoked); the token is shown once. */
    fun create(label: String, ttlMillis: Long?): Pair<ShareLinkDto, String> = synchronized(lock) {
        val name = label.trim()
        require(name.isNotEmpty()) { "a label is required" }
        require(name.length <= MAX_LABEL) { "the label is longer than $MAX_LABEL characters" }
        require(ttlMillis == null || ttlMillis > 0) { "the expiry must be in the future" }
        val at = now()
        val token = PREFIX + Crypto.token(TOKEN_LENGTH)
        val link = Stored(
            id = Crypto.token(12, "abcdefghijklmnopqrstuvwxyz0123456789"),
            label = name,
            hash = Crypto.sha256Hex(token),
            createdAt = at,
            expiresAt = ttlMillis?.let { at + it },
        )
        links = listOf(link) + links.filter { it.live(at) }
        save()
        link.dto() to token
    }

    /** Removes link [id]; its token stops working at once. False when there is no such link. */
    fun revoke(id: String): Boolean = synchronized(lock) {
        val before = links.size
        links = links.filter { it.id != id }
        (links.size != before).also { removed -> if (removed) save() }
    }

    /** Whether link [id] still works (not revoked, not expired). */
    fun isLive(id: String): Boolean = synchronized(lock) {
        val at = now()
        links.any { it.id == id && it.live(at) }
    }

    /** The live link that [token] belongs to, or null. Notes when it was used. */
    fun verify(token: String): ShareLinkDto? {
        if (!token.startsWith(PREFIX)) return null
        val hash = Crypto.sha256Hex(token)
        return synchronized(lock) {
            val at = now()
            val index = links.indexOfFirst { it.hash == hash && it.live(at) }
            if (index < 0) return@synchronized null
            val link = links[index]
            // Each request uses the link; write the time down only once a minute.
            if (link.lastUsedAt == null || at - link.lastUsedAt >= USE_RESOLUTION_MS) {
                links = links.toMutableList().also { it[index] = link.copy(lastUsedAt = at) }
                save()
            }
            links[index].dto()
        }
    }

    private fun Stored.live(at: Long): Boolean = expiresAt == null || expiresAt > at

    private fun Stored.dto() = ShareLinkDto(
        id = id,
        label = label,
        createdAt = createdAt,
        expiresAt = expiresAt,
        lastUsedAt = lastUsedAt,
    )

    private fun save() {
        runCatching {
            Files.writeAtomic(
                path = path,
                content = json.encodeToString(links),
                mode = "600".toInt(8),
            )
        }
    }

    companion object {
        /** Marks a share link token, so it can't be mistaken for the dashboard token. */
        const val PREFIX = "hgv_"
        private const val TOKEN_LENGTH = 32
        private const val MAX_LABEL = 80
        private const val USE_RESOLUTION_MS = 60_000L
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
