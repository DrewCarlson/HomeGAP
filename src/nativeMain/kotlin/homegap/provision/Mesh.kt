package homegap.provision

import kotlinx.serialization.Serializable
import homegap.clients.SshSession.Companion.shQuote
import homegap.config.MeshConfig

/**
 * The cluster's private WireGuard mesh: the homelab and every node, each peered with all others.
 *
 * - Addresses are fixed: the homelab is host 1 of the subnet and a node is host `10 + slot`, its
 *   permanent tunnel slot, so an address never moves to another node.
 * - Each node generates its own key on first setup; the private key never leaves the node, and
 *   HomeGAP learns the public key from the setup script's output.
 * - The homelab has no public endpoint: it dials every node and keeps the paths alive, so it works
 *   behind NAT. Nodes reach each other directly on their public addresses.
 */
object Mesh {
    const val INTERFACE = "hg-mesh"
    const val HOMELAB = "homelab"
    private const val HOMELAB_HOST = 1
    private const val NODE_HOST_BASE = 10
    private const val KEEPALIVE_SECONDS = 25

    /** Stands in for the node's private key in the rendered config; replaced on the node itself. */
    const val KEY_PLACEHOLDER = "@@HOMEGAP_MESH_PRIVATE_KEY@@"

    /** An IPv4 subnet such as `10.77.0.0/24`. */
    data class Subnet(val base: Long, val prefix: Int) {
        private val size: Long get() = 1L shl (32 - prefix)

        /** The [n]th host address (n >= 1). */
        fun host(n: Int): String {
            require(n in 1 until size - 1) { "host $n is outside /$prefix" }
            return format(base + n)
        }

        /** Room for the homelab and [nodes] node slots. */
        fun fits(nodes: Int): Boolean = NODE_HOST_BASE + nodes < size - 1

        /** Overlaps 100.64.0.0/10, where Pangolin allocates its tunnel and client subnets. */
        fun overlapsCgnat(): Boolean {
            val cgnat = parseIp("100.64.0.0")
            val cgnatEnd = cgnat + (1L shl 22) - 1
            return base <= cgnatEnd && base + size - 1 >= cgnat
        }

        override fun toString() = "${format(base)}/$prefix"
    }

    fun parseSubnet(cidr: String): Subnet {
        val (ip, bits) = cidr.split('/').takeIf { it.size == 2 } ?: throw IllegalArgumentException("'$cidr' is not an IPv4 CIDR")
        val prefix = bits.toIntOrNull()?.takeIf { it in 16..28 } ?: throw IllegalArgumentException("prefix must be /16 to /28")
        val base = parseIp(ip)
        require(base and ((1L shl (32 - prefix)) - 1) == 0L) { "'$cidr' is not a network address (host bits set)" }
        return Subnet(base, prefix)
    }

    fun homelabAddress(mesh: MeshConfig): String = parseSubnet(mesh.subnet).host(HOMELAB_HOST)

    fun nodeAddress(mesh: MeshConfig, slot: Int): String = parseSubnet(mesh.subnet).host(NODE_HOST_BASE + slot)

    /** Another member as seen from one member: [endpoint] is null for the homelab (it dials out). */
    @Serializable
    data class Peer(val id: String, val publicKey: String, val address: String, val endpoint: String?)

