package homegap.identity

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256

/** [CryptoBackend] with the library `cryptography-kotlin`, which uses OpenSSL 3. */
class OpenSslBackend : CryptoBackend {
    private val provider = CryptographyProvider.Default
    private val ecdsa = provider.get(ECDSA)

    override fun sha256(data: ByteArray): ByteArray = provider.get(SHA256).hasher().hashBlocking(data)

    override fun newKeyPair(): CryptoBackend.KeyPair {
        val pair = ecdsa.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        return CryptoBackend.KeyPair(
            privateKey = pair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.PEM),
            publicKey = pair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
        )
    }

    override fun sign(privateKey: ByteArray, data: ByteArray): ByteArray =
        ecdsa.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PrivateKey.Format.PEM, privateKey)
            .signatureGenerator(SHA256, ECDSA.SignatureFormat.RAW)
            .generateSignatureBlocking(data)

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        ecdsa.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, publicKey)
            .signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
            .tryVerifySignatureBlocking(data, signature)

    override fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray =
        provider.get(PBKDF2)
            .secretDerivation(SHA256, iterations, length.bytes, salt)
            .deriveSecretToByteArrayBlocking(password)
}
