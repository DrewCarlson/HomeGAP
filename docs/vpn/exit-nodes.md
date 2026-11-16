# Exits and tunnel DNS

With an exit, a device sends all its traffic through HomeGAP, and the traffic leaves for the internet at a node or at your homelab. With tunnel DNS, a device sends its DNS lookups through the tunnel. This page tells you how to enable them and how they work.

## Enable the exits

Add `vpn.exit_nodes` to the config. By default, the config enables no exit.

```yaml
vpn:
  exit_nodes:
    nodes: true        # "Exit via <node>" for each node
    homelab: true      # "Exit via homelab"
    roles: [Member]    # who can use them (Admin always can)
```

| Key | Default | Meaning |
| --- | --- | --- |
| `vpn.exit_nodes.nodes` | `false` | Each node offers **Exit via <node>**. |
| `vpn.exit_nodes.homelab` | `false` | The homelab offers **Exit via homelab**. |
| `vpn.exit_nodes.roles` | `[]` | The roles that can use the exits. `Admin` always can. |
| `vpn.exit_port` | `51840` | The UDP port of each node for its own exit. |

The user selects an exit in the Pangolin app. While the app uses an exit, the default route of the device goes through it. The app lists only the exits that the roles of the user permit.

An exit gives a way to the internet and no way into a private network. The firewall of each exit drops packets to these ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `169.254.0.0/16`, `127.0.0.0/8` and `224.0.0.0/3`. It also drops packets to the mesh.

## Exit via a node

The traffic leaves with the public address of the node. This exit continues to work while the homelab is down.

The agent of the node (`homegap-agent`) keeps a WireGuard interface for the exit, `hg-exit`. The device connects to it on UDP port `vpn.exit_port` of the node. The address of the exit is `100.90.255.<n>` with the default subnet, where `n` is the last part of the mesh address of the node.

The mesh agent of the homelab sends each node the devices that have a role for the exits. It sends the full list over the mesh every 15 seconds and after each change. The agent of the node admits the WireGuard key of each device at the tunnel address of that device. The node accepts the list only from the mesh address of the homelab.

The app lists the exit of a node only after that node has the key of the device. A node that does not answer the mesh agent is not in the list.

Through this exit, a device reaches the internet. It does not reach a private range, the mesh or another device. On the node itself, it reaches only the probe of the app and ping.

The key of the exit stays on the node, in the folder `agent-state` of the install folder.

## Exit via homelab

The traffic leaves with your home address. This exit needs the homelab.

There is no second tunnel for this exit. The device uses its tunnel to the homelab, which a node relays. The mesh namespace of the homelab (`homegap-mesh`) sends the traffic to the internet through the Docker network `homegap-mesh`. The traffic leaves with the address of that namespace, and your router then gives it your home address.

The firewall of the namespace drops the private ranges for this traffic. Thus a device with this exit does not reach your home network or another container.

## Tunnel DNS

With `vpn.tunnel_dns`, the apps send their DNS lookups through the tunnel to the servers that you name. The homelab sends the lookups on, so they leave from your home. A device needs no exit for this.

```yaml
vpn:
  tunnel_dns:
    resolvers: ["9.9.9.10"]
    roles: [Member]
```

| Key | Default | Meaning |
| --- | --- | --- |
| `vpn.tunnel_dns.resolvers` | `["8.8.8.8"]` | The IPv4 addresses of the DNS servers. An empty list (`[]`) disables tunnel DNS. |
| `vpn.tunnel_dns.roles` | `[Member]` | The roles that can use the servers. `Admin` always can. |

When a device connects, HomeGAP gives the app these servers and enables DNS through the tunnel. The firewall of the homelab lets the device reach each server on port 53 (TCP and UDP). A lookup to a server that is not in the list gets no answer.

The servers are at the homelab only. The exits of the nodes do not carry them, so tunnel DNS does not work while the homelab is down.

The names of your private services do not use these servers. The app answers them itself. See [DNS](pangolin-apps.md#dns).

## If you disable an exit

After you set `nodes` or `homelab` to `false`, the apps do not list those exits, and the firewall rules for them go. With `nodes: false`, a node has no `hg-exit` interface.

## Ports

HomeGAP opens `vpn.port` and `vpn.exit_port` on each node in its own firewall chains, before the chains of `ufw`. If your server provider has a firewall of its own, open the two UDP ports there. See [Ports and firewall](../reference/ports.md).
