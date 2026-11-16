package homegap.config

import homegap.platform.Files
import homegap.platform.env

/**
 * The directories that the orchestrator shares with other containers. Each has a fixed path in
 * the HomeGAP image, so a compose file only mounts a folder there. Each is a folder of its own,
 * so the other container never sees the state cache, the SSH keys or the dashboard token.
 *
 * Outside the image the fixed path is not there, and the directory is inside the data directory.
 */
object SharedDirs {
    const val MESH = "/mesh"
    const val TUNNELS = "/tunnels"
    const val CLUSTER_CONFIG = "/cluster-config"
    const val IDENTITY = "/identity"
    const val SIGN_IN = "/sign-in"

    /** What the orchestrator and the mesh agent of the homelab (`homegap mesh`) tell each other. */
    fun mesh(dataDir: String): String = resolve("HOMEGAP_MESH_DIR", MESH, "$dataDir/mesh")

    /** Connector specs and their status files, shared with the `tunnels` container. */
    fun tunnels(dataDir: String): String = resolve("HOMEGAP_TUNNELS_DIR", TUNNELS, "$dataDir/tunnels")

    /** The config of the cluster containers of the homelab, which read it. */
    fun clusterConfig(dataDir: String): String =
        resolve("HOMEGAP_CLUSTER_CONFIG_DIR", CLUSTER_CONFIG, "$dataDir/cluster/config")

    /** The signed directory of users and the public key of the homelab, which the sign-in server reads. */
    fun identity(dataDir: String): String = resolve("HOMEGAP_IDENTITY_DIR", IDENTITY, "$dataDir/identity")

    /** The folder of the sign-in server. The orchestrator reads the public key of the server there. */
    fun signIn(dataDir: String): String = resolve("HOMEGAP_SIGN_IN_DIR", SIGN_IN, "$dataDir/sign-in")

    private fun resolve(variable: String, imagePath: String, fallback: String): String =
        env(variable)?.takeIf { it.isNotBlank() } ?: imagePath.takeIf { Files.isDirectory(it) } ?: fallback

    /**
     * True if [path] is in the file system of the container itself, so no other container can
     * see it. False for a volume, and outside a container.
     */
    fun notAVolume(path: String): Boolean {
        val mounts = Files.readTextOrNull("/proc/self/mountinfo") ?: return false
        return mountPoint(path, mounts) == "/"
    }

    /** The mount point of [mountinfo] (the format of `/proc/self/mountinfo`) that holds [path]. */
    fun mountPoint(path: String, mountinfo: String): String? =
        mountinfo.lineSequence()
            .mapNotNull { it.split(" ").getOrNull(4) }
            .filter { it == "/" || path == it || path.startsWith("$it/") }
            .maxByOrNull { it.length }

    /** What to tell the operator when the shared directory [path] is not a volume. */
    fun notAVolumeHint(path: String, folder: String, other: String): String {
        val problem = "$path is not a mount, so the $other container cannot see it."
        val remedy = "Mount the folder $folder of the stack at $path in both containers."
        return "$problem $remedy"
    }
}

val AppConfig.clusterConfigDir: String get() = SharedDirs.clusterConfig(dataDir)

val AppConfig.identityDir: String get() = SharedDirs.identity(dataDir)

val AppConfig.signInDir: String get() = SharedDirs.signIn(dataDir)

/** The private key of the homelab and the users. No other container gets this folder. */
val AppConfig.authorityDir: String get() = "$dataDir/authority"

val AppConfig.meshDir: String get() = SharedDirs.mesh(dataDir)

val AppConfig.tunnelsDir: String get() = tunnels.stateDir ?: SharedDirs.tunnels(dataDir)

/** One line for each shared directory of this config that is not a volume. */
fun AppConfig.sharedDirProblems(): List<String> = buildList {
    fun check(path: String, folder: String, other: String) {
        if (SharedDirs.notAVolume(path)) {
            add(SharedDirs.notAVolumeHint(path, folder = folder, other = other))
        }
    }

    if (nodes.isNotEmpty()) {
        check(tunnelsDir, folder = "data/tunnels", other = "tunnels")
    }
    if (cluster != null) {
        check(meshDir, folder = "data/mesh", other = "homegap-mesh-agent")
        check(clusterConfigDir, folder = "data/cluster/config", other = "cluster")
    }
}
