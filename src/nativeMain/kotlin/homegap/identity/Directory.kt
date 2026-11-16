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
 * A device with no user, for example a server that needs access. It signs in with its id and a
 * secret. [secretHash] is [Keys.fingerprint] of the secret.
 */
@Serializable
data class Machine(
    val id: String,
    val name: String,
    val roles: List<String> = emptyList(),
    val secretHash: String,
    val blocked: Boolean = false,
    val createdAt: Long = 0,
)

/**
 * A private name that a device can reach through the VPN. The app gives the name the [address],
 * and the homelab sends that address to the service. A device needs one of the [roles]. An admin
 * needs none.
 */
@Serializable
data class Resource(val name: String, val address: String, val roles: List<String> = emptyList()) {
    fun allows(roles: List<String>): Boolean = IdentityStore.ADMIN_ROLE in roles || roles.any { it in this.roles }
}

/**
 * What a device can do through the VPN besides the private names: leave for the internet at the
 * homelab ([exitRoles], null if the homelab is no exit) or at a node, and reach the DNS [resolvers] through the
 * tunnel ([resolverRoles]). An admin can do each of them.
 */
@Serializable
data class VpnAccess(
    val exitRoles: List<String>? = null,
    val resolvers: List<String> = emptyList(),
    val resolverRoles: List<String> = emptyList(),
    /** The roles for the exit of each node. With null, no node is an exit. */
    val nodeExitRoles: List<String>? = null,
) {
    fun exitFor(roles: List<String>): Boolean = exitRoles != null && permits(exitRoles, roles)

    fun nodeExitFor(roles: List<String>): Boolean = nodeExitRoles != null && permits(nodeExitRoles, roles)

    fun resolversFor(roles: List<String>): List<String> = if (permits(resolverRoles, roles)) resolvers else emptyList()

    private fun permits(wanted: List<String>, roles: List<String>): Boolean =
        IdentityStore.ADMIN_ROLE in roles || roles.any { it in wanted }
}

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
    val machines: List<Machine> = emptyList(),
    val resources: List<Resource> = emptyList(),
    val vpn: VpnAccess = VpnAccess(),
    /**
     * The public keys that can sign a session with access to the homelab: name to key. Only the
     * sign-in server of the homelab is here. A node signs sessions that give its exit only.
     */
    val issuers: Map<String, String> = emptyMap(),
) {
    fun user(id: String): User? = users.firstOrNull { it.id == id }

    fun machine(id: String): Machine? = machines.firstOrNull { it.id == id }

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
