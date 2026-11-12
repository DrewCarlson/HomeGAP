package homegap.config

import homegap.platform.Files
import homegap.platform.env

/**
 * The directories that the orchestrator shares with other containers. Each has a fixed path in
 * the HomeGAP image, so a compose file only mounts a volume there. Each is a volume of its own,
 * so the other container never sees the state cache, the SSH keys or the dashboard token.
 *
 * Outside the image the fixed path is not there, and the directory is inside the data directory.
 */
object SharedDirs {
    const val MESH = "/mesh"
    const val TUNNELS = "/tunnels"
    const val CLUSTER_CONFIG = "/cluster-config"

    /** What the orchestrator and the mesh agent of the homelab (`homegap mesh`) tell each other. */
    fun mesh(dataDir: String): String = resolve("HOMEGAP_MESH_DIR", MESH, "$dataDir/mesh")

    /** Connector specs and their status files, shared with the `tunnels` container. */
    fun tunnels(dataDir: String): String = resolve("HOMEGAP_TUNNELS_DIR", TUNNELS, "$dataDir/tunnels")

    /** The config of the cluster containers of the homelab, which read it. */
    fun clusterConfig(dataDir: String): String = resolve("HOMEGAP_CLUSTER_CONFIG_DIR", CLUSTER_CONFIG, "$dataDir/cluster/config")

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
    fun notAVolumeHint(path: String, volume: String, other: String): String =
        "$path is not a volume, so the $other container cannot see it. Mount the $volume volume at $path in both containers."
}

val AppConfig.clusterConfigDir: String get() = SharedDirs.clusterConfig(dataDir)

val AppConfig.meshDir: String get() = SharedDirs.mesh(dataDir)

val AppConfig.tunnelsDir: String get() = tunnels.stateDir ?: SharedDirs.tunnels(dataDir)

/**
 * After a move of a shared directory to its own volume: brings over the files the old place
 * holds, so the cluster member keeps its write-once settings and the connectors keep their specs.
 * Only files that the new place does not have yet are copied.
 */
fun AppConfig.adoptSharedDirs() {
    Files.copyMissing("$dataDir/cluster/config", clusterConfigDir)
    Files.copyMissing("$dataDir/tunnels", tunnelsDir)
}

/** One line for each shared directory of this config that is not a volume. */
fun AppConfig.sharedDirProblems(): List<String> = buildList {
    if (nodes.isNotEmpty() && SharedDirs.notAVolume(tunnelsDir)) add(SharedDirs.notAVolumeHint(tunnelsDir, "homegap-tunnels", "tunnels"))
    if (cluster != null) {
        if (SharedDirs.notAVolume(meshDir)) add(SharedDirs.notAVolumeHint(meshDir, "homegap-mesh", "homegap-mesh-agent"))
        if (SharedDirs.notAVolume(clusterConfigDir)) add(SharedDirs.notAVolumeHint(clusterConfigDir, "homegap-cluster-config", "cluster"))
    }
}
