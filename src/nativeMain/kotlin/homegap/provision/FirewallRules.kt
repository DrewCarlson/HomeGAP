package homegap.provision

import homegap.config.NodeConfig
import homegap.model.RawPort

/**
 * The node firewall (`ufw`), shared by the add-node wizard and every provisioning pass so both
 * always agree. Rules HomeGAP adds carry the comment `homegap`; only those are ever removed.
 */
object FirewallRules {
    /** `ufw` rule specs (the arguments after `ufw`) for [node]; [meshPort] opens the cluster mesh. */
    fun specs(node: NodeConfig, rawPorts: List<RawPort> = emptyList(), meshPort: Int? = null): List<String> = buildList {
        // SSH: from anywhere (key-only once hardened) or only from the listed sources.
        if (node.sshAllowFrom.isEmpty()) {
            add("allow ${node.sshPort}/tcp")
        } else {
            node.sshAllowFrom.forEach { add("allow from ${canonical(it)} to any port ${node.sshPort} proto tcp") }
        }
        add("allow 80/tcp")
        add("allow 443/tcp")
        add("allow 443/udp")
        add("allow 51820/udp")
        add("allow 21820/udp")
        // WireGuard authenticates every packet, and the homelab dials in from a changing address.
        meshPort?.let { addAll(meshSpecs(it)) }
        rawPorts.forEach { add("allow ${it.port}/${it.protocol}") }
    }.distinct()

    /**
     * The rules for the cluster mesh on [port]. The other members reach only the cluster ports on
     * the mesh address. The containers of the node (HAProxy, Pangolin) reach them from the cluster bridge.
     */
    fun meshSpecs(port: Int): List<String> = buildList {
        add("allow $port/udp")
        (ClusterTemplates.MESH_PORTS + NodeTemplates.GERBIL_API_PORT).sorted().forEach {
            add("allow in on ${Mesh.INTERFACE} to any port $it proto tcp")
        }
        add("allow in on ${ClusterTemplates.BRIDGE}")
    }

    /**
     * Applies [specs]: adds the wanted rules first (so narrowing SSH access never drops the
     * current path before its replacement exists), then removes HomeGAP rules no longer wanted.
     * With [enable], also turns `ufw` on.
     */
    fun script(specs: List<String>, enable: Boolean = true): String = $$"""
        |export DEBIAN_FRONTEND=noninteractive
        |$${Apt.FUNCTION}
        |command -v ufw >/dev/null || { hg_apt update -q && hg_apt install -yq ufw; } || exit 1
        |ufw default deny incoming >/dev/null
        |ufw default allow outgoing >/dev/null
        |WANTED=$(cat <<'HOMEGAP_RULES'
        |$${specs.joinToString("\n")}
        |HOMEGAP_RULES
        |)
        |printf '%s\n' "$WANTED" | while IFS= read -r spec; do
        |  [ -n "$spec" ] && ufw $spec comment homegap >/dev/null
        |done
        |ufw show added 2>/dev/null | sed -n "s/^ufw \(.*\) comment 'homegap'$/\1/p" | while IFS= read -r spec; do
        |  printf '%s\n' "$WANTED" | grep -qxF "$spec" || { ufw delete $spec >/dev/null && echo "CHANGED ufw removed $spec"; }
        |done
        |$${if (enable) ENABLE else ""}
    """.trimMargin()

    /**
     * Returns [source] as `ufw show added` prints it. The cleanup compares text, so a rule in
     * another form looks unwanted and the cleanup deletes it.
     */
    fun canonical(source: String): String = source.lowercase().removeSuffix("/32").removeSuffix("/128")

    private const val ENABLE =
        "ufw status | grep -q '^Status: active' || { ufw --force enable >/dev/null && echo \"CHANGED ufw enabled\"; }"
}
