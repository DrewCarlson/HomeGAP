package homegap.provision

import homegap.config.NodeConfig

/**
 * The node firewall (`ufw`). The add-node wizard and each provisioning pass use the same rules,
 * so the two always agree. Each rule that HomeGAP adds has the comment `homegap`. HomeGAP removes
 * only rules with that comment.
 */
object FirewallRules {
    /** The rules for SSH: from each address (key-only after the hardening) or only from the listed sources. */
    fun sshSpecs(node: NodeConfig): List<String> = when {
        node.sshAllowFrom.isEmpty() -> listOf("allow ${node.sshPort}/tcp")
        else -> node.sshAllowFrom.map { "allow from ${canonical(it)} to any port ${node.sshPort} proto tcp" }
    }

    /**
     * The `ufw` rule specs (the arguments after `ufw`) for [node]. [meshPort] opens the mesh.
     * The raw ports of the services, the port of the VPN and the exit of the node are not here:
     * HomeGAP opens them in its own chains, before `ufw` ([Mesh.forwardScript], [Mesh.exitFirewall]).
     */
    fun specs(node: NodeConfig, meshPort: Int? = null): List<String> = buildList {
        addAll(sshSpecs(node))
        add("allow 80/tcp")
        add("allow 443/tcp")
        add("allow 443/udp")
        // WireGuard authenticates each packet, and the address of the homelab is not fixed.
        meshPort?.let { addAll(meshSpecs(it)) }
    }.distinct()

    /** The rules for the mesh on [port]. No port of a node is open on the mesh interface by `ufw`. */
    fun meshSpecs(port: Int): List<String> = listOf("allow $port/udp")

    /**
     * Applies [specs]. The script adds the wanted rules first, then removes the HomeGAP rules
     * that are not wanted. Thus a change that limits SSH access never drops the current path
     * before the new path exists. With [enable], the script also enables `ufw`.
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
