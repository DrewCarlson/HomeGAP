package homegap.identity

import homegap.config.AppConfig
import homegap.platform.Files
import homegap.platform.watchDirectory
import homegap.reconcile.ReconcileLog
import kotlinx.serialization.Serializable

/**
 * What the sign-in provider said about a person at a sign-in: the address that it proved, the
 * name and the groups. [at] is the time of the sign-in (epoch ms).
 */
@Serializable
data class ProviderUser(val email: String, val name: String = "", val groups: List<String> = emptyList(), val at: Long)

/**
 * The people that the sign-in provider vouched for, in a file of the folder of the sign-in. The
 * sign-in server writes the file. The orchestrator reads it and is the only part that changes
 * the users.
 */
object ProviderUsers {
    const val FILE = "provider-users.json"

    fun read(dir: String): Map<String, ProviderUser> {
        val text = Files.readTextOrNull("$dir/$FILE") ?: return emptyMap()
        return runCatching { Signed.JSON.decodeFromString<Map<String, ProviderUser>>(text) }.getOrDefault(emptyMap())
    }

    /** Keeps [person] as the newest answer of the provider for the address. */
    fun record(dir: String, person: ProviderUser) {
        val people = read(dir) + (person.email.trim().lowercase() to person)
        Files.writeAtomic("$dir/$FILE", Signed.JSON.encodeToString(people) + "\n")
    }
}

/**
 * Makes and changes the users that the sign-in provider vouches for. The sign-in server of the
 * homelab writes each answer of the provider to [signInDir]. This watch gives it to the
 * [identity] store with the settings of `sign_in.oidc` in [config].
 */
class ProviderUsersWatch(
    private val signInDir: String,
    private val identity: IdentityStore,
    private val config: () -> AppConfig,
    private val log: ReconcileLog,
) {
    suspend fun run() {
        watchDirectory(signInDir) { it == ProviderUsers.FILE }.collect { apply() }
    }

    private fun apply() {
        val oidc = config().signIn.oidc ?: return
        for (person in ProviderUsers.read(signInDir).values.sortedBy { it.at }) {
            runCatching { identity.applyProvider(person, oidc) }
                .onSuccess { change -> change?.let { log.change("identity", it) } }
                .onFailure { log.error("identity", "cannot take ${person.email} from ${oidc.name}: ${it.message}") }
        }
    }
}
