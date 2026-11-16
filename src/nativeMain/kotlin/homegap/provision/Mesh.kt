package homegap.provision

import homegap.clients.SshSession.Companion.shQuote
import homegap.config.MeshConfig
import homegap.model.RawPort
import homegap.platform.Crypto
import kotlinx.serialization.Serializable

/**
 * The private WireGuard mesh: the homelab and each node. Each member is a peer of
 * all the other members.
 *
 * - The addresses are fixed. The homelab is host 1 of the subnet. A node is host `10 + slot`,
 *   where the slot is its permanent slot, so an address never moves to another node.
 * - Each node makes its own key during its first setup. The private key stays on the node.
 *   HomeGAP learns the public key from the output of the setup script.
 * - The homelab has no public endpoint. It connects to each node and keeps the paths open, so it
 *   works behind NAT. The nodes reach each other directly on their public addresses.
 */
object Mesh {
    const val INTERFACE = "hg-mesh"
    const val HOMELAB = "homelab"
    private const val HOMELAB_HOST = 1
    private const val NODE_HOST_BASE = 10
    private const val KEEPALIVE_SECONDS = 25
    private const val IPV4_BITS = 32
    private const val TCP = "tcp"
    private const val MIN_PREFIX = 16
    private const val MAX_PREFIX = 28

    /** The number of fields in a peer line of `wg show <iface> dump`. */
    private const val DUMP_PEER_FIELDS = 8

    /** The rendered config has this text in the place of the private key. The node puts its key there. */
    const val KEY_PLACEHOLDER = "@@HOMEGAP_MESH_PRIVATE_KEY@@"

    /** An IPv4 subnet such as `10.77.0.0/24`. */
    data class Subnet(val base: Long, val prefix: Int) {
        private val size: Long get() = 1L shl (IPV4_BITS - prefix)

        /** The host address with the number [number] (1 or more). */
        fun host(number: Int): String {
            require(number in 1 until size - 1) { "host $number is outside /$prefix" }
            return format(base + number)
        }

        /** Returns true if the subnet has room for the homelab and [nodes] node slots. */
        fun fits(nodes: Int): Boolean = NODE_HOST_BASE + nodes < size - 1

        /** Returns true if the subnet overlaps 100.64.0.0/10. The devices of the VPN have their addresses in it. */
        fun overlapsCgnat(): Boolean {
            val cgnat = parseIp("100.64.0.0")
            val cgnatEnd = cgnat + (1L shl 22) - 1
            return base <= cgnatEnd && base + size - 1 >= cgnat
        }

        override fun toString() = "${format(base)}/$prefix"
    }

    fun parseSubnet(cidr: String): Subnet {
        val parts = cidr.split('/')
        require(parts.size == 2) { "'$cidr' is not an IPv4 CIDR" }
        val (ip, bits) = parts
        val prefix = bits.toIntOrNull()?.takeIf { it in MIN_PREFIX..MAX_PREFIX }
        require(prefix != null) { "prefix must be /16 to /28" }
        val base = parseIp(ip)
        val hostBits = base and ((1L shl (IPV4_BITS - prefix)) - 1)
        require(hostBits == 0L) { "'$cidr' is not a network address (host bits set)" }
        return Subnet(base, prefix)
    }

    fun homelabAddress(mesh: MeshConfig): String = parseSubnet(mesh.subnet).host(HOMELAB_HOST)

    fun nodeAddress(mesh: MeshConfig, slot: Int): String = parseSubnet(mesh.subnet).host(NODE_HOST_BASE + slot)

    /** Another member, as one member sees it. [endpoint] is null for the homelab, which connects out. */
    @Serializable
    data class Peer(val id: String, val publicKey: String, val address: String, val endpoint: String?)

    /**
     * The `wg-quick` config of a node. The private key is [KEY_PLACEHOLDER], and the node puts
     * its key there. A peer with an endpoint gets a keepalive. The homelab keeps its own paths open.
     */
    fun nodeConfig(mesh: MeshConfig, address: String, peers: List<Peer>): String = buildString {
        val prefix = parseSubnet(mesh.subnet).prefix
        appendLine("# Managed by HomeGAP: the mesh. Changes are overwritten.")
        appendLine("[Interface]")
        appendLine("Address = $address/$prefix")
        appendLine("ListenPort = ${mesh.port}")
        appendLine("PrivateKey = $KEY_PLACEHOLDER")
        // At boot, before Docker starts and makes its rules.
        appendLine("PostUp = [ ! -x $FORWARDS_FILE ] || $FORWARDS_FILE")
        for (peer in peers.sortedBy { it.id }) {
            appendLine()
            appendLine("# ${peer.id}")
            appendPeerSection(peer)
            peer.endpoint?.let {
                appendLine("Endpoint = ${endpointHost(it)}:${mesh.port}")
                appendLine("PersistentKeepalive = $KEEPALIVE_SECONDS")
            }
        }
    }

