package homegap.signin

import homegap.identity.Directory
import homegap.identity.Keys
import homegap.identity.SessionClaims
import homegap.identity.Sessions
import homegap.identity.Signed
import homegap.identity.SigningKey
import homegap.identity.User
import homegap.platform.Crypto
import homegap.platform.Files
import homegap.platform.nowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The sign-in of the homelab: it checks a password against the signed [Directory] and gives a
 * session with its own signature. The homelab puts the public key of this issuer in the
 * directory, and that is what gives its sessions access to the homelab.
 *
 * A device signs in with a code. The app asks for a code and opens the sign-in page with it. The
 * user signs in on the page, and the app then gets the session for the code.
 *
 * [dir] holds the private key of the issuer and the ended sessions. No other container gets it.
 */
class SignIn(
    private val dir: String,
    private val directory: () -> Directory?,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = SynchronizedObject()
    private val key: SigningKey by lazy { loadKey() }
    private val pending = HashMap<String, Pending>()
    private val failures = HashMap<String, MutableList<Long>>()
    private var ended: MutableMap<String, Long>? = null

    /** A code that waits for its user. [form] is the secret of the page that the code opened. */
    class Pending(val device: String, val application: String, val createdAt: Long, val form: String) {
        var token: String? = null
    }

    sealed interface Outcome {
        data class SignedIn(val user: User) : Outcome
        data object WrongPassword : Outcome

        /** The provider proved an address that no user of HomeGAP has, or the user is blocked. */
        data object NoSuchUser : Outcome
        data object TooManyAttempts : Outcome
        data object UnknownCode : Outcome
        data object NotReady : Outcome
    }

    val publicKey: String get() = key.publicKey

    /** Starts a sign-in for a device. Returns the code, or null if too many codes wait. */
    fun start(device: String, application: String): String? = synchronized(lock) {
        expire()
        if (pending.size >= MAX_PENDING) return@synchronized null
        val code = Crypto.token(CODE_LENGTH, CODE_ALPHABET)
        val form = Crypto.token(FORM_LENGTH)
        pending[code] = Pending(device.take(NAME_LENGTH), application.take(NAME_LENGTH), now(), form)
        code
    }

    /** The code that waits, or null if it is not known or it expired. */
    fun pending(code: String): Pending? = synchronized(lock) {
        expire()
        pending[normalize(code)]
    }

    /**
     * Signs the user in for [code]. [address] is the address of the browser, for the limit of
     * attempts. After success, [pending] has the token of the session.
     */
    fun signIn(code: String, email: String, password: String, address: String): Outcome {
        val users = directory() ?: return Outcome.NotReady
        val entry = pending(code) ?: return Outcome.UnknownCode
        val keys = listOf("address:$address", "user:${email.trim().lowercase()}")
        if (keys.any { limited(it) }) return Outcome.TooManyAttempts
        val user = users.userByEmail(email)
        // An address with no user costs the same time as a wrong password.
        val valid = Keys.verifyPassword(password, user?.passwordHash ?: DUMMY_HASH)
        if (user == null || !valid || user.blocked) {
            synchronized(lock) { keys.forEach { failures.getOrPut(it) { mutableListOf() } += now() } }
            return Outcome.WrongPassword
        }
        return approve(entry, user)
    }

    /**
     * Signs in the user with the address [email] for [code], with no password: a provider that
     * HomeGAP trusts proved the address. The user must exist in the directory of the homelab.
     */
    fun signInProven(code: String, email: String): Outcome {
        val users = directory() ?: return Outcome.NotReady
        val entry = pending(code) ?: return Outcome.UnknownCode
        val user = users.userByEmail(email)?.takeIf { !it.blocked } ?: return Outcome.NoSuchUser
        return approve(entry, user)
    }

    private fun approve(entry: Pending, user: User): Outcome {
        val claims = SessionClaims(
            sid = Crypto.token(SESSION_ID_LENGTH),
            userId = user.id,
            issuer = ISSUER,
            issuedAt = now(),
            expiresAt = now() + SESSION_LIFETIME.inWholeMilliseconds,
            device = entry.device,
        )
        synchronized(lock) { entry.token = Sessions.issue(key, claims) }
        return Outcome.SignedIn(user)
    }

    /** The token for [code] after the sign-in. The code is then gone. Null while the user has not signed in. */
    fun collect(code: String): String? = synchronized(lock) {
        val normal = normalize(code)
        pending[normal]?.token?.also { pending.remove(normal) }
    }

    /** The user of the session [token], or null if the session gives no access. */
    fun user(token: String): User? {
        val users = directory() ?: return null
        val claims = Sessions.read(token, users, now()) ?: return null
        if (synchronized(lock) { claims.sid in endedSessions() }) return null
        return users.user(claims.userId)
    }

    /** The directory of the homelab as this server has it now. */
    fun users(): Directory? = directory()

    /** A token that lets [device] open its control connection. */
    fun tunnelToken(device: Device): String {
        val claims = SessionClaims(
            sid = Crypto.token(SESSION_ID_LENGTH),
            userId = device.userId,
            issuer = ISSUER,
            issuedAt = now(),
            expiresAt = now() + TUNNEL_TOKEN_LIFETIME.inWholeMilliseconds,
            device = device.id,
            use = Sessions.TUNNEL,
        )
        return Sessions.issue(key, claims)
    }

    /** The claims of the tunnel token [token], or null if it gives no access now. */
    fun tunnelClaims(token: String): SessionClaims? {
        val users = directory() ?: return null
        return Sessions.read(token, users, now(), Sessions.TUNNEL)
    }

    /** Ends the session [token] (sign-out). */
    fun end(token: String) {
        val users = directory() ?: return
        val claims = Sessions.read(token, users, now()) ?: return
        synchronized(lock) {
            val sessions = endedSessions()
            sessions.entries.removeAll { it.value <= now() }
            sessions[claims.sid] = claims.expiresAt
            Files.writeAtomic("$dir/$ENDED_FILE", Signed.JSON.encodeToString(sessions.toMap()) + "\n", OWNER_ONLY)
        }
    }

    private fun normalize(code: String): String = code.trim().uppercase().replace("-", "")

    private fun expire() {
        val limit = now() - CODE_LIFETIME.inWholeMilliseconds
        pending.entries.removeAll { it.value.createdAt < limit }
    }

    private fun limited(key: String): Boolean = synchronized(lock) {
        val recent = failures[key] ?: return@synchronized false
        recent.removeAll { it < now() - FAILURE_WINDOW.inWholeMilliseconds }
        if (recent.isEmpty()) failures.remove(key)
        recent.size >= MAX_FAILURES
    }

    private fun endedSessions(): MutableMap<String, Long> {
        ended?.let { return it }
        val text = Files.readTextOrNull("$dir/$ENDED_FILE")
        val loaded = text?.let { runCatching { Signed.JSON.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
        return loaded.orEmpty().toMutableMap().also { ended = it }
    }

    private fun loadKey(): SigningKey {
        val file = "$dir/$KEY_FILE"
        Files.readTextOrNull(file)?.let { text ->
            return runCatching { Signed.JSON.decodeFromString<SigningKey>(text) }
                .getOrElse { error("$file is not readable") }
        }
        val created = Keys.newSigningKey()
        Files.mkdirs(dir)
        Files.chmodPath(dir, OWNER_DIR)
        Files.writeAtomic(file, Signed.JSON.encodeToString(created) + "\n", OWNER_ONLY)
        return created
    }

    companion object {
        /** The name of this issuer in [Directory.issuers]. */
        const val ISSUER = "homelab"
        const val PUBLIC_KEY_FILE = "issuer.pub"
        val CODE_LIFETIME = 10.minutes
        val SESSION_LIFETIME = 30.days
        val TUNNEL_TOKEN_LIFETIME = 7.days
        const val MAX_FAILURES = 5
        val FAILURE_WINDOW = 15.minutes
        private const val KEY_FILE = "issuer.key"
        private const val ENDED_FILE = "ended-sessions.json"
        private const val MAX_PENDING = 1000
        private const val CODE_LENGTH = 8

        /** No letters or digits that look the same. */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val FORM_LENGTH = 32
        private const val SESSION_ID_LENGTH = 24
        private const val NAME_LENGTH = 80
        private val OWNER_ONLY = "600".toInt(8)
        private val OWNER_DIR = "700".toInt(8)
        private val DUMMY_HASH by lazy { Keys.hashPassword(Crypto.token(FORM_LENGTH)) }
    }
}
