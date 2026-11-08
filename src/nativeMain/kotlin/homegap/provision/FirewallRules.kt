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
            node.sshAllowFrom.forEach { add("allow from $it to any port ${node.sshPort} proto tcp") }
        }
        add("allow 80/tcp")
        add("allow 443/tcp")
        add("allow 443/udp")
        add("allow 51820/udp")
        add("allow 21820/udp")
        // WireGuard authenticates every packet, and the homelab dials in from a changing address.
        meshPort?.let {
            add("allow $it/udp")
            // Cluster services listen on the mesh address; only mesh members can reach it, and
            // the node's own containers on the cluster network (HAProxy, Pangolin).
            add("allow in on ${Mesh.INTERFACE}")
            add("allow in on ${ClusterTemplates.BRIDGE}")
        }
        rawPorts.forEach { add("allow ${it.port}/${it.protocol}") }
    }.distinct()

    /**
     * Applies [specs]: adds the wanted rules first (so narrowing SSH access never drops the
     * current path before its replacement exists), then removes HomeGAP rules no longer wanted.
     * With [enable], also turns `ufw` on.
     */
    fun script(specs: List<String>, enable: Boolean = true): String = $$"""
        |export DEBIAN_FRONTEND=noninteractive
        |command -v ufw >/dev/null || { apt-get update -q >/dev/null && apt-get install -yq ufw >/dev/null; }
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

    private const val ENABLE =
        "ufw status | grep -q '^Status: active' || { ufw --force enable >/dev/null && echo \"CHANGED ufw enabled\"; }"
}
