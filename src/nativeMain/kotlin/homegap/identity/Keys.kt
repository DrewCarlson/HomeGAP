package homegap.identity

import homegap.platform.Crypto
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** A key pair for signatures: [privateKey] is PEM, and [publicKey] is the point of the curve in base64url. */
@Serializable
data class SigningKey(val privateKey: String, val publicKey: String)

/**
 * The cryptography of the identity system: signatures of the homelab, password hashes and
 * SHA-256. The rest of HomeGAP uses only this object. The algorithms are in [CryptoBackend].
 */
object Keys {
    /** The one place that selects the library. */
    private val backend: CryptoBackend by lazy { OpenSslBackend() }
    private val encoding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private const val PASSWORD_SCHEME = "pbkdf2-sha256"
    private const val PASSWORD_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val PASSWORD_PARTS = 4

    /** The most iterations that a stored hash can ask for, so a bad file cannot stop the sign-in. */
    private const val MAX_ITERATIONS = 5_000_000

    fun encode(bytes: ByteArray): String = encoding.encode(bytes)

    /** The bytes of [text], or null if the text is not base64url. */
    fun decode(text: String): ByteArray? = runCatching { encoding.decode(text) }.getOrNull()

    fun sha256(bytes: ByteArray): ByteArray = backend.sha256(bytes)

    /** The SHA-256 of [text], in base64url. HomeGAP stores this in place of a token. */
    fun fingerprint(text: String): String = encode(sha256(text.encodeToByteArray()))

    /** A new key pair for signatures. */
    fun newSigningKey(): SigningKey {
        val pair = backend.newKeyPair()
        return SigningKey(pair.privateKey.decodeToString(), encode(pair.publicKey))
    }

    /** The signature of [data] with [privateKey] (PEM). */
    fun sign(privateKey: String, data: ByteArray): ByteArray = backend.sign(privateKey.encodeToByteArray(), data)

    /** Returns true if [signature] is the signature of [data] by the owner of [publicKey] (base64url). */
    fun verify(publicKey: String, data: ByteArray, signature: ByteArray): Boolean {
        val key = decode(publicKey) ?: return false
        return runCatching { backend.verify(key, data, signature) }.getOrDefault(false)
    }

    /** The hash of [password] to store: scheme, iterations, salt and hash. */
    fun hashPassword(password: String): String {
        val salt = Crypto.randomBytes(SALT_BYTES)
        val hash = derive(password, salt, PASSWORD_ITERATIONS)
        return listOf(PASSWORD_SCHEME, PASSWORD_ITERATIONS, encode(salt), encode(hash)).joinToString("$")
    }

    /** Returns true if [password] has the hash [stored] (from [hashPassword]). */
    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != PASSWORD_PARTS || parts[0] != PASSWORD_SCHEME) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return false
        val salt = decode(parts[2]) ?: return false
        val expected = decode(parts[3]) ?: return false
        return constantTimeEquals(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        backend.pbkdf2(password.encodeToByteArray(), salt, iterations, HASH_BYTES)

    private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) return false
        var difference = 0
        for (index in first.indices) difference = difference or (first[index].toInt() xor second[index].toInt())
        return difference == 0
    }
}
