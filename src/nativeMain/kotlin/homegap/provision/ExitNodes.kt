package homegap.provision

import homegap.clients.SshSession
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.AppConfig
import homegap.model.PrivateResource
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exit nodes (`cluster.exit_nodes`): Pangolin "exit node" resources a client can pick, so all its
 * traffic leaves for the internet there.
 *
 * - A node's exit is its own site, `exit-<node>`, whose Newt connector runs on the node (its own
 *   compose project in `<install_dir>/exit`, on the host network so its outbound connections leave
 *   from the node's address). It talks to the node's Pangolin on loopback, so it needs no
 *   certificate and doesn't depend on the main stack's Docker network.
 * - The homelab's exit lists every node's homelab site: the client relays through the nearest node
 *   and the homelab's connector makes the connections.
 */
object ExitNodes {
    const val NODE_EXIT_PREFIX = "hg-exit-"
    const val HOMELAB_EXIT = "hg-exit-homelab"
    const val DIR = "exit"

    fun siteNiceId(nodeId: String) = "exit-$nodeId"

    /** The Pangolin app shows the resource name; the site name shows in its peer list. */
    fun siteName(nodeId: String) = "exit via $nodeId"

    /**
     * The exit resources: [exitSites] maps each node that has its exit site to it, [homelabSites]
     * every node's homelab site. Only what the config turns on, and only resources with a site.
     */
    fun resources(config: AppConfig, exitSites: Map<String, String>, homelabSites: Map<String, String>): Map<PrivateResource, List<String>> {
        val exits = config.cluster?.exitNodes ?: return emptyMap()
        val out = linkedMapOf<PrivateResource, List<String>>()
        if (exits.nodes) {
            for (n in config.nodes) {
                val site = exitSites[n.id] ?: continue
                out[resource(NODE_EXIT_PREFIX + n.id, "Exit via ${n.id}", site, exits.roles)] = listOf(site)
            }
        }
        if (exits.homelab && homelabSites.isNotEmpty()) {
            val sites = config.nodes.mapNotNull { homelabSites[it.id] }
            out[resource(HOMELAB_EXIT, "Exit via homelab", sites.first(), exits.roles)] = sites
        }
        return out
    }

    /** Every exit resource the config wants, whether or not its site exists yet (never deleted). */
    fun wanted(config: AppConfig): Set<String> {
        val exits = config.cluster?.exitNodes ?: return emptySet()
        return buildSet {
            if (exits.nodes) config.nodes.forEach { add(NODE_EXIT_PREFIX + it.id) }
            if (exits.homelab) add(HOMELAB_EXIT)
        }
    }

    fun isExit(niceId: String) = niceId == HOMELAB_EXIT || niceId.startsWith(NODE_EXIT_PREFIX)

    private fun resource(niceId: String, name: String, site: String, roles: List<String>) = PrivateResource(
        niceId = niceId, serviceKey = "exit", name = name, siteNiceId = site, destination = "", alias = "",
        tcpPorts = "", udpPorts = "", roles = roles, paused = false, mode = "exit-node",
    )

    fun compose(image: String): String = YamlEmitter.emit(
        buildJsonObject {
            put("name", "homegap-exit")
            putJsonObject("services") {
                putJsonObject("newt") {
                    put("image", image)
                    put("restart", "unless-stopped")
                    // Outbound connections leave from the node's own address, and Pangolin is on loopback.
                    put("network_mode", "host")
                    putJsonArray("env_file") { add(".env") }
                }
            }
        },
        "Managed by HomeGAP: this node's internet exit (cluster.exit_nodes.nodes).",
    )

    fun env(newtId: String, newtSecret: String): String =
        "PANGOLIN_ENDPOINT=http://127.0.0.1:3000\nNEWT_ID=$newtId\nNEWT_SECRET=$newtSecret\n"

    /** Writes the project and (re)starts it when anything changed. Prints `CHANGED …` lines. */
    fun deployScript(installDir: String, image: String, newtId: String, newtSecret: String): String {
        val dir = "$installDir/$DIR"
        return listOf(
            "command -v docker >/dev/null 2>&1 || { echo WAITING-FOR-DOCKER; exit 0; }",
            "set -e",
            "install -d -m 0700 ${shQuote(dir)}",
            "OUT=$( {",
            SshSession.writeFileSnippet("$dir/compose.yml", compose(image)),
            SshSession.writeFileSnippet("$dir/.env", env(newtId, newtSecret), "0600"),
            "} )",
            "cd ${shQuote(dir)}",
            "if [ -n \"\$OUT\" ] || [ -z \"\$(docker compose ps --status running -q newt 2>/dev/null)\" ]; then",
            "  docker compose up -d --remove-orphans >/dev/null 2>&1 || docker compose up -d --remove-orphans",
            "  echo 'CHANGED exit connector running'",
            "fi",
        ).joinToString("\n")
    }

    fun teardownScript(installDir: String): String {
        val dir = shQuote("$installDir/$DIR")
        return "if [ -d $dir ]; then (cd $dir && docker compose down --remove-orphans >/dev/null 2>&1 || true); rm -rf $dir; echo 'CHANGED exit connector removed'; fi"
    }
}
