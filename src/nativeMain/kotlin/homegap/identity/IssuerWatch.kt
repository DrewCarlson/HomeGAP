package homegap.identity

import homegap.platform.Files
import homegap.platform.watchDirectory
import homegap.reconcile.ReconcileLog
import homegap.signin.SignIn

/**
 * Puts the public key of the sign-in server of the homelab in the signed directory. The server
 * makes its key in its own container and writes the public part to [signInDir]. Only a session
 * with the signature of a key in the directory gives access to the homelab.
 */
class IssuerWatch(private val signInDir: String, private val identity: IdentityStore, private val log: ReconcileLog) {
    suspend fun run() {
        identity.publish()
        watchDirectory(signInDir) { it == SignIn.PUBLIC_KEY_FILE }.collect {
            val key = Files.readTextOrNull("$signInDir/${SignIn.PUBLIC_KEY_FILE}")?.trim()
            if (!key.isNullOrEmpty() && identity.setIssuer(SignIn.ISSUER, key)) {
                log.change("identity", "the sign-in server of the homelab has a new key: the directory names it now")
            }
        }
    }
}
