package homegap.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A person who can sign in. [passwordHash] comes from [Keys.hashPassword]. With null, the user has no password. */
@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String = "",
    val roles: List<String> = emptyList(),
    val passwordHash: String? = null,
    /** A blocked user cannot sign in, and each session of the user ends. */
    val blocked: Boolean = false,
    val createdAt: Long = 0,
)

/**
 * What the homelab says about who can sign in. The homelab signs it ([Signed]), and each part
 * that checks a user or a session trusts only a directory with that signature.
 */
@Serializable
data class Directory(
    /** Grows with each change. A reader keeps the directory with the highest version. */
    val version: Long = 0,
    val issuedAt: Long = 0,
    val users: List<User> = emptyList(),
    /**
     * The public keys that can sign a session with access to the homelab: name to key. Only the
     * sign-in server of the homelab is here. A node signs sessions that give its exit only.
     */
    val issuers: Map<String, String> = emptyMap(),
) {
    fun user(id: String): User? = users.firstOrNull { it.id == id }

    fun userByEmail(email: String): User? = users.firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }
}

/** A text with the signature of its author. [payload] is the exact text that the author signed. */
@Serializable
data class Signed(val payload: String, val signature: String) {
    /** The payload, or null if [publicKey] did not sign it. */
    fun open(publicKey: String): String? {
        val bytes = Keys.decode(signature) ?: return null
        return payload.takeIf { Keys.verify(publicKey, it.encodeToByteArray(), bytes) }
    }

    fun render(): String = JSON.encodeToString(this) + "\n"

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun seal(key: SigningKey, payload: String): Signed =
            Signed(payload, Keys.encode(Keys.sign(key.privateKey, payload.encodeToByteArray())))

        /** The signed text in [text], or null if the text has another form. */
        fun parse(text: String): Signed? = runCatching { JSON.decodeFromString<Signed>(text) }.getOrNull()

        /** The directory in [text] (from [render]) if [publicKey] signed it, or null. */
        fun directory(text: String, publicKey: String): Directory? {
            val payload = parse(text)?.open(publicKey) ?: return null
            return runCatching { JSON.decodeFromString<Directory>(payload) }.getOrNull()
        }
    }
}
