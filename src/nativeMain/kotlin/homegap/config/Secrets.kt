package homegap.config

import homegap.platform.Files
import homegap.platform.env
import kotlinx.serialization.Serializable

/** Docker mounts a secret as a file with the name of the secret in this directory. */
private const val DOCKER_SECRETS_DIR = "/run/secrets"

/**
 * A reference to a secret that is not in the config: an environment variable, a mounted file, or
 * both. The environment variable has priority. The log and the dashboard show only the reference.
 */
@Serializable
data class SecretRef(val name: String, val envVar: String?, val file: String?) {
    fun resolve(): String? =
        envVar?.let { secretText(env(it)) }
            ?: file?.let { secretText(Files.readTextOrNull(it)) }
            ?: envVar?.let { secretText(Files.readTextOrNull("$DOCKER_SECRETS_DIR/$it")) }

    fun require(): String = resolve() ?: error("Secret '$name' is not available (${describe()})")

    fun describe(): String = listOfNotNull(envVar?.let { "env:$it" }, file?.let { "file:$it" }).joinToString(", ")

    private fun secretText(text: String?): String? = text?.takeIf { it.isNotBlank() }?.trim()
}

/** Every secret the configuration references, for the dashboard's "referenced secrets" view. */
fun AppConfig.referencedSecrets(): List<Pair<SecretRef, String>> = buildList {
    add(bunny.apiKey to "Bunny DNS API")
    pangolin.licenseKey()?.let { add(it to "Pangolin EE license (all nodes)") }
    dashboard.authToken()?.let { add(it to "Dashboard bearer token") }
    for (node in nodes) {
        add(node.sshKey to "SSH key for node ${node.id}")
        if (node.licenseKeyEnv != null || node.licenseKeyFile != null) {
            node.licenseKey(pangolin)?.let { add(it to "Pangolin EE license for node ${node.id}") }
        }
    }
}
