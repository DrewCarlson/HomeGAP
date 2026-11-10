# Cluster mesh

The cluster mesh is the private network of the cluster. This page tells you how the mesh works and how to read the **Cluster mesh** panel.

## What the mesh is

`hg-mesh` is a private WireGuard mesh between the homelab and every node. Cluster traffic uses only this mesh: the database, Valkey and the node API of Gerbil.

The addresses are fixed. The homelab is host 1 of `cluster.mesh.subnet`. A node is host `10 + slot`, where the slot is the permanent tunnel slot of the node. With the default subnet, the homelab is `10.77.0.1` and the first node is `10.77.0.10`.

## Keys and connections

Each node creates its own key during its first setup. The private key stays on the node. HomeGAP knows only the public key.

The homelab can be behind NAT. It dials every node and keeps the paths open, so HomeGAP never publishes its address. The nodes connect to each other directly on their public addresses.

The firewall of each node opens `cluster.mesh.port`/udp to anywhere. WireGuard authenticates every packet, and the address of the homelab can change.

On the homelab end of the mesh, HomeGAP drops inbound traffic, except replies, ping and the ports of the cluster database. Thus the nodes reach only the cluster services of the homelab, and the dashboard stays private.

## The interfaces

On each node, `wg-quick` manages the interface, and it starts at boot. Changes to the peers apply in place. They do not drop the sessions.

On the homelab, the interface is in the network namespace of the `homegap-mesh` container. The orchestrator joins this namespace with `NET_ADMIN`. Thus the interface stays up when the orchestrator restarts. `deploy/upgrade.sh` never recreates `homegap-mesh`.

## Probes

Once a second, each node opens a TCP connection to the etcd port of every other member. It does this on two paths:

- From the host, like etcd, Patroni and replication. A small `probe` container in the cluster member of the node does this on the host network. The node keeps the readings in `/run/homegap-probe`, so a reboot clears them.
- From inside the Docker network, through the checks of HAProxy. This is the path of the database and Valkey traffic of Pangolin, across the `homegap-cluster` bridge and its NAT.

The homelab does not send probes, but every node sends probes to it.

## The Cluster mesh panel

The **Nodes** page has a **Cluster mesh** panel. For each node and peer, it shows **Host probe (last hour)** and **Container probe (last hour)**. Each probe column shows:

- The latest round trip.
- The part of the probes lost in the last hour.
- Each outage, with its start and its duration. An outage is three or more lost probes in a row.

A degraded pill means some loss or an outage in the last hour. A red pill means that the peer does not answer now. If only the container column shows a problem, the link is good. The problem is then in the Docker network of that node.

The **Last handshake with** column shows a pill for each peer, with the peer and the time since the last WireGuard handshake. The color and the description of the pill tell the state:

| Pill | Description | Meaning |
| --- | --- | --- |
| Green | None. | The last handshake is 3 minutes old or less. |
| Yellow | "No recent handshake" | The last handshake is 3 to 10 minutes old. |
| Red | "No handshake for over 10 minutes" | The last handshake is more than 10 minutes old. |
| Red, `never` | "No handshake yet" | The member has never had a handshake with the peer. |
| Red, `not configured` | "Not in this member's peer list yet" | The member does not have this peer in its configuration yet. |

<!-- SCREENSHOT: docs/img/cluster/01-mesh-panel.png | the Cluster mesh panel on the Nodes page with the probe columns -->
