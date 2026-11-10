# HomeGAP

HomeGAP (Homelab Global Access Platform) publishes the services of your homelab to the world through regional edge nodes that you own. This page tells you what HomeGAP does and where to start.

HomeGAP is a self-hosted alternative to Cloudflare Tunnels and WARP. Your home network has no inbound ports, and no third-party tunnel carries your traffic.

The homelab is the single origin, and HomeGAP never exposes it. Regional nodes accept the connections near each audience. Each node then sends the traffic back home through a WireGuard tunnel that the homelab dials outbound.

!!! warning "Caution"
    HomeGAP is early-stage software. Before you send production traffic through it, test it on a domain that nothing depends on. Use a spare domain, or a subdomain that you delegate to Bunny DNS.

## The parts

HomeGAP has three parts:

- **Regional nodes.** A node is an Ubuntu VPS from any provider, in any location. It runs Pangolin EE, Gerbil and Traefik.
- **Bunny DNS.** Bunny sends each viewer to the nearest node. Health checks remove a failed node from the answers.
- **The orchestrator.** One container on the homelab reads your Docker labels. From the labels, it creates the Pangolin resources on every node, the Newt tunnels that feed them and the Bunny DNS records that steer viewers. It also provisions and maintains the nodes over SSH.

## Features

- The origin stays hidden. Every tunnel is outbound from the homelab.
- TLS is end-to-end by default. The nodes route by SNI and never see plaintext. One label changes a service to TLS termination on the node.
- HomeGAP publishes HTTP(S), raw TCP and UDP services. Private services are available only through the Pangolin client.
- Geo routing sends each viewer to the nearest healthy node, with failover by health check.
- In cluster mode, the VPN continues to work while the homelab is down. The Pangolin apps connect through the nearest node, and sign-in continues to work.
- One label publishes a service.
- HomeGAP changes a bare VPS into a node over SSH, and it repairs drift.
- The orchestrator has a built-in dashboard.
- The maps are self-hosted. They use no third-party map service.
- The orchestrator is never in the traffic path. If it stops, the nodes, the tunnels and DNS continue to work.

You can manage each part by hand at all times: SSH to a node, the Pangolin dashboard of each node, or the Bunny console.

## Next steps

Start with [Getting started](getting-started/index.md). For the model behind the system, read [How HomeGAP works](getting-started/how-it-works.md).

These pages are the documentation for HomeGAP version {{ hg_version }}.

## License

HomeGAP has the MIT license. Pangolin EE has its own license terms. It is free for personal use and for organizations below its commercial revenue threshold.
