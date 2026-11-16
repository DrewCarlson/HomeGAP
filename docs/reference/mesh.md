# The mesh

The mesh is the private WireGuard network between the homelab and each node. This page tells you how its addresses, keys and firewall work and what the mesh agent does. It also tells you how to read the mesh view of the dashboard.

## What the mesh is

`hg-mesh` is a WireGuard interface on the homelab and on each node. Each member is a peer of all other members. The mesh is always on, and each route from a node to the homelab goes over it: web traffic, raw ports, the sign-in and the VPN of the devices.

```yaml
mesh:
  subnet: 10.77.0.0/24
  port: 51821
```

| Key | Default | Rules |
| --- | --- | --- |
| `mesh.subnet` | `10.77.0.0/24` | An IPv4 network address with a prefix from /16 to /26. It must not overlap `100.64.0.0/10`, where the VPN has its addresses. |
| `mesh.port` | `51821` | The UDP port that each node listens on. |

## Addresses and slots

The addresses do not change. The homelab is host 1 of `mesh.subnet`. A node is host `10 + slot`.

| Member | Address (default subnet) |
| --- | --- |
| The homelab | `10.77.0.1` |
| The node with slot 0 | `10.77.0.10` |
| The node with slot 1 | `10.77.0.11` |

The **slot** of a node is permanent. HomeGAP keeps the slots in its state, so a node keeps its address when other nodes go or change their order in the config. A new node gets the lowest free slot. A node that waits for its removal keeps its slot until the removal ends. There are 32 slots, thus the maximum is 32 nodes.

The slot also gives the node its exit address in the VPN. See [Exits and tunnel DNS](../vpn/exit-nodes.md).

## Keys

Each node makes its own key during its first setup, in `/etc/wireguard/hg-mesh.key`. The private key stays on the node. HomeGAP learns only the public key, from the output of the setup script.

The mesh agent of the homelab makes the key of the homelab one time, in the folder `data/mesh`. Only the owner of the file can read it. The key does not go to the orchestrator.

## Connections

The homelab has no public endpoint. It dials each node and sends a keepalive every 25 seconds. Thus the mesh works behind NAT, and no DNS record publishes your home address.

The nodes connect to each other directly, on their public addresses and `mesh.port`. Each peer has only the one mesh address of the other member as its permitted address.

## The interface of a node

On a node, `wg-quick` manages the interface from `/etc/wireguard/hg-mesh.conf`. Where systemd runs, the interface starts at boot.

In each pass, the orchestrator runs the mesh script on each node over SSH. The script installs `wireguard-tools`, writes the peer list and applies peer changes in place with `wg syncconf`. The current sessions stay. If a node joins with a new key, a second round in the same pass gives that key to the other members. On a paused node, the orchestrator only reads the state.

The same pass writes the forward rules of the node, in `/etc/wireguard/hg-mesh-forwards.sh`. The interface also runs this script at boot.

## The mesh agent of the homelab

On the homelab, the interface is in the network namespace of the `homegap-mesh` container. That container has a fixed image and does nothing but hold the namespace. The orchestrator is not in it.

The `homegap-mesh-agent` container runs `homegap mesh` in that namespace, with the capabilities `NET_ADMIN`, `NET_RAW` and `DAC_OVERRIDE`. It is the only container there that can change the network. The orchestrator and the agent talk through files in `data/mesh`:

| File | Writer | Content |
| --- | --- | --- |
| `mesh.json` | The orchestrator | The description of the mesh: the address, the peers, the forwards and the VPN. |
| `status.json` | The agent | The public key of the homelab, the description that is in effect, the state of each peer and the last error. |
| `host-gateway` | The agent | The address of the homelab host, as the namespace reaches it. |

The agent does these jobs:

