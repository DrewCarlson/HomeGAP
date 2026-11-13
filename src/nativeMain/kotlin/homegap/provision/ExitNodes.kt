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
 * Exit nodes (`cluster.exit_nodes`): Pangolin "exit node" resources that a client can select. All
 * the traffic of the client then goes to the internet from that location.
 *
 * - The exit of a node is its own site, `exit-<node>`. The Newt connector of the site runs on the
 *   node, as its own compose project in `<install_dir>/exit`. It uses the host network, so its
 *   outbound connections come from the address of the node. It connects to the Pangolin of the
 *   node on loopback, so it needs no certificate and no Docker network of the main stack.
 * - The exit of the homelab lists the homelab site of each node. The client goes through the
 *   nearest node, and the connector of the homelab makes the connections.
 */
object ExitNodes {
    const val NODE_EXIT_PREFIX = "hg-exit-"
    const val HOMELAB_EXIT = "hg-exit-homelab"
    const val DIR = "exit"

    fun siteNiceId(nodeId: String) = "exit-$nodeId"

    /** The Pangolin app shows the resource name. The site name is in the peer list of the app. */
    fun siteName(nodeId: String) = "exit via $nodeId"

    /**
     * The exit resources, each with its sites. [exitSites] gives the exit site of each node that
     * has one. [homelabSites] gives the homelab site of each node. The result has only the exits
     * that the config enables, and only resources with a site.
     */
    fun resources(
        config: AppConfig,
        exitSites: Map<String, String>,
        homelabSites: Map<String, String>,
    ): Map<PrivateResource, List<String>> {
        val exits = config.cluster?.exitNodes ?: return emptyMap()
        val resources = linkedMapOf<PrivateResource, List<String>>()
        if (exits.nodes) {
            for (node in config.nodes) {
                val site = exitSites[node.id] ?: continue
                val resource = resource(NODE_EXIT_PREFIX + node.id, "Exit via ${node.id}", site, exits.roles)
                resources[resource] = listOf(site)
            }
        }
        // A site of a node that left the config does not count.
        val sites = config.nodes.mapNotNull { homelabSites[it.id] }
        if (exits.homelab && sites.isNotEmpty()) {
            resources[resource(HOMELAB_EXIT, "Exit via homelab", sites.first(), exits.roles)] = sites
        }
        return resources
    }

    /** Each exit resource that the config wants, also if its site does not exist yet. */
    fun wanted(config: AppConfig): Set<String> {
        val exits = config.cluster?.exitNodes ?: return emptySet()
        return buildSet {
            if (exits.nodes) config.nodes.forEach { add(NODE_EXIT_PREFIX + it.id) }
            if (exits.homelab) add(HOMELAB_EXIT)
        }
    }

    private fun resource(niceId: String, name: String, site: String, roles: List<String>) = PrivateResource(
        niceId = niceId,
        serviceKey = "exit",
        name = name,
        siteNiceId = site,
        destination = "",
        alias = "",
        tcpPorts = "",
        udpPorts = "",
        roles = roles,
        paused = false,
        mode = "exit-node",
    )

    /**
     * [pins] has a (host, address) pair for the management host of the node. Pangolin gives that
     * host to the connector as its WireGuard endpoint. The pin is the address that HomeGAP
     * publishes in DNS, so the connector reaches this node also while public DNS has an older answer.
     */
    fun compose(image: String, pins: List<Pair<String, String>> = emptyList()): String = YamlEmitter.emit(
        buildJsonObject {
            put("name", "homegap-exit")
            putJsonObject("services") {
                putJsonObject("newt") {
                    put("image", image)
                    put("restart", "unless-stopped")
                    // Outbound connections come from the address of the node, and Pangolin is on loopback.
                    put("network_mode", "host")
                    putJsonArray("env_file") { add(".env") }
                    putJsonArray("extra_hosts") {
                        pins.forEach { (host, ip) -> add("$host=$ip") }
                        OutsideCalls.NEWT.forEach { add("$it=${OutsideCalls.NOWHERE}") }
                    }
                }
            }
        },
        "Managed by HomeGAP: this node's internet exit (cluster.exit_nodes.nodes).",
    )

    fun env(newtId: String, newtSecret: String): String =
        "PANGOLIN_ENDPOINT=http://127.0.0.1:3000\nNEWT_ID=$newtId\nNEWT_SECRET=$newtSecret\n"

    /** Writes the project and starts it again after a change. Prints `CHANGED …` lines. */
    fun deployScript(
        installDir: String,
        image: String,
        newtId: String,
        newtSecret: String,
        pins: List<Pair<String, String>> = emptyList(),
    ): String {
        val dir = "$installDir/$DIR"
        return listOf(
            "type -P docker >/dev/null 2>&1 || { echo WAITING-FOR-DOCKER; exit 0; }",
            "set -e",
            "install -d -m 0700 ${shQuote(dir)}",
            "OUT=$( {",
            SshSession.writeFileSnippet("$dir/compose.yml", compose(image, pins)),
            SshSession.writeFileSnippet("$dir/.env", env(newtId, newtSecret), "0600"),
            "} )",
            "hg_own ${shQuote(dir)}",
            "cd ${shQuote(dir)}",
            $$"if [ -n \"$OUT\" ] || [ -z \"$(docker compose ps --status running -q newt 2>/dev/null)\" ]; then",
            "  docker compose up -d --remove-orphans >/dev/null 2>&1 || docker compose up -d --remove-orphans",
            "  echo 'CHANGED exit connector running'",
            "fi",
        ).joinToString("\n")
    }

    fun teardownScript(installDir: String): String {
        val dir = shQuote("$installDir/$DIR")
        val stopProject = "(cd $dir && docker compose down --remove-orphans >/dev/null 2>&1 || true)"
        return "if [ -d $dir ]; then $stopProject; rm -rf $dir; echo 'CHANGED exit connector removed'; fi"
    }
}