    /** The config of the homelab in `wg` (not `wg-quick`) format, for `wg syncconf`. */
    fun homelabConfig(mesh: MeshConfig, privateKey: String, peers: List<Peer>): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("ListenPort = ${mesh.port}")
        for (peer in peers.sortedBy { it.id }) {
            appendLine()
            appendPeerSection(peer)
            peer.endpoint?.let { appendLine("Endpoint = ${endpointHost(it)}:${mesh.port}") }
            appendLine("PersistentKeepalive = $KEEPALIVE_SECONDS")
        }
    }

    private fun StringBuilder.appendPeerSection(peer: Peer) {
        appendLine("[Peer]")
        appendLine("PublicKey = ${peer.publicKey}")
        appendLine("AllowedIPs = ${peer.address}/32")
    }

    /**
     * Makes the mesh of a node agree with [config] (from [nodeConfig]). The script installs
     * wireguard-tools, makes the key of the node once and writes the config. It applies peer
     * changes in place with `wg syncconf`, so the current sessions stay. Where systemd runs, it
     * enables the interface at boot. With [openFirewall], it also opens the mesh port in an
     * active ufw. It prints `PUBKEY=…`, and the peer dump of the interface between `DUMP-BEGIN`
     * and `DUMP-END`.
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
            |  # Containers publish ports on the mesh address, so Docker starts after it.
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

    /**
     * The script that makes a node forward its raw [ports] in the kernel to the same port of
     * [homelabAddress], over the mesh. One step replaces the full set of rules. The script also
     * runs at boot, from the `PostUp` of the interface.
     * - `HG-RAW` changes the destination of a packet for a public port of the node itself.
     * - `HG-RAW-FWD` is the first rule of `FORWARD`, before the rules of Docker and of `ufw`. It
     *   passes only a connection that `HG-RAW` changed, and the answers.
     * - `HG-RAW-NAT` gives the packet the mesh address of the node, which is the only source
     *   that the homelab accepts from the node.
     * - `HG-RAW-IN` is the first rule of `INPUT`, before the rules of `ufw`. It accepts the TCP
     *   ports that the agent of the node serves ([agentPorts]). The firewall of the node thus
     *   follows a new port at once.
     */
    fun forwardScript(
        homelabAddress: String,
        ports: List<RawPort>,
        agentPorts: List<Int> = emptyList(),
    ): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Managed by HomeGAP: the raw ports that this node forwards. Changes are overwritten.")
        appendLine("iptables-restore --noflush <<'HOMEGAP_RULES'")
        appendLine("*nat")
        appendLine(":HG-RAW - [0:0]")
        appendLine(":HG-RAW-NAT - [0:0]")
        for ((port, protocol) in ports.sorted()) {
            val match = "! -i $INTERFACE -p $protocol -m addrtype --dst-type LOCAL --dport $port"
            appendLine("-A HG-RAW $match -j DNAT --to-destination $homelabAddress:$port")
        }
        appendLine("-A HG-RAW-NAT -o $INTERFACE -m conntrack --ctstate DNAT -j MASQUERADE")
        appendLine("COMMIT")
        appendLine("*filter")
        appendLine(":HG-RAW-FWD - [0:0]")
        appendLine("-A HG-RAW-FWD -o $INTERFACE -m conntrack --ctstate DNAT -j ACCEPT")
        appendLine("-A HG-RAW-FWD -i $INTERFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine(":HG-RAW-IN - [0:0]")
        agentPorts.sorted().forEach { appendLine("-A HG-RAW-IN ! -i $INTERFACE -p tcp --dport $it -j ACCEPT") }
        appendLine("COMMIT")
        appendLine("HOMEGAP_RULES")
        for ((table, chain, target) in NODE_FORWARD_JUMPS) {
            val iptables = "iptables -t $table"
            appendLine("$iptables -C $chain -j $target 2>/dev/null || $iptables -I $chain 1 -j $target")
        }
    }

    /** Writes [script] (from [forwardScript]) to the node. Runs it after a change, or if a jump is not there. */
    fun forwardStep(script: String): String {
        val jumps = NODE_FORWARD_JUMPS.joinToString(" && ") { (table, chain, target) ->
            "iptables -t $table -C $chain -j $target 2>/dev/null"
        }
        return $$"""
            |FORWARDS=$${shQuote(FORWARDS_FILE)}
            |install -d -m 0700 /etc/wireguard
            |(umask 077; cat > "$FORWARDS.new") <<'HOMEGAP_FORWARDS'
            |$${script.trimEnd()}
            |HOMEGAP_FORWARDS
            |chmod 0700 "$FORWARDS.new"
            |if cmp -s "$FORWARDS.new" "$FORWARDS"; then
            |  rm -f "$FORWARDS.new"
            |  { $$jumps; } || { "$FORWARDS" && echo "CHANGED put the forward rules of the raw ports back"; } || exit 1
            |else
            |  mv -f "$FORWARDS.new" "$FORWARDS"
            |  "$FORWARDS" || exit 1
            |  echo "CHANGED the raw ports that the node forwards to the homelab"
            |fi
        """.trimMargin()
    }

    private const val FORWARDS_FILE = "/etc/wireguard/$INTERFACE-forwards.sh"

    /** The built-in chains of a node that jump to the chains of [forwardScript]: table, chain, target. */
    private val NODE_FORWARD_JUMPS = listOf(
        Triple("nat", "PREROUTING", "HG-RAW"),
        Triple("nat", "POSTROUTING", "HG-RAW-NAT"),
        Triple("filter", "FORWARD", "HG-RAW-FWD"),
        Triple("filter", "INPUT", "HG-RAW-IN"),
    )

    /** Removes a node from the mesh (decommission). The script does what it can and never fails. */
    val TEARDOWN = $$"""
        |IFACE=$${shQuote(INTERFACE)}
        |if [ -d /run/systemd/system ]; then
        |  systemctl disable "wg-quick@$IFACE" >/dev/null 2>&1 || true
        |  if [ -f $${shQuote(DOCKER_DROPIN)} ]; then rm -f $${shQuote(DOCKER_DROPIN)}; systemctl daemon-reload; fi
        |fi
        |ip link show "$IFACE" >/dev/null 2>&1 && { wg-quick down "$IFACE" >/dev/null 2>&1 || ip link del "$IFACE"; }
        |rm -f "/etc/wireguard/$IFACE.conf" $${shQuote(FORWARDS_FILE)}
        |$${NODE_FORWARD_JUMPS.joinToString("\n") { (table, chain, target) -> dropChain(table, chain, target) }}
        |true
    """.trimMargin()

    /** Removes the exit of a node from the host (decommission). The agent of the node is gone by then. */
    val EXIT_TEARDOWN: String get() = $$"""
        |ip link show $${shQuote(EXIT_INTERFACE)} >/dev/null 2>&1 && ip link del $${shQuote(EXIT_INTERFACE)}
        |$${EXIT_JUMPS.joinToString("\n") { (table, chain, target) -> dropChain(table, chain, target) }}
        |iptables -t filter -F HG-EXIT-OUT 2>/dev/null; iptables -t filter -X HG-EXIT-OUT 2>/dev/null
        |true
    """.trimMargin()

    private fun dropChain(table: String, chain: String, target: String): String {
        val iptables = "iptables -t $table"
        return listOf("-D $chain -j $target", "-F $target", "-X $target").joinToString("; ") { "$iptables $it 2>/dev/null" }
    }

    private const val DOCKER_DROPIN ="/etc/systemd/system/docker.service.d/10-homegap-mesh.conf"

    /**
     * Opens the mesh in an active ufw, and removes each HomeGAP rule for the mesh interface that
     * is not wanted, so a rule that admits more does not stay. Each `ufw` call
     * takes a moment, so the step adds only a rule that ufw does not have.
     */
    private fun firewallStep(port: Int): String {
        val wanted = FirewallRules.meshSpecs(port)
        return $$"""
            |if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
            |  WANTED=$(cat <<'HOMEGAP_MESH_RULES'
            |$${wanted.joinToString("\n")}
            |HOMEGAP_MESH_RULES
            |)
            |  ADDED=$(ufw show added 2>/dev/null | sed -n "s/^ufw \(.*\) comment 'homegap'$/\1/p")
            |  printf '%s\n' "$WANTED" | while IFS= read -r spec; do
            |    printf '%s\n' "$ADDED" | grep -qxF "$spec" || ufw $spec comment homegap >/dev/null
            |  done
            |  printf '%s\n' "$ADDED" | { grep -E ' on $${INTERFACE}( |$)' || true; } | while IFS= read -r spec; do
            |    printf '%s\n' "$WANTED" | grep -qxF "$spec" || { ufw delete $spec >/dev/null && echo "CHANGED ufw removed $spec"; }
            |  done
            |fi
        """.trimMargin()
    }

    /** The file that the orchestrator writes for the mesh agent of the homelab: the interface that the agent keeps. */
    const val SPEC_FILE = "mesh.json"

    /** The file that the mesh agent writes: the address of the homelab host, as the mesh namespace reaches it. */
    const val HOST_GATEWAY_FILE = "host-gateway"

    /** The file that the mesh agent writes: its key, the spec that it applied and the peers that it sees. */
    const val STATUS_FILE = "status.json"

    /**
     * A port of the mesh address of the homelab that goes to a service of the homelab. [target] is
     * `address:port`, on a Docker network of the mesh namespace.
     */
    @Serializable
    data class Forward(val protocol: String, val port: Int, val target: String) {
        val host: String get() = target.substringBeforeLast(':')
        val targetPort: Int get() = target.substringAfterLast(':').toInt()
    }

    /**
     * The mesh of the homelab, as the agent applies it. Other members can reach the [openPorts]
     * of the member and the [forwards], and nothing else.
     */
    @Serializable
    data class AgentSpec(
        val subnet: String,
        val port: Int,
        val address: String,
        val peers: List<Peer>,
        val openPorts: List<Int>,
        val forwards: List<Forward> = emptyList(),
        val vpn: Vpn? = null,
    )

    /**
     * The peer of the VPN on the homelab: the interface [VPN_INTERFACE] with the address
     * [address] in [subnet], on the UDP port [port]. The devices reach only the [targets].
     */
    @Serializable
    data class Vpn(
        val subnet: String,
        val address: String,
        val port: Int,
        val targets: List<VpnTarget> = emptyList(),
        /** The roles of the devices that can leave for the internet here. With null, the homelab is no exit. */
        val exitRoles: List<String>? = null,
        /** The DNS servers that a device can reach through the tunnel, and the roles for it. */
        val resolvers: List<String> = emptyList(),
        val resolverRoles: List<String> = emptyList(),
        /** The roles of the devices that can leave for the internet at a node. With null, no node is an exit. */
        val nodeExitRoles: List<String>? = null,
    )

    /**
     * The exit of a node: a WireGuard interface ([EXIT_INTERFACE]) of the agent of the node with
     * [address] in the [subnet] of the VPN, on the UDP port [port] of the node. The mesh agent of
     * the homelab ([homelab], a mesh address) sends the keys of the devices to [listen], an
     * `address:port` on the mesh.
     */
    @Serializable
    data class NodeExit(
        val subnet: String,
        val address: String,
        val port: Int,
        val listen: String,
        val homelab: String,
        /** With this, the node also serves the apps while the homelab is away. */
        val control: NodeControl? = null,
    )

    /**
     * The control side of a node: the calls of the apps and the sign-in page at [listen]
     * (`address:port` on the mesh address of the node), which the Traefik of the node uses when
     * the homelab does not answer. The node trusts a directory only with the signature of
     * [authorityKey]. [node] is its id and [host] its public address, for its exit. [name] is the
     * name of the sign-in, which the apps connect to.
     */
    @Serializable
    data class NodeControl(
        val name: String,
        val listen: String,
        val authorityKey: String,
        val node: String,
        val host: String,
        val utilitySubnet: String,
    )

    /**
     * What the mesh agent of the homelab sends a node, in full each time: the devices for the
     * exit of the node ([peers]), the directory of users with the signature of the homelab
     * ([directory], the text of the file) and the devices that the homelab knows ([devices], the
     * text of the file). With the last two, the node can serve the apps while the homelab is away.
     */
    @Serializable
    data class NodeState(val peers: List<VpnPeer> = emptyList(), val directory: String = "", val devices: String = "")

    /** What the exit of a node says after it took a list of devices. */
    @Serializable
    data class ExitStatus(
        val publicKey: String,
        val address: String,
        val port: Int,
        /** The keys of the devices that the interface has. */
        val peers: List<String> = emptyList(),
        /** The id of the node and its public host. The mesh agent of the homelab adds them. */
        val node: String = "",
        val host: String = "",
    )

    const val EXIT_INTERFACE = "hg-exit"

    /** The TCP port of the mesh address of a node where its agent takes the devices of its exit. */
    const val AGENT_PORT = 8091

    /** The path of that call. */
    const val NODE_STATE_PATH = "/state"

    /** The TCP port of the mesh address of a node where its agent serves the apps while the homelab is away. */
    const val CONTROL_PORT = 8092

    /** The files that the sign-in server of the homelab writes for the nodes, next to [VPN_PEERS_FILE]. */
    const val VPN_DIRECTORY_FILE = "directory.json"
    const val VPN_DEVICES_FILE = "devices.json"

    /**
     * The address of the exit of a node in the subnet of the VPN. The last 256 addresses of the
     * subnet are for the exits, and the number of a node there is the last part of its mesh address.
     */
    fun exitAddress(vpnSubnet: String, meshAddress: String): String {
        val subnet = parseSubnet(vpnSubnet)
        val size = 1L shl (IPV4_BITS - subnet.prefix)
        return format(subnet.base + size - EXIT_RANGE + meshAddress.substringAfterLast('.').toInt())
    }

    /** The number of addresses at the end of the subnet of the VPN that no device gets. */
    const val EXIT_RANGE = 256

    /** Returns true if a device with [roles] can use an exit for [wanted] roles. An admin can use each exit. */
    fun permits(wanted: List<String>, roles: List<String>): Boolean =
        ADMIN_ROLE in roles || roles.any { it in wanted }

    /**
     * The firewall of the exit of a node, as input for `iptables-restore --noflush`:
     * - In: WireGuard of the exit from each address, the liveness probe of a device, and the
     *   list of devices from the homelab over the mesh.
     * - Through: a device reaches the internet and no private range, not the mesh and not
     *   another device.
     */
    fun exitFirewall(exit: NodeExit): String = buildString {
        val listenPort = exit.listen.substringAfterLast(':')
        appendLine("*filter")
        appendLine(":HG-EXIT-IN - [0:0]")
        appendLine(":HG-EXIT-FWD - [0:0]")
        appendLine(":HG-EXIT-OUT - [0:0]")
        appendLine("-A HG-EXIT-IN ! -i $INTERFACE -p udp --dport ${exit.port} -j ACCEPT")
        appendLine("-A HG-EXIT-IN -i $EXIT_INTERFACE -d ${exit.address} -p udp --dport ${exit.port + 1} -j ACCEPT")
        appendLine("-A HG-EXIT-IN -i $EXIT_INTERFACE -d ${exit.address} -p icmp -j ACCEPT")
        appendLine("-A HG-EXIT-IN -i $EXIT_INTERFACE -j DROP")
        appendLine("-A HG-EXIT-IN -i $INTERFACE -s ${exit.homelab} -p tcp --dport $listenPort -j ACCEPT")
        appendLine("-A HG-EXIT-IN -p tcp --dport $listenPort -j DROP")
        exit.control?.let { control ->
            // The Traefik of the node is in a container, so it comes from an address of a Docker network.
            val port = control.listen.substringAfterLast(':')
            appendLine("-A HG-EXIT-IN ! -i $INTERFACE -s $DOCKER_RANGE -p tcp --dport $port -j ACCEPT")
            appendLine("-A HG-EXIT-IN -p tcp --dport $port -j DROP")
        }
        appendLine("-A HG-EXIT-FWD -i $EXIT_INTERFACE -j HG-EXIT-OUT")
        appendLine("-A HG-EXIT-FWD -o $EXIT_INTERFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-EXIT-FWD -o $EXIT_INTERFACE -j DROP")
        PRIVATE_RANGES.forEach { appendLine("-A HG-EXIT-OUT -d $it -j DROP") }
        appendLine("-A HG-EXIT-OUT -o $INTERFACE -j DROP")
        appendLine("-A HG-EXIT-OUT -o $EXIT_INTERFACE -j DROP")
        appendLine("-A HG-EXIT-OUT -j ACCEPT")
        appendLine("COMMIT")
        appendLine("*nat")
        appendLine(":HG-EXIT-NAT - [0:0]")
        appendLine("-A HG-EXIT-NAT -s ${exit.subnet} ! -o $EXIT_INTERFACE -j MASQUERADE")
        appendLine("COMMIT")
    }

    /** The built-in chains of a node that jump to the chains of [exitFirewall]: table, chain, target. */
    val EXIT_JUMPS = listOf(
        Triple("filter", "INPUT", "HG-EXIT-IN"),
        Triple("filter", "FORWARD", "HG-EXIT-FWD"),
        Triple("nat", "POSTROUTING", "HG-EXIT-NAT"),
    )

    /** The config of the exit interface of a node in `wg` format, for `wg syncconf`. */
    fun exitConfig(exit: NodeExit, privateKey: String, peers: List<VpnPeer>): String =
        vpnConfig(Vpn(exit.subnet, exit.address, exit.port), privateKey, peers)

    /**
     * A private name of the VPN. A packet of a device to [address] (the address of the name) goes
     * to [target], an address on a Docker network of the mesh namespace, on the same port.
     */
    @Serializable
    data class VpnTarget(
        val address: String,
        val target: String,
        val tcpPorts: List<Int> = emptyList(),
        val udpPorts: List<Int> = emptyList(),
        val roles: List<String> = emptyList(),
    )

    /** A device that is connected: its WireGuard key, its address in the tunnel and its roles. */
    @Serializable
    data class VpnPeer(val publicKey: String, val address: String, val roles: List<String> = emptyList())

    /** What the VPN peer of the homelab says about itself, for the sign-in server. */
    @Serializable
    data class VpnStatus(
        val publicKey: String,
        val port: Int,
        val address: String,
        /** The keys of the devices that the interface has. */
        val peers: List<String> = emptyList(),
        /** The exits of the nodes that answered, with the devices that each one has. */
        val exits: List<ExitStatus> = emptyList(),
    )

    const val VPN_INTERFACE = "hg-vpn"

    /** The file that the sign-in server writes: the devices that are connected ([VpnPeer]). */
    const val VPN_PEERS_FILE = "peers.json"

    /** The file that the mesh agent writes: [VpnStatus]. */
    const val VPN_STATUS_FILE = "status.json"

    /** The config of the VPN interface in `wg` format, for `wg syncconf`. */
    fun vpnConfig(vpn: Vpn, privateKey: String, peers: List<VpnPeer>): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("ListenPort = ${vpn.port}")
        for ((publicKey, address) in peers.sortedBy { it.address }) {
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $publicKey")
            appendLine("AllowedIPs = $address/32")
        }
    }

    /** The address of the homelab in the subnet of the VPN: the first one. */
    fun vpnAddress(subnet: String): String = format(parseIp(subnet.substringBefore('/')) + 1)

    /** Returns true if a device with [roles] can reach [target]. An admin can reach each target. */
    fun allows(target: VpnTarget, roles: List<String>): Boolean =
        ADMIN_ROLE in roles || roles.any { it in target.roles }

    private const val ADMIN_ROLE = "Admin"
    private const val MULTIPORT_LIMIT = 15
    private const val EXIT_CHAIN = "HG-VPN-OUT"

    /** The range that Docker takes the addresses of its networks from. */
    private const val DOCKER_RANGE = "172.16.0.0/12"
    private const val DNS_PORT = 53

    /** The ranges that are never on the internet. */
    private val PRIVATE_RANGES = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "100.64.0.0/10",
        "169.254.0.0/16",
        "127.0.0.0/8",
        "224.0.0.0/3",
    )

    /** The rules of `HG-FWD` for what the device [peer] sends past the private names: DNS servers and the exit. */
    private fun vpnOutRules(vpn: Vpn, peer: VpnPeer): String = buildString {
        val from = "-A HG-FWD -i $VPN_INTERFACE -s ${peer.address}"
        if (permits(vpn.resolverRoles, peer.roles)) {
            for (resolver in vpn.resolvers.sorted()) {
                appendLine("$from -d $resolver ! -o $INTERFACE -p udp --dport $DNS_PORT -j ACCEPT")
                appendLine("$from -d $resolver ! -o $INTERFACE -p tcp --dport $DNS_PORT -j ACCEPT")
            }
        }
        if (vpn.exitRoles != null && permits(vpn.exitRoles, peer.roles)) appendLine("$from -j $EXIT_CHAIN")
    }.trimEnd()



    @Serializable
    data class AgentStatus(
        val publicKey: String? = null,
        /** The [specHash] of the spec that is in effect. It is null before the first spec. */
        val applied: String? = null,
        val peers: List<PeerStatus>? = null,
        val error: String? = null,
        val at: Long = 0,
    )

    fun specHash(specText: String): String = Crypto.fnv64(specText)

    /** The built-in chains that jump to the chains of HomeGAP: table, chain, target. */
    val FIREWALL_JUMPS = listOf(
        Triple("filter", "INPUT", "HG-IN"),
        Triple("filter", "OUTPUT", "HG-OUT"),
        Triple("filter", "FORWARD", "HG-FWD"),
        Triple("nat", "POSTROUTING", "HG-NAT"),
        Triple("nat", "PREROUTING", "HG-DNAT"),
    )

    /**
     * The firewall of the mesh namespace of the homelab, as input for `iptables-restore --noflush`.
     * A chain that the input names becomes empty first, so one step replaces the full set. The
     * rules refuse all traffic, with these exceptions:
     * - In: answers, ping, and the [AgentSpec.openPorts] of the member. The nodes reach them over
     *   the mesh, and the orchestrator reaches them over the network that it shares with the member.
     * - Out: the mesh, answers, and WireGuard itself to the nodes. Nothing in the namespace can
     *   open a connection to the orchestrator, another container or the home network.
     * - Through: the orchestrator to the members over the mesh, with the mesh address of the
     *   homelab as the source, and the answers. For a node, the namespace forwards only the
     *   [AgentSpec.forwards], each to its one target.
     *
     * A TCP forward on one of the [AgentSpec.openPorts] gets no rule.
     *
     * With [AgentSpec.vpn], the devices of the VPN ([vpnPeers]) come in on [VPN_INTERFACE]:
     * - In: WireGuard of the VPN from a node over the mesh, and the liveness probe of a device.
     * - Through: a device reaches a target of the VPN only if it has a role of the target. The
     *   rule names the address of the device, and WireGuard accepts that address only from the
     *   key of the device. Each other packet of a device is refused, also to the mesh.
     */
    fun firewallRules(spec: AgentSpec, vpnPeers: List<VpnPeer> = emptyList()): String = buildString {
        val vpn = spec.vpn
        val ports = spec.openPorts.joinToString(",")
        val forwards = spec.forwards
            .filterNot { it.protocol == TCP && it.port in spec.openPorts }
            .map { it to it.host }
        appendLine("*filter")
        appendLine(":HG-IN - [0:0]")
        appendLine(":HG-OUT - [0:0]")
        appendLine(":HG-FWD - [0:0]")
        appendLine(":$EXIT_CHAIN - [0:0]")
        // A device that leaves for the internet here reaches no private range: not the home network,
        // not a Docker network, not the mesh.
        PRIVATE_RANGES.forEach { appendLine("-A $EXIT_CHAIN -d $it -j DROP") }
        appendLine("-A $EXIT_CHAIN -o $INTERFACE -j DROP")
        appendLine("-A $EXIT_CHAIN -j ACCEPT")
        appendLine("-A HG-IN -i lo -j ACCEPT")
        appendLine("-A HG-IN -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-IN -p icmp -j ACCEPT")
        if (ports.isNotEmpty()) appendLine("-A HG-IN -p tcp -m multiport --dports $ports -j ACCEPT")
        if (vpn != null) {
            appendLine("-A HG-IN -i $INTERFACE -p udp --dport ${vpn.port} -j ACCEPT")
            appendLine("-A HG-IN -i $VPN_INTERFACE -d ${vpn.address} -p udp --dport ${vpn.port + 1} -j ACCEPT")
        }
        appendLine("-A HG-IN -j DROP")
        appendLine("-A HG-OUT -o lo -j ACCEPT")
        appendLine("-A HG-OUT -o $INTERFACE -j ACCEPT")
        appendLine("-A HG-OUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-OUT -p udp --dport ${spec.port} -j ACCEPT")
        appendLine("-A HG-OUT -j DROP")
        if (vpn != null) {
            // Before each other rule: a device gets only what a rule of HG-DNAT sent to a target.
            appendLine("-A HG-FWD -i $VPN_INTERFACE ! -o $INTERFACE -m conntrack --ctstate DNAT -j ACCEPT")
            vpnPeers.sortedBy { it.address }
                .map { vpnOutRules(vpn, it) }
                .filter { it.isNotEmpty() }
                .forEach(::appendLine)
            appendLine("-A HG-FWD -i $VPN_INTERFACE -j DROP")
            appendLine("-A HG-FWD -o $VPN_INTERFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        }
        appendLine("-A HG-FWD ! -i $INTERFACE -o $INTERFACE -j ACCEPT")
        for ((forward, address) in forwards) {
            val match = "-d $address -p ${forward.protocol} --dport ${forward.targetPort}"
            appendLine("-A HG-FWD -i $INTERFACE $match -m conntrack --ctstate DNAT -j ACCEPT")
        }
        appendLine("-A HG-FWD -i $INTERFACE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT")
        appendLine("-A HG-FWD -j DROP")
        appendLine("COMMIT")
        appendLine("*nat")
        appendLine(":HG-NAT - [0:0]")
        appendLine(":HG-DNAT - [0:0]")
        appendLine("-A HG-NAT -o $INTERFACE -j MASQUERADE")
        // The target sees the namespace as the client, so its answers come back here.
        appendLine("-A HG-NAT ! -o $INTERFACE -m conntrack --ctstate DNAT -j MASQUERADE")
        // The traffic of a device that leaves here (an exit, a DNS server) gets the address of the namespace.
        vpn?.let { appendLine("-A HG-NAT -s ${it.subnet} ! -o $INTERFACE -j MASQUERADE") }
        for ((forward, address) in forwards) {
            val match = "-i $INTERFACE -d ${spec.address} -p ${forward.protocol} --dport ${forward.port}"
            appendLine("-A HG-DNAT $match -j DNAT --to-destination $address:${forward.targetPort}")
        }
        for ((_, device, roles) in vpnPeers.sortedBy { it.address }) {
            for ((address, destination, tcpPorts, udpPorts) in vpn?.targets.orEmpty().filter { allows(it, roles) }) {
                val match = "-i $VPN_INTERFACE -s $device -d $address"
                for ((protocol, targetPorts) in listOf(TCP to tcpPorts, "udp" to udpPorts)) {
                    if (targetPorts.isEmpty()) continue
                    // One rule of iptables takes 15 ports.
                    for (chunk in targetPorts.sorted().chunked(MULTIPORT_LIMIT)) {
                        val list = "-p $protocol -m multiport --dports ${chunk.joinToString(",")}"
                        appendLine("-A HG-DNAT $match $list -j DNAT --to-destination $destination")
                    }
                }
            }
        }
        appendLine("COMMIT")
    }

    /** One peer line of `wg show <iface> dump`. [latestHandshake] is in epoch seconds (0 = never). */
    @Serializable
    data class PeerStatus(
        val publicKey: String,
        val endpoint: String?,
        val latestHandshake: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    /** The peers in `wg show <iface> dump`. The first line is the interface itself. */
    fun parseDump(dump: String): List<PeerStatus> =
        dump.lines().drop(1).mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < DUMP_PEER_FIELDS) return@mapNotNull null
            PeerStatus(
                publicKey = fields[0],
                endpoint = fields[2].takeIf { it != "(none)" },
                latestHandshake = fields[4].toLongOrNull() ?: 0,
                rxBytes = fields[5].toLongOrNull() ?: 0,
                txBytes = fields[6].toLongOrNull() ?: 0,
            )
        }

    /** The text between `DUMP-BEGIN` and `DUMP-END` in the output of a node script. */
    fun dumpSection(output: String): String =
        output.substringAfter("DUMP-BEGIN\n", "").substringBefore("DUMP-END", "").trimEnd()

    /** An IPv6 endpoint needs brackets before the port. */
    fun endpointHost(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

    private fun parseIp(ip: String): Long {
        val octets = ip.split('.').map { it.toIntOrNull() ?: -1 }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "'$ip' is not an IPv4 address" }
        return octets.fold(0L) { address, octet -> (address shl 8) or octet.toLong() }
    }

    private fun format(address: Long) =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((address shr shift) and 0xFF).toString() }
}
