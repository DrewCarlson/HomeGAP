package homegap.identity

import kotlinx.serialization.Serializable

/** What a session token says. The issuer signs it, so no other part can change it. */
@Serializable
data class SessionClaims(
    /** The id of the session. A sign-out puts it on the list of ended sessions. */
    val sid: String,
    val userId: String,
    /** The name of the signer. It gives access to the homelab only if the [Directory] has its key. */
    val issuer: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val device: String = "",
    /** What the token is for: [Sessions.SESSION] or [Sessions.TUNNEL]. A token for one is not valid for the other. */
    val use: String = Sessions.SESSION,
)

/**
 * Session tokens: `hg1.<claims>.<signature>`, both parts in base64url. A reader needs only the
 * signed [Directory] to check one, with no call to the homelab.
 */
object Sessions {
    /** [SessionClaims.use] of a session of a user. */
    const val SESSION = "session"

    /** [SessionClaims.use] of the token for the control connection of a device. [SessionClaims.device] is its id. */
    const val TUNNEL = "tunnel"
    private const val PREFIX = "hg1"
    private const val PARTS = 3

    fun issue(key: SigningKey, claims: SessionClaims): String {
        val payload = Signed.JSON.encodeToString(claims).encodeToByteArray()
        return listOf(PREFIX, Keys.encode(payload), Keys.encode(Keys.sign(key.privateKey, payload))).joinToString(".")
    }

    /**
     * The claims of [token] if it gives access to the homelab at the time [now]: an issuer of
     * [directory] signed it for [use], it has not expired, and its user or machine is there and
     * not blocked.
     */
    fun read(token: String, directory: Directory, now: Long, use: String = SESSION): SessionClaims? {
        val parts = token.split('.')
        if (parts.size != PARTS || parts[0] != PREFIX) return null
        val payload = Keys.decode(parts[1]) ?: return null
        val signature = Keys.decode(parts[2]) ?: return null
        val claims = runCatching { Signed.JSON.decodeFromString<SessionClaims>(payload.decodeToString()) }
            .getOrNull() ?: return null
        val key = directory.issuers[claims.issuer] ?: return null
        if (!Keys.verify(key, payload, signature)) return null
        if (now >= claims.expiresAt || claims.use != use) return null
        val active = when {
            claims.userId.isEmpty() -> directory.machine(claims.device)?.blocked == false
            else -> directory.user(claims.userId)?.blocked == false
        }
        return claims.takeIf { active }
    }
}
