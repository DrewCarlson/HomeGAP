<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/img/brand/readme-header-dark.svg">
  <img alt="HomeGAP: Homelab Global Access Platform" src="docs/img/brand/readme-header-light.svg" width="300">
</picture>

Publish homelab services worldwide through your own regional edge nodes. No inbound ports, no third-party tunnel.

[![Build](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml/badge.svg)](https://github.com/DrewCarlson/HomeGAP/actions/workflows/build.yml)
[![Docs](https://img.shields.io/badge/docs-latest-5c6bc0)](https://drewcarlson.github.io/HomeGAP/latest/)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Container image](https://img.shields.io/badge/ghcr.io-drewcarlson%2Fhomegap-2496ED?logo=docker&logoColor=white)](https://github.com/DrewCarlson/HomeGAP/pkgs/container/homegap)

**[Documentation](https://drewcarlson.github.io/HomeGAP/latest/)** ·
**[Getting started](https://drewcarlson.github.io/HomeGAP/latest/getting-started/)** ·
**[Label reference](https://drewcarlson.github.io/HomeGAP/latest/services/labels/)**

</div>

---

HomeGAP is a self-hosted alternative to Cloudflare Tunnels and WARP. Your homelab is the only
origin, and nothing at home accepts inbound connections. Regional VPS nodes take each viewer's
connection near the viewer and send it home through a private WireGuard mesh that the homelab
dials out.

One orchestrator container on the homelab controls the system from Docker labels:

- **Regional nodes** are Ubuntu VPS servers from any provider, in any location. Each node runs
  [Traefik](https://traefik.io/), a maintenance page and the agent of HomeGAP.
- **[Bunny DNS](https://bunny.net/dns/)** sends each viewer to the nearest healthy node. Health
  checks remove a node that fails.
- **The orchestrator** reads the labels and writes the routes of every node, the mesh between
  the nodes and the homelab, and the DNS records. It also installs and maintains the nodes over
  SSH.

> [!CAUTION]
> HomeGAP is early-stage software. Test it on a domain that nothing depends on, for example a
> spare domain or a subdomain delegated to Bunny. Then move production traffic to it.

## Features

- **The origin stays hidden.** The homelab has no open ports. The homelab dials out to each node.
- **End-to-end TLS by default.** Nodes route by the TLS server name and never see plaintext. One
  label moves TLS termination to the nodes.
  ([Traffic paths](https://drewcarlson.github.io/HomeGAP/latest/services/traffic-paths/))
- **HTTP(S), raw TCP and UDP**, and private services that only the devices of the VPN can reach.
  ([Services](https://drewcarlson.github.io/HomeGAP/latest/services/))
- **Geo routing with failover.** Each viewer reaches the nearest healthy node.
  ([DNS routing and health checks](https://drewcarlson.github.io/HomeGAP/latest/configuration/dns/))
- **A VPN with its own sign-in.** The Pangolin apps and `olm` connect to the sign-in of HomeGAP,
  with a HomeGAP password or an OpenID Connect provider such as Authelia. Roles decide which
  private services a device can reach. A device can leave for the internet through a node or
  through the homelab.
  ([Sign-in and VPN](https://drewcarlson.github.io/HomeGAP/latest/vpn/))
- **Exits that work when the homelab is down.** A node signs devices in for the exits, and the
  exits through a node continue to work.
  ([Exits and tunnel DNS](https://drewcarlson.github.io/HomeGAP/latest/vpn/exit-nodes/))
- **Real client addresses.** With the PROXY protocol, the homelab sees the address of each
  viewer.
  ([Client addresses](https://drewcarlson.github.io/HomeGAP/latest/services/client-addresses/))
- **One label publishes a service.** Docker labels are the source of truth. The orchestrator
  makes the rest match them.
- **Node lifecycle from a bare VPS.** A dashboard wizard turns a fresh server into a node over
  SSH. Updates roll out one node at a time, with automatic rollback.
  ([Add a node](https://drewcarlson.github.io/HomeGAP/latest/getting-started/add-node/))
- **A built-in dashboard** for health, pausing, re-provisioning, users and devices, speed tests,
  diagnostics and node management. It also serves the documentation for its own version.
  ([The dashboard](https://drewcarlson.github.io/HomeGAP/latest/operations/dashboard/))
- **Self-hosted maps** of the nodes and the homelab, on the dashboard and on an optional public
  status page. No third-party map service.
  ([Maps and locations](https://drewcarlson.github.io/HomeGAP/latest/operations/maps/))
- **The orchestrator is never in the traffic path.** If it stops, the nodes continue to serve,
  the mesh stays up and DNS continues to resolve.

## How it works

Solid arrows are viewer traffic. Dashed arrows are the orchestrator's control plane.

```mermaid
flowchart TD
    viewer([Viewers and Pangolin apps])
    dns["Bunny DNS<br/>geo routing + health checks"]
    node["Nearest regional node<br/>Traefik · maintenance page · HomeGAP agent"]

    subgraph homelab["Homelab (no inbound ports)"]
        mesh["Mesh agent<br/>homegap mesh"]
        signin["Sign-in<br/>homegap sign-in"]
        traefik["Homelab Traefik"]
        services["Services"]
        orch["Orchestrator<br/>homegap run"]
    end

    viewer --> dns
    dns -->|resolves to| node
    node <-->|WireGuard mesh hg-mesh, dialed outbound from home| mesh
    mesh --> traefik
    mesh --> signin
    traefik --> services

    orch -.->|reads Docker labels| traefik
    orch -.->|mesh description| mesh
    orch -.->|SSH: stack and routes| node
    orch -.->|Bunny API| dns
```

A viewer's request goes to the node that DNS picks. The node reads only the TLS server name and
sends the encrypted stream over the mesh to your homelab Traefik, which ends TLS as it does
today. A device of the VPN connects to a node, and the node forwards it to the homelab.
[How HomeGAP works](https://drewcarlson.github.io/HomeGAP/latest/getting-started/how-it-works/)
explains each step.

## A quick look

Label your homelab Traefik as the front door. The root and every name one label under the domain
that it serves are then published through the nodes, with no more labels:

```yaml
traefik:
  labels:
    homegap.expose: "example.net"
    homegap.wildcard: "true"
    homegap.protocol: "https"
    homegap.tls: "passthrough"   # change to "terminate" to end TLS on the nodes
```

Other services need labels only when they need their own path, for example a game server on a
public UDP port in one region:

```yaml
game:
  labels:
    homegap.expose: "game.example.net"
    homegap.protocol: "udp"
    homegap.port: "27015"
    homegap.regions: "eu1"
```

The [label reference](https://drewcarlson.github.io/HomeGAP/latest/services/labels/) lists every
label.

## Get started

You need Docker and Traefik on the homelab, one Ubuntu VPS for each region, and a Bunny DNS zone.
See the [requirements](https://drewcarlson.github.io/HomeGAP/latest/getting-started/requirements/).

1. [Prepare Bunny DNS](https://drewcarlson.github.io/HomeGAP/latest/getting-started/bunny-dns/).
2. [Prepare the servers](https://drewcarlson.github.io/HomeGAP/latest/getting-started/servers/).
3. [Install on the homelab](https://drewcarlson.github.io/HomeGAP/latest/getting-started/install/).
4. [Add a node](https://drewcarlson.github.io/HomeGAP/latest/getting-started/add-node/).
5. [Publish a service](https://drewcarlson.github.io/HomeGAP/latest/getting-started/first-service/).
6. [Configure the sign-in](https://drewcarlson.github.io/HomeGAP/latest/vpn/sign-in/) and
   [connect the Pangolin apps](https://drewcarlson.github.io/HomeGAP/latest/vpn/pangolin-apps/).

The image `ghcr.io/drewcarlson/homegap` is published for `linux/amd64` and `linux/arm64`.

## Documentation

| Section | What it covers |
| --- | --- |
| [Getting started](https://drewcarlson.github.io/HomeGAP/latest/getting-started/) | The concepts, the requirements and the setup from start to finish. |
| [Services](https://drewcarlson.github.io/HomeGAP/latest/services/) | Labels, traffic paths, raw ports, private services, client addresses. |
| [Sign-in and VPN](https://drewcarlson.github.io/HomeGAP/latest/vpn/) | The sign-in, users and devices, the Pangolin apps, exits and tunnel DNS. |
| [Configuration](https://drewcarlson.github.io/HomeGAP/latest/configuration/) | Every config key, secrets, certificates, DNS. |
| [Operations](https://drewcarlson.github.io/HomeGAP/latest/operations/dashboard/) | The dashboard, updates, upgrades, troubleshooting, failure modes, performance. |
| [Reference](https://drewcarlson.github.io/HomeGAP/latest/reference/cli/) | The command line, the HTTP API, the security model, ports, files, the mesh. |
| [Development](https://drewcarlson.github.io/HomeGAP/latest/development/building/) | Building from source, the end-to-end tests, the documentation. |

## Security

The orchestrator holds every SSH key and API key, so run it on a minimal host and keep its
dashboard private. The [security model](https://drewcarlson.github.io/HomeGAP/latest/reference/security/)
gives the details.

To report a security issue, open a private
[security advisory](https://github.com/DrewCarlson/HomeGAP/security/advisories/new). Do not open
a public issue.

## Contributing

Issues and pull requests are welcome. Before you open a pull request, make sure that the unit
tests pass (`./gradlew macosArm64Test :presentation:jsNodeTest`). For changes to provisioning, routing or DNS, the
[end-to-end tests](https://drewcarlson.github.io/HomeGAP/latest/development/e2e/) must also pass.
[Build from source](https://drewcarlson.github.io/HomeGAP/latest/development/building/) tells you
how to build and test HomeGAP.

## Acknowledgements

HomeGAP builds on [Traefik](https://traefik.io/), [WireGuard](https://www.wireguard.com/) and
[Bunny DNS](https://bunny.net/dns/). The apps of [Pangolin](https://github.com/fosrl/pangolin)
by Fossorial, and its `olm` client, connect to the VPN of HomeGAP.

## AI assistance

HomeGAP was built with AI assistance. The architecture and system design were created by a
human, and the core security code was written by a human. The rest of the implementation was
written with AI assistance under human direction and review.

## License

HomeGAP is released under the [MIT License](LICENSE).
