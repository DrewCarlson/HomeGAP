# Ports and firewall

This page lists the ports that HomeGAP uses on the nodes and on the homelab.

## Node: public ports

With `firewall: true` (the default), HomeGAP opens these ports in `ufw`:

| Port | Use |
| --- | --- |
| `ssh_port`/tcp (default 22) | SSH, from anywhere or only from `ssh_allow_from`. |
| 80/tcp | HTTP: the health probe of Bunny and the redirects to HTTPS. |
| 443/tcp | HTTPS. |
| 443/udp | UDP on the HTTPS port. |
| 51820/udp and 21820/udp | WireGuard, for Gerbil. |
| `cluster.mesh.port`/udp (default 51821) | The cluster mesh, in cluster mode. |
| The public ports of raw services | Raw TCP and UDP services, as their labels set them. |

A firewall at the provider level must open the same ports.

## Node: ports on the mesh

In cluster mode, HomeGAP also opens these TCP ports in `ufw`, but only on the interfaces of the cluster:

| Port | Interface | Use |
| --- | --- | --- |
| 2379 | `hg-mesh` | The etcd client port. |
| 2380 | `hg-mesh` | The etcd peer port. |
| 3004 | `hg-mesh` | The API of Gerbil. |
| 5432 | `hg-mesh` and `hg-cluster` | PostgreSQL. |
| 6379 | `hg-mesh` and `hg-cluster` | Valkey. |
| 8008 | `hg-mesh` and `hg-cluster` | The API of Patroni. |
| 26379 | `hg-mesh` | Valkey Sentinel. |

`hg-cluster` is the bridge of the `homegap-cluster` Docker network on the node. A rule in the `DOCKER-USER` chain drops packets from a container to ports 2380 and 26379 of another member. See [Cluster mesh](../cluster/mesh.md#what-a-container-on-a-node-can-reach).

## Node: ports on loopback

| Port | Use |
| --- | --- |
| 3000, 3002, 3003 | Pangolin. The orchestrator reaches 3000 and 3003 through SSH port forwarding. |
| `127.0.0.1:8404` | The stats page of HAProxy, in cluster mode. |

## Node: reserved ports

The node uses these ports itself: `22`, `80`, `443`, `2379`, `2380`, `3000`, `3001`, `3002`, `3003`, `3004`, `3005`, `5432`, `5433`, `6379`, `6380`, `8008`, `8080`, `8082`, `8090`, `21820`, `26379` and `51820`. A public raw service cannot use them. In cluster mode, it also cannot use `cluster.mesh.port`/udp.

## Node: internal ports

| Port | Use |
| --- | --- |
| From `pangolin.passthrough_port_base` (default 40000) | The internal proxy ports of passthrough resources. HomeGAP never publishes them. |
| `cluster-proxy:5433` | PostgreSQL, through HAProxy, on the `homegap-cluster` network. Cluster mode only. |
| `cluster-proxy:6380` | Valkey, through HAProxy, on the `homegap-cluster` network. Cluster mode only. |

## Homelab

The homelab has no inbound ports from the internet.

| Port | Use |
| --- | --- |
| `dashboard.bind` (8088) | The dashboard. Reach it only privately. |
| `speedtest.bind` (8089) | The public origin of the speed test and the status page. Your Traefik routes to it. |
| 8080 on your Traefik | The Traefik API, with `api.insecure: true`, for `homegap.private_middleware`. Do not publish it. |

## Addresses

| Range | Use |
| --- | --- |
| Host 1 of `cluster.mesh.subnet` | The mesh address of the homelab. |
| Host `10 + slot` of `cluster.mesh.subnet` | The mesh address of a node. |
| `100.89.<128 + 4·slot>.0/22` | The tunnel subnet of a node. |

See [Cluster mesh](../cluster/mesh.md) and [Manage nodes](../operations/nodes.md#tunnel-slots).
