package homegap.setup

import homegap.api.SshKeyDto
import homegap.config.ConfigLoader
import homegap.platform.Files
import homegap.platform.Subprocess
import kotlin.time.Duration.Companion.seconds

/**
 * SSH keys the orchestrator manages nodes with. Generated and imported keys live in [dir] (the
 * data volume, mode 0700); the private half never leaves the orchestrator.
 */
class NodeKeys(private val dir: String) {
    /** A new ed25519 key for [nodeId], replacing any earlier one generated for that id. */
    suspend fun generate(nodeId: String): SshKeyDto {
        val path = pathFor(nodeId)
        Files.delete(path)
        Files.delete("$path.pub")
        val r = Subprocess.run(
            listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "homegap-$nodeId", "-f", path),
            timeout = 30.seconds,
        )
        check(r.ok) { "ssh-keygen failed: ${r.stderr.trim().take(300)}" }
        return describe(path)
    }

    /** Stores a pasted private key for [nodeId] (unencrypted OpenSSH or PEM). */
    suspend fun import(nodeId: String, privateKey: String): SshKeyDto {
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        require(text.startsWith("-----BEGIN ") && "PRIVATE KEY-----" in text) { "That does not look like a private key." }
        val path = pathFor(nodeId)
        Files.writeAtomic(path, text, "600".toInt(8))
        return runCatching { describe(path) }.getOrElse {
            Files.delete(path)
            throw IllegalArgumentException("The key could not be read (passphrase-protected keys are not supported): ${it.message}")
        }
    }

    /** Uses a private key already on the orchestrator (for example under /secrets/ssh). */
    suspend fun existing(path: String): SshKeyDto {
        require(Files.exists(path)) { "No file at $path on the orchestrator." }
        return describe(path)
    }

    /** Public key and fingerprint of the private key at [path]. */
    private suspend fun describe(path: String): SshKeyDto {
        val pub = Subprocess.run(listOf("ssh-keygen", "-y", "-f", path), stdin = "", timeout = 10.seconds)
        check(pub.ok) { pub.stderr.trim().ifEmpty { "unreadable key" }.take(300) }
        val publicKey = pub.stdout.trim()
        val fingerprint = Subprocess.run(listOf("ssh-keygen", "-l", "-f", "-"), stdin = "$publicKey\n", timeout = 10.seconds)
            .stdout.split(' ').getOrNull(1).orEmpty()
        return SshKeyDto(path, publicKey, fingerprint)
    }

    private fun pathFor(nodeId: String): String {
        require(ConfigLoader.NODE_ID.matches(nodeId)) { "node id must match ${ConfigLoader.NODE_ID.pattern}" }
        Files.mkdirs(dir)
        Files.chmodPath(dir, "700".toInt(8))
        return "$dir/${nodeId}_ed25519"
    }
}
