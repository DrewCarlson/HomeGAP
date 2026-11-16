package homegap.identity

/**
 * The primitives that [Keys] needs from a cryptography library. One class implements it, and no
 * other file of HomeGAP names the library. To change the library, write a new class and select it
 * in [Keys].
 *
 * The formats are part of the contract, because keys and hashes are on disk:
 * - signatures are ECDSA with the curve P-256 and SHA-256, as the two numbers `r` and `s` (64 bytes)
 * - a private key is PEM (PKCS #8)
 * - a public key is the point of the curve, not compressed (65 bytes)
 * - password hashes are PBKDF2 with HMAC-SHA-256
 */
interface CryptoBackend {
    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun sha256(data: ByteArray): ByteArray

    fun newKeyPair(): KeyPair

    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray

    /** Returns true if [signature] is valid. It can throw for a key or a signature that has another form. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean

    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray
}