    /**
     * A node's `wg-quick` config. The private key is [KEY_PLACEHOLDER], filled in on the node.
     * Peers with an endpoint get a keepalive; the homelab keeps its own paths alive.
     */
    fun nodeConfig(mesh: MeshConfig, address: String, peers: List<Peer>): String = buildString {
        val prefix = parseSubnet(mesh.subnet).prefix
        appendLine("# Managed by HomeGAP: the cluster mesh. Changes are overwritten.")
        appendLine("[Interface]")
        appendLine("Address = $address/$prefix")
        appendLine("ListenPort = ${mesh.port}")
        appendLine("PrivateKey = $KEY_PLACEHOLDER")
        // At boot, before Docker starts and makes its rules.
        appendLine("PostUp = $GUARD_COMMAND")
        for (p in peers.sortedBy { it.id }) {
            appendLine()
            appendLine("# ${p.id}")
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.publicKey}")
            appendLine("AllowedIPs = ${p.address}/32")
            p.endpoint?.let {
                appendLine("Endpoint = ${endpointHost(it)}:${mesh.port}")
                appendLine("PersistentKeepalive = $KEEPALIVE_SECONDS")
            }
        }
    }

    /** The homelab's config in `wg` (not `wg-quick`) format, for `wg syncconf`. */
    fun homelabConfig(mesh: MeshConfig, privateKey: String, peers: List<Peer>): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("ListenPort = ${mesh.port}")
        for (p in peers.sortedBy { it.id }) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.publicKey}")
            appendLine("AllowedIPs = ${p.address}/32")
            p.endpoint?.let { appendLine("Endpoint = ${endpointHost(it)}:${mesh.port}") }
            appendLine("PersistentKeepalive = $KEEPALIVE_SECONDS")
        }
    }

    /**
     * Brings a node's mesh up to [config] (from [nodeConfig]): installs wireguard-tools, creates
     * the node's key once, writes the config, applies peer changes in place (`wg syncconf`, so
     * existing sessions survive) and enables it at boot where systemd runs. With [openFirewall],
     * also opens the mesh port in an active ufw. Prints `PUBKEY=…` and the interface's peer dump
     * between `DUMP-BEGIN` and `DUMP-END`.
     */
    fun nodeScript(mesh: MeshConfig, address: String, config: String, openFirewall: Boolean): String {
        val prefix = parseSubnet(mesh.subnet).prefix
        return $$"""
            |IFACE=$${shQuote(INTERFACE)}
            |CONF=/etc/wireguard/$IFACE.conf
            |KEY=/etc/wireguard/$IFACE.key
            |ADDR=$${shQuote("$address/$prefix")}
            |export DEBIAN_FRONTEND=noninteractive
            |$${Apt.FUNCTION}
            |# The node's first provisioning may be installing Docker at the same time: wait for apt.
            |command -v wg >/dev/null 2>&1 || { hg_apt update -q && hg_apt install -yq --no-install-recommends wireguard-tools; } || exit 1
            |install -d -m 0700 /etc/wireguard
            |if [ ! -s "$KEY" ]; then (umask 077; wg genkey > "$KEY"); echo "CHANGED created the node's mesh key"; fi
            |(umask 077; cat > "$CONF.new") <<'HOMEGAP_MESH'
            |$${config.trimEnd()}
            |HOMEGAP_MESH
            |sed -i "s|$${KEY_PLACEHOLDER}|$(cat "$KEY")|" "$CONF.new"
            |chmod 0600 "$CONF.new"
            |UPDATED=no
            |if cmp -s "$CONF.new" "$CONF"; then rm -f "$CONF.new"; else mv -f "$CONF.new" "$CONF"; UPDATED=yes; fi
            |if ip link show "$IFACE" >/dev/null 2>&1; then
            |  if [ "$UPDATED" = yes ]; then wg syncconf "$IFACE" <(wg-quick strip "$IFACE"); echo "CHANGED mesh peers updated"; fi
            |  if ! ip -4 addr show dev "$IFACE" | grep -q "inet $ADDR "; then
            |    ip -4 addr flush dev "$IFACE"
            |    ip addr add "$ADDR" dev "$IFACE"
            |    echo "CHANGED mesh address set to $ADDR"
            |  fi
            |else
            |  wg-quick up "$IFACE" >/dev/null
            |  echo "CHANGED mesh interface up at $ADDR"
            |fi
            |if [ -d /run/systemd/system ]; then
            |  systemctl enable "wg-quick@$IFACE" >/dev/null 2>&1 || true
            |  # Containers publish ports on the mesh address (Gerbil's API), so Docker starts after it.
            |  DROPIN=$${shQuote(DOCKER_DROPIN)}
            |  WANT="$(printf '[Unit]\nWants=wg-quick@%s.service\nAfter=wg-quick@%s.service' "$IFACE" "$IFACE")"
            |  if [ "$(cat "$DROPIN" 2>/dev/null)" != "$WANT" ]; then
            |    install -d "$(dirname "$DROPIN")"; printf '%s\n' "$WANT" > "$DROPIN"; systemctl daemon-reload
            |    echo "CHANGED Docker now starts after the mesh interface"
            |  fi
            |fi
            |$${if (openFirewall) firewallStep(mesh.port) else ":"}
            |echo "PUBKEY=$(wg pubkey < "$KEY")"
            |echo DUMP-BEGIN
            |wg show "$IFACE" dump
            |echo DUMP-END
        """.trimMargin()
    }

    /** Takes a node out of the mesh (decommission): best effort, never fails. */
    val TEARDOWN = $$"""
        |IFACE=$${shQuote(INTERFACE)}
        |if [ -d /run/systemd/system ]; then
        |  systemctl disable "wg-quick@$IFACE" >/dev/null 2>&1 || true
        |  if [ -f $${shQuote(DOCKER_DROPIN)} ]; then rm -f $${shQuote(DOCKER_DROPIN)}; systemctl daemon-reload; fi
        |fi
        |ip link show "$IFACE" >/dev/null 2>&1 && { wg-quick down "$IFACE" >/dev/null 2>&1 || ip link del "$IFACE"; }
        |rm -f "/etc/wireguard/$IFACE.conf"
        |true
    """.trimMargin()

    private const val DOCKER_DROPIN = "/etc/systemd/system/docker.service.d/10-homegap-mesh.conf"

    /**
     * Adds the rule of [FirewallRules.containerGuard] if it is not there. Docker keeps the chain.
     * A host can have the nftables and the legacy rule sets, and Docker uses one of them, so the
     * rule goes into each set that has the chain.
     */
    private val GUARD_COMMAND =
        "iptables -N DOCKER-USER 2>/dev/null || true; for t in iptables iptables-legacy; do \$t -S DOCKER-USER >/dev/null 2>&1 || continue; " +
            "\$t -C ${FirewallRules.containerGuard} 2>/dev/null || \$t -I ${FirewallRules.containerGuard} || true; done"

    /**
     * Opens the mesh in an active ufw, and removes each HomeGAP rule for the mesh interface or the
     * cluster bridge that is not wanted, so a rule that admits more does not stay.
     */
    private fun firewallStep(port: Int): String {
        val wanted = FirewallRules.meshSpecs(port)
        return $$"""
            |$$GUARD_COMMAND
            |if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
            |  WANTED=$(cat <<'HOMEGAP_MESH_RULES'
            |$${wanted.joinToString("\n")}
            |HOMEGAP_MESH_RULES
            |)
            |  printf '%s\n' "$WANTED" | while IFS= read -r spec; do ufw $spec comment homegap >/dev/null; done
            |  ufw show added 2>/dev/null | sed -n "s/^ufw \(.*\) comment 'homegap'$/\1/p" | { grep -E ' on ($${INTERFACE}|$${ClusterTemplates.BRIDGE})( |$)' || true; } | while IFS= read -r spec; do
            |    printf '%s\n' "$WANTED" | grep -qxF "$spec" || { ufw delete $spec >/dev/null && echo "CHANGED ufw removed $spec"; }
            |  done
            |fi
        """.trimMargin()
    }

    /** What the orchestrator writes for the homelab's mesh agent: the interface it keeps. */
    const val SPEC_FILE = "mesh.json"

    /** What the mesh agent writes back: its key, what it applied and the peers it sees. */
    const val STATUS_FILE = "status.json"

    /** The homelab's mesh as the agent applies it. [openPorts] are the member's ports others may reach. */
    @Serializable
    data class AgentSpec(
        val subnet: String,
        val port: Int,
        val address: String,
        val peers: List<Peer>,
        val openPorts: List<Int>,
    )

    @Serializable
    data class AgentStatus(
        val publicKey: String? = null,
        /** [specHash] of the spec that is in effect; null before the first one. */
        val applied: String? = null,
        val peers: List<PeerStatus>? = null,
        val error: String? = null,
        val at: Long = 0,
    )

    fun specHash(specText: String): String = homegap.platform.Crypto.fnv64(specText)

    /** The built-in chains that pass through HomeGAP's own: table, chain, target. */
    val FIREWALL_JUMPS = listOf(
        Triple("filter", "INPUT", "HG-IN"),
        Triple("filter", "OUTPUT", "HG-OUT"),
        Triple("filter", "FORWARD", "HG-FWD"),
        Triple("nat", "POSTROUTING", "HG-NAT"),
    )

    /**
     * The firewall of the homelab's mesh namespace, as `iptables-restore --noflush` input (naming a
     * chain there empties it, so the whole set is replaced in one step). Everything is refused
     * except:
     * - in: answers, ping, and the member's [AgentSpec.openPorts] (from the nodes over the mesh,
     *   and from the orchestrator over the network the two share);
     * - out: the mesh, answers, and WireGuard itself to the nodes. Nothing in the namespace can
     *   open a connection to the orchestrator, another container or the home network;
     * - through: the orchestrator reaching the members over the mesh (leaving as the homelab's
     *   mesh address), and the answers. A node is never passed on to anything.
     */
    fun firewallRules(spec: AgentSpec): String = buildString {
        val ports = spec.openPorts.joinToString(",")
        appendLine("*filter")
        appendLine(":HG-IN - [0:0]")
        appendLine(":HG-OUT - [0:0]")
        appendLine(":HG-FWD - [0:0]")
        appendLine("-A HG-IN -i lo -j ACCEPT")
        appendLine("-A HG-IN -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-IN -p icmp -j ACCEPT")
        if (ports.isNotEmpty()) appendLine("-A HG-IN -p tcp -m multiport --dports $ports -j ACCEPT")
        appendLine("-A HG-IN -j DROP")
        appendLine("-A HG-OUT -o lo -j ACCEPT")
        appendLine("-A HG-OUT -o $INTERFACE -j ACCEPT")
        appendLine("-A HG-OUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-OUT -p udp --dport ${spec.port} -j ACCEPT")
        appendLine("-A HG-OUT -j DROP")
        appendLine("-A HG-FWD ! -i $INTERFACE -o $INTERFACE -j ACCEPT")
        appendLine("-A HG-FWD -i $INTERFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-FWD -j DROP")
        appendLine("COMMIT")
        appendLine("*nat")
        appendLine(":HG-NAT - [0:0]")
        appendLine("-A HG-NAT -o $INTERFACE -j MASQUERADE")
        appendLine("COMMIT")
    }

    /** One peer line of `wg show <iface> dump`. [latestHandshake] is epoch seconds (0 = never). */
    @Serializable
    data class PeerStatus(val publicKey: String, val endpoint: String?, val latestHandshake: Long, val rxBytes: Long, val txBytes: Long)

    /** Peers from `wg show <iface> dump` (the first line describes the interface itself). */
    fun parseDump(dump: String): List<PeerStatus> =
        dump.lines().drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 8) return@mapNotNull null
            PeerStatus(
                publicKey = f[0],
                endpoint = f[2].takeIf { it != "(none)" },
                latestHandshake = f[4].toLongOrNull() ?: 0,
                rxBytes = f[5].toLongOrNull() ?: 0,
                txBytes = f[6].toLongOrNull() ?: 0,
            )
        }

    /** The text between `DUMP-BEGIN` and `DUMP-END` in a node script's output. */
    fun dumpSection(output: String): String =
        output.substringAfter("DUMP-BEGIN\n", "").substringBefore("DUMP-END", "").trimEnd()

    /** IPv6 endpoints need brackets before the port. */
    private fun endpointHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

    private fun parseIp(ip: String): Long {
        val parts = ip.split('.').map { it.toIntOrNull() ?: -1 }
        require(parts.size == 4 && parts.all { it in 0..255 }) { "'$ip' is not an IPv4 address" }
        return parts.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
    }

    private fun format(v: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 0xFF).toString() }
}
