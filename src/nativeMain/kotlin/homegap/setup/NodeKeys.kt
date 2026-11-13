package homegap.setup

import homegap.api.SshKeyDto
import homegap.config.ConfigLoader
import homegap.platform.Files
import homegap.platform.Subprocess
import kotlin.time.Duration.Companion.seconds

/**
 * The SSH keys that the orchestrator uses for the nodes. The keys that it makes or imports are in
 * [dir] (the data volume, mode 0700). The private half never leaves the orchestrator.
 */
class NodeKeys(private val dir: String) {
    /** Makes a new ed25519 key for [nodeId]. It replaces an earlier key for that id. */
    suspend fun generate(nodeId: String): SshKeyDto {
        val path = pathFor(nodeId)
        Files.delete(path)
        Files.delete("$path.pub")
        val result = Subprocess.run(
            listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "homegap-$nodeId", "-f", path),
            timeout = KEYGEN_TIMEOUT,
        )
        check(result.ok) { "ssh-keygen failed: ${result.stderr.trim().take(ERROR_TEXT_LENGTH)}" }
        return describe(path)
    }

    /** Stores a pasted private key for [nodeId] (unencrypted OpenSSH or PEM). */
    suspend fun import(nodeId: String, privateKey: String): SshKeyDto {
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        require(text.startsWith("-----BEGIN ") && "PRIVATE KEY-----" in text) {
            "That does not look like a private key."
        }
        val path = pathFor(nodeId)
        Files.writeAtomic(path, text, PRIVATE_FILE_MODE)
        return runCatching { describe(path) }.getOrElse {
            Files.delete(path)
            val problem = "The key could not be read (passphrase-protected keys are not supported)"
            throw IllegalArgumentException("$problem: ${it.message}")
        }
    }

    /** Uses a private key that is on the orchestrator already (for example under /secrets/ssh). */
    suspend fun existing(path: String): SshKeyDto {
        require(Files.exists(path)) { "No file at $path on the orchestrator." }
        return describe(path)
    }

    /** Returns the public key and the fingerprint of the private key at [path]. */
    private suspend fun describe(path: String): SshKeyDto {
        val public = Subprocess.run(listOf("ssh-keygen", "-y", "-f", path), stdin = "", timeout = READ_TIMEOUT)
        check(public.ok) { public.stderr.trim().ifEmpty { "unreadable key" }.take(ERROR_TEXT_LENGTH) }
        val publicKey = public.stdout.trim()
        val fingerprint = Subprocess
            .run(listOf("ssh-keygen", "-l", "-f", "-"), stdin = "$publicKey\n", timeout = READ_TIMEOUT)
            .stdout
            .split(' ')
            .getOrNull(1)
            .orEmpty()
        return SshKeyDto(path, publicKey, fingerprint)
    }

    private fun pathFor(nodeId: String): String {
        require(ConfigLoader.NODE_ID.matches(nodeId)) { "node id must match ${ConfigLoader.NODE_ID.pattern}" }
        Files.mkdirs(dir)
        Files.chmodPath(dir, PRIVATE_DIR_MODE)
        return "$dir/${nodeId}_ed25519"
    }

    private companion object {
        val PRIVATE_FILE_MODE = "600".toInt(8)
        val PRIVATE_DIR_MODE = "700".toInt(8)
        const val ERROR_TEXT_LENGTH = 300
        val KEYGEN_TIMEOUT = 30.seconds
        val READ_TIMEOUT = 10.seconds
    }
}