- It keeps `hg-mesh` as `mesh.json` describes it. It applies a change of the file at once.
- It keeps the forwards. A forward sends one port of the mesh address of the homelab to one container: the front door or a service.
- It keeps `hg-vpn`, the home end of the VPN, and the firewall rules of each connected device. See [Sign-in and VPN](../vpn/index.md).
- It sends each node the devices for the exit of that node, with the signed directory. See [Exits and tunnel DNS](../vpn/exit-nodes.md).
- It writes `status.json` every 15 seconds, and it applies the full description again every 60 seconds. This repairs a rule or a peer that is not as the description says.

The agent installs the firewall before it starts the interface. If the firewall fails, the mesh stays down and never open.

`upgrade.sh` recreates the mesh agent with each upgrade, and the interface stays up without it. The script recreates `homegap-mesh` only when its part of the compose files changes. The mesh is then down for a few seconds. See [Upgrade](../operations/upgrade.md).

## The firewall

### On a node

The firewall of the node (`ufw`) opens `mesh.port`/udp to each address. WireGuard authenticates each packet, and the address of the homelab can change. `ufw` opens no port of the node on the mesh interface.

HomeGAP keeps its own chains before the chains of `ufw` and of Docker:

- `HG-RAW`, `HG-RAW-FWD` and `HG-RAW-NAT` forward the raw ports of your services, and the port of the VPN, to the homelab over the mesh. The homelab sees the mesh address of the node as the source.
- `HG-RAW-IN` accepts the PROXY protocol ports that the agent of the node serves.
- `HG-EXIT-IN`, `HG-EXIT-FWD` and `HG-EXIT-NAT` are the firewall of the exit of the node.

The agent of the node listens on two ports of its mesh address:

| Port | Use | Source that the firewall accepts |
| --- | --- | --- |
| `8091`/tcp | The device list from the homelab. | The mesh address of the homelab only. |
| `8092`/tcp | The sign-in of the node, for the time that the homelab is down. | The Docker networks of the node only, where the Traefik of the node is. |

### On the homelab

Each node is a member of the mesh, so HomeGAP treats the namespace of `homegap-mesh` as reachable from the nodes. The agent keeps a firewall there (the chains `HG-IN`, `HG-OUT`, `HG-FWD`, `HG-NAT` and `HG-DNAT`) that refuses everything except these connections:

- Into the namespace: answers, ping, WireGuard of the VPN from a node over the mesh, and the probe of a device.
- Out of the namespace: the mesh, answers and WireGuard to the nodes.
- Through the namespace, from a node: only the forwards, each to its one container.
- Through the namespace, from a device: only what the roles of its user permit.
- Through the namespace, from the orchestrator: to the members over the mesh.

Thus a node or a container in that namespace cannot open a connection to the orchestrator, to your other containers or to your home network. See [Security model](security.md).

## The mesh view of the dashboard

The **Nodes** page shows the mesh in two places.

The **Mesh link** column of the **Nodes** table shows `link up` or `link down` for each node.

The **Mesh** panel is a matrix. Each row is a member, and each column is a peer of that member. The note next to the title gives the number of links that are up, for example "6 of 6 links". A cell shows the time since the last WireGuard handshake of the member with the peer. WireGuard does a new handshake approximately every 2 minutes while traffic flows.

| Cell | Tooltip | Meaning |
| --- | --- | --- |
| Green | None. | The last handshake is 3 minutes old or less. |
| Amber | "No recent handshake" | The last handshake is 3 to 10 minutes old. |
| Rose | "No handshake for over 10 minutes" | The last handshake is more than 10 minutes old. |
| Rose, `never` | "No handshake yet" | The member has no handshake with the peer yet. |
| Rose, `not configured` | "Not in this member's peer list yet" | The member does not have this peer in its configuration yet. |
| Dim | "This member could not be read" | HomeGAP cannot read the state of the member. |

<!-- SCREENSHOT: docs/img/mesh/01-mesh-panel.png | The Mesh panel on the Nodes page with the handshake matrix -->

If a link stays down, check that the firewall of your server provider lets `mesh.port`/udp reach the node. The log of the orchestrator has the error of a member one time, and "mesh is healthy again" after the recovery. For the logs of the agent, run `docker compose logs homegap-mesh-agent` on the homelab.
