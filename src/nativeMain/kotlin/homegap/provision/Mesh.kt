package homegap.provision

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
            |command -v wg >/dev/null 2>&1 || { apt-get update -q >/dev/null && apt-get install -yq --no-install-recommends wireguard-tools >/dev/null; }
            |install -d -m 0700 /etc/wireguard
            |if [ ! -s "$KEY" ]; then (umask 077; wg genkey > "$KEY"); echo "CHANGED created the node's mesh key"; fi
            |cat > "$CONF.new" <<'HOMEGAP_MESH'
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
            |if [ -d /run/systemd/system ]; then systemctl enable "wg-quick@$IFACE" >/dev/null 2>&1 || true; fi
            |$${if (openFirewall) OPEN_PORT.replace("@PORT@", mesh.port.toString()) else ":"}
            |echo "PUBKEY=$(wg pubkey < "$KEY")"
            |echo DUMP-BEGIN
            |wg show "$IFACE" dump
            |echo DUMP-END
        """.trimMargin()
    }

    /** Takes a node out of the mesh (decommission): best effort, never fails. */
    val TEARDOWN = $$"""
        |IFACE=$${shQuote(INTERFACE)}
        |if [ -d /run/systemd/system ]; then systemctl disable "wg-quick@$IFACE" >/dev/null 2>&1 || true; fi
        |ip link show "$IFACE" >/dev/null 2>&1 && { wg-quick down "$IFACE" >/dev/null 2>&1 || ip link del "$IFACE"; }
        |rm -f "/etc/wireguard/$IFACE.conf"
        |true
    """.trimMargin()

    private const val OPEN_PORT =
        "if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then " +
            "ufw allow @PORT@/udp comment homegap >/dev/null; ufw allow in on $INTERFACE comment homegap >/dev/null; " +
            "ufw allow in on ${ClusterTemplates.BRIDGE} comment homegap >/dev/null; fi"

    /** One peer line of `wg show <iface> dump`. [latestHandshake] is epoch seconds (0 = never). */
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
