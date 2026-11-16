# Sign-in and VPN

HomeGAP has its own sign-in and its own VPN for the devices of your users. This page tells you what they give you, which parts they have and what works while the homelab is down.

## What you get

- Your users sign in with a HomeGAP password or through an OpenID Connect provider such as Authelia. See [Sign-in](sign-in.md).
- You manage the users, the machines and their devices on the **Access** page of the dashboard, or with two commands. See [Users, machines and devices](access.md).
- The unmodified Pangolin apps for Android, iOS and desktop connect to HomeGAP. The `olm` command line client connects a server. See [Connect the Pangolin apps](pangolin-apps.md).
- A connected device reaches your private services by their names. The roles of its user decide which services. See [Private services](../services/private.md).
- A device can send all its traffic through a node or through the homelab. It can also send its DNS lookups through the tunnel. See [Exits and tunnel DNS](exit-nodes.md).

HomeGAP denies everything else. A device reaches a private service only if a role of its user permits it.

## Enable the sign-in

The sign-in and the VPN start when the config has `sign_in.url`. Without it, there is no sign-in and no VPN.

```yaml
sign_in:
  url: https://vpn.example.net
```

This is the address that a user enters in the Pangolin apps. Each node serves it, and Bunny DNS sends a device to a healthy node. For the methods of the sign-in, see [Sign-in](sign-in.md).

## The parts

| Where | Part | What it does |
| --- | --- | --- |
| Homelab | `orchestrator` | It holds the users, the machines and the roles. It signs this directory with a key that stays on the homelab. |
| Homelab | `homegap-sign-in` | It serves the sign-in page and the calls of the apps. It holds the devices and the sessions. |
| Homelab | `homegap-mesh-agent` | It keeps the home end of the VPN, the WireGuard interface `hg-vpn`, and the access rules of each device. |
| Node | Traefik | It serves the name of the sign-in and sends the requests to the homelab over the mesh. |
| Node | `homegap-agent` | It keeps the exit of the node, the interface `hg-exit`. It also serves the sign-in while the homelab is down. |

The sign-in is a container of its own. Thus the part that the internet reaches has no SSH key, no DNS key and no access to Docker.

## How a device connects

1. The app opens the address of the sign-in. A node answers and sends the request to the homelab over the mesh.
2. The user signs in on a page in the browser. The device then has a session.
3. The app sends a new WireGuard key. The mesh agent of the homelab admits the key at the tunnel address of the device.
4. The app sends its WireGuard packets to a node, on UDP port `vpn.port`.
5. The kernel of the node forwards the packets to the homelab over the mesh. The node cannot read them.

A node always relays the traffic between a device and the homelab. There is no direct path to your home, and HomeGAP does no hole punching. For the mesh, see [The mesh](../reference/mesh.md).

The mesh agent writes one set of firewall rules for each device, from the roles of its user. A block, a removal or a change of roles updates the rules at once.

## Settings of the VPN

All keys of `vpn` have a default. See also the [Configuration reference](../configuration/reference.md).

| Key | Default | Meaning |
| --- | --- | --- |
| `vpn.port` | `51830` | The UDP port of each node that the apps connect to. The node forwards it to the homelab. |
| `vpn.exit_port` | `51840` | The UDP port of each node for its own exit. |
| `vpn.client_subnet` | `100.90.0.0/16` | The tunnel addresses of the devices. It must be a /16. |
| `vpn.utility_subnet` | `100.96.128.0/24` | The addresses of the private names, and the DNS proxy of each app. |
| `vpn.endpoint` | The name of the sign-in | The name or address that the devices send their WireGuard packets to. It must go to a node. |
| `vpn.exit_nodes` | Disabled | See [Exits and tunnel DNS](exit-nodes.md). |
| `vpn.tunnel_dns` | `8.8.8.8` for the role `Member` | See [Exits and tunnel DNS](exit-nodes.md). |

The two ports must be different. HomeGAP also uses the next port after each one, inside the tunnel: `51831` and `51841` with the defaults.

HomeGAP opens `vpn.port` and `vpn.exit_port` in its own firewall chains on each node. If your server provider has a firewall of its own, open the two UDP ports there. See [Ports and firewall](../reference/ports.md).

### Addresses in the tunnel

| Address (default subnet) | Use |
| --- | --- |
| `100.90.0.1` | The homelab. |
| `100.90.0.2` and higher | The devices. A device keeps its address. |
| `100.90.<100 + n>.x` | The devices that a node creates while the homelab is down. |
| `100.90.255.<n>` | The exit of a node. |

`n` is the last part of the mesh address of the node. For the first node, `n` is 10.

## With the homelab down

With `vpn.exit_nodes.nodes: true`, each node holds a signed copy of the directory and of the device list. The node accepts a copy only with the signature of the homelab. With these copies, a node serves the apps alone.

Without the exits of the nodes, a node has nothing to give a device. The sign-in then does not answer during the outage.

- The sign-in answers through a node again after approximately 15 seconds.
- A device connects to a node and uses the exit of that node, if its roles permit the exit.
- A new device signs in at a node with a HomeGAP password. An OpenID Connect provider is not available at a node.
- A machine connects with its id and its secret.
- The private services of the homelab are not reachable. **Exit via homelab** and the DNS servers of `vpn.tunnel_dns` do not work.
- Changes to users and devices wait for the homelab. A block that you make during the outage reaches a node only after the homelab returns.

A session that a node signs gives the exit of that node and nothing more. The homelab refuses such a session. Thus the user signs in again after the homelab returns.

!!! warning "Caution"
    With `sign_in.passwords: false`, no user can sign in while the homelab is down. Devices that have a session of the homelab still connect.

For all other effects of an outage, see [Failure modes](../operations/failure-modes.md).
