# Ports and firewall

This page lists the ports that HomeGAP uses on the nodes and on the homelab.

## Node: public ports

With `firewall: true` (the default), HomeGAP opens these ports in `ufw`:

| Port | Use |
| --- | --- |
| `ssh_port`/tcp (default 22) | SSH, from anywhere or only from `ssh_allow_from`. |
| 80/tcp | HTTP: the health probe of Bunny and the redirects to HTTPS. |
| 443/tcp | HTTPS. |
| 443/udp | UDP on the HTTPS port (HTTP/3). |
| `mesh.port`/udp (default 51821) | The mesh. WireGuard authenticates each packet. |

HomeGAP opens the next ports in its own `iptables` chains, before the rules of `ufw`. Thus the firewall follows a new port at once.

| Port | Use |
| --- | --- |
| `vpn.port`/udp (default 51830) | The VPN. The kernel of the node forwards the packets over the mesh to the homelab. |
| `vpn.exit_port`/udp (default 51840) | The exit of the node, with `vpn.exit_nodes.nodes: true`. |
| The public ports of raw services | Raw TCP and UDP services, as their labels set them. The kernel forwards them over the mesh. |
| The public ports of raw TCP services with `homegap.proxy_protocol` | The agent of the node accepts them and adds the PROXY protocol header. |

A firewall at the provider level must open the same ports.

## Node: ports that the internet cannot reach

| Port | Where | Use |
| --- | --- | --- |
| 8091/tcp | The mesh address of the node, with `vpn.exit_nodes.nodes: true` | The agent takes the devices of its exit from the homelab. The firewall accepts only the mesh address of the homelab. |
| 8092/tcp | The mesh address of the node, with `vpn.exit_nodes.nodes: true` and `sign_in.url` | The sign-in of the node for an outage of the homelab. The firewall accepts only a Docker network of the node, where its Traefik is. |
| `vpn.exit_port + 1`/udp | The exit address of the node, inside the VPN | The liveness probe of a device. |
| 8090/tcp | The `homegap-maintenance` container | The maintenance page. The container publishes no port, so only the Traefik of the node reaches it. |
| `127.0.0.1:8082` | The `traefik` container | The metrics of Traefik. The orchestrator reads them through SSH. |

`ufw` opens no port on the mesh interface.

## Node: reserved ports

A public raw service cannot use these ports on a node:

- `80` and `443`, for the web.
- `8091` and `8092`, for the agent of the node.
- The SSH port of each node that serves the service (`ssh_port`, default 22).
- `mesh.port`/udp.

HomeGAP refuses such a service and reports a label error. Publish the service on a different port.

A public service cannot take `vpn.port`/udp, `vpn.exit_port`/udp or the port after each of them. HomeGAP refuses the service and shows a label error.

## Homelab

The homelab has no inbound ports from the internet. The mesh agent dials each node.

| Port | Where | Use |
| --- | --- | --- |
| `dashboard.bind` (8088) | The `orchestrator` container. The stack publishes it on `127.0.0.1:8088` of the host. | The dashboard. Reach it only privately. |
| `speedtest.bind` (8089) | The `orchestrator` container | The public origin of the speed test and the status page. Your Traefik routes to it, with the route that HomeGAP gives it through the HTTP provider. |
| 8090/tcp | The `homegap-sign-in` container | The sign-in. Each node sends the name of `sign_in.url` to it over the mesh. |
| From `node_stack.mesh_port_base` (default 40000) | The mesh address of the homelab | One port for each service that a node reaches by name. The mesh namespace forwards it to the container. No node publishes these ports. |
| The public ports of raw services | The mesh address of the homelab | The mesh namespace forwards each port to its container. |
| `vpn.port`/udp (default 51830) | The mesh address of the homelab | The WireGuard peer of the VPN. It accepts packets only from the mesh. |
| `vpn.port + 1`/udp | The VPN address of the homelab, inside the VPN | The liveness probe of a device. |
| 2375/tcp | The `docker-proxy` container | The read-only Docker API. Only the orchestrator reaches it. |
| 8080 on your Traefik | Your front door container | The Traefik API, with `api.insecure: true`, for `homegap.private_middleware`. Do not publish it. |

## Addresses

| Range | Use |
| --- | --- |
| Host 1 of `mesh.subnet` (default `10.77.0.0/24`) | The mesh address of the homelab. |
| Host `10 + slot` of `mesh.subnet` | The mesh address of a node. |
| The first address of `vpn.client_subnet` (default `100.90.0.0/16`) | The VPN address of the homelab. |
| The last 256 addresses of `vpn.client_subnet` | The exit addresses of the nodes. No device gets one. |
| `vpn.utility_subnet` (default `100.96.128.0/24`) | The DNS proxy of the apps and the addresses of private names. |

See [Mesh](mesh.md) and [Manage nodes](../operations/nodes.md).
