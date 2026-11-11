package homegap.config

import homegap.platform.Files
import homegap.platform.env

/**
 * The two directories other containers read. By default they are inside the data directory; the
 * shipped compose file puts each on its own volume (`HOMEGAP_CLUSTER_CONFIG_DIR`,
 * `HOMEGAP_TUNNELS_DIR`), so the cluster containers and the tunnels container never see the
 * state cache, the SSH keys or the dashboard token.
 */
val AppConfig.clusterConfigDir: String
    get() = env("HOMEGAP_CLUSTER_CONFIG_DIR")?.takeIf { it.isNotBlank() } ?: "$dataDir/cluster/config"

/** Connector specs and their status files, shared with the `tunnels` container. */
val AppConfig.tunnelsDir: String
    get() = tunnels.stateDir ?: env("HOMEGAP_TUNNELS_DIR")?.takeIf { it.isNotBlank() } ?: "$dataDir/tunnels"

/**
 * After a move of a shared directory to its own volume: brings over the files the old place
 * holds, so the cluster member keeps its write-once settings and the connectors keep their specs.
 * Only files that the new place does not have yet are copied.
 */
fun AppConfig.adoptSharedDirs() {
    Files.copyMissing("$dataDir/cluster/config", clusterConfigDir)
    Files.copyMissing("$dataDir/tunnels", tunnelsDir)
}
