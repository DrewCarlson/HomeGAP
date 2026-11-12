package homegap.config

import homegap.platform.Files
import homegap.platform.env
import kotlinx.serialization.Serializable

/**
 * A reference to a secret held outside the config: an environment variable, a mounted file, or
 * both (env wins). Only the reference is ever logged or shown in the dashboard.
 */
@Serializable
data class SecretRef(val name: String, val envVar: String?, val file: String?) {
    fun resolve(): String? {
        envVar?.let { e -> env(e)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
        file?.let { f -> Files.readTextOrNull(f)?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
        // Docker secrets convention: /run/secrets/<ENV_NAME>
        envVar?.let { e -> Files.readTextOrNull("/run/secrets/$e")?.takeIf { it.isNotBlank() }?.let { return it.trim() } }
        return null
    }

    fun require(): String = resolve() ?: error("Secret '$name' is not available (${describe()})")

    fun describe(): String = listOfNotNull(envVar?.let { "env:$it" }, file?.let { "file:$it" }).joinToString(", ")
}

/** Every secret the configuration references, for the dashboard's "referenced secrets" view. */
fun AppConfig.referencedSecrets(): List<Pair<SecretRef, String>> = buildList {
    add(bunny.apiKey to "Bunny DNS API")
    pangolin.licenseKey()?.let { add(it to "Pangolin EE license (all nodes)") }
    dashboard.authToken()?.let { add(it to "Dashboard bearer token") }
    for (n in nodes) {
        add(n.sshKey to "SSH key for node ${n.id}")
        if (n.licenseKeyEnv != null || n.licenseKeyFile != null) n.licenseKey(pangolin)?.let { add(it to "Pangolin EE license for node ${n.id}") }
    }
}
