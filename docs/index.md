<div align="center" markdown>

# ![HomeGAP: Homelab Global Access Platform](img/brand/readme-header-dark.svg){ width="280" }

Publish homelab services worldwide through your own regional edge nodes. No inbound ports, no third-party tunnel.

{% if not hg_embedded %}
[![Build](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml/badge.svg)](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/DrewCarlson/HomeGAP/blob/main/LICENSE)
[![Container image](https://img.shields.io/badge/ghcr.io-drewcarlson%2Fhomegap-2496ED?logo=docker&logoColor=white)](https://github.com/DrewCarlson/HomeGAP/pkgs/container/homegap)
{% endif %}

**[Getting started](getting-started/index.md)** ·
**[How it works](getting-started/how-it-works.md)** ·
**[Label reference](services/labels.md)**

</div>

---

HomeGAP is a self-hosted alternative to Cloudflare Tunnels and WARP. Your homelab is the only origin, and nothing at home accepts inbound connections. Regional VPS nodes take each viewer's connection near the viewer and send it home through a private WireGuard mesh that the homelab dials out.

One orchestrator container on the homelab controls the system from Docker labels:

- **Regional nodes** are Ubuntu VPS servers from any provider, in any location. Each node runs Traefik, a maintenance page and the agent of HomeGAP.
- **Bunny DNS** sends each viewer to the nearest healthy node. Health checks remove a node that fails.
- **The orchestrator** reads the labels and writes the routes of every node, the mesh between the nodes and the homelab, and the DNS records. It also installs and maintains the nodes over SSH.

!!! warning "Caution"
    HomeGAP is early-stage software. Test it on a domain that nothing depends on, for example a spare domain or a subdomain delegated to Bunny DNS. Then move production traffic to it.

## Features

- **The origin stays hidden.** The homelab has no open ports. The homelab dials out to each node.
- **End-to-end TLS by default.** Nodes route by the TLS server name and never see plaintext. One label moves TLS termination to the nodes. See [Traffic paths](services/traffic-paths.md).
- **HTTP(S), raw TCP and UDP**, and private services that only the devices of the VPN can reach. See [Services](services/index.md).
- **Geo routing with failover.** Each viewer reaches the nearest healthy node. See [DNS routing and health checks](configuration/dns.md).
- **A VPN with its own sign-in.** The Pangolin apps and `olm` connect to the sign-in of HomeGAP, with a HomeGAP password or an OpenID Connect provider such as Authelia. Roles decide which private services a device can reach. See [Sign-in and VPN](vpn/index.md).
- **Exits through a node or through the homelab.** A device can send all its traffic to the internet through an exit. When the homelab is down, the exits through a node continue to work. See [Exits and tunnel DNS](vpn/exit-nodes.md).
- **Real client addresses.** With the PROXY protocol, the homelab sees the address of each viewer. See [Client addresses](services/client-addresses.md).
- **One label publishes a service.** Docker labels are the source of truth. The orchestrator makes the rest match them.
- **Node lifecycle from a bare VPS.** A dashboard wizard turns a fresh server into a node over SSH. Updates roll out one node at a time, with automatic rollback. See [Add a node](getting-started/add-node.md).
- **A built-in dashboard** for health, pausing, re-provisioning, users and devices, speed tests, diagnostics and node management. It also serves this documentation for its own version. See [The dashboard](operations/dashboard.md).
- **Self-hosted maps** of the nodes and the homelab, on the dashboard and on an optional public status page. No third-party map service. See [Maps and locations](operations/maps.md).
- **The orchestrator is never in the traffic path.** If it stops, the nodes continue to serve, the mesh stays up and DNS continues to resolve.

## Next steps

Start with [Getting started](getting-started/index.md). For the model behind the system, read [How HomeGAP works](getting-started/how-it-works.md).

These pages are the documentation for HomeGAP version {{ hg_version }}.

## License

HomeGAP has the MIT license.
