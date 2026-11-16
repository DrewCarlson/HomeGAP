# Requirements

This page lists what you must have before you install HomeGAP.

| Component | Requirement |
| --- | --- |
| Homelab | Linux with WireGuard in the kernel, Docker with Compose, and a Traefik instance in front of your services. |
| Regional nodes | A VPS with Ubuntu 22.04, 24.04, 26.04 or 26.10 (x86_64 or aarch64), a public IPv4 address (IPv6 is optional) and SSH access. |
| DNS | A Bunny DNS zone and a Bunny API key. |

One node is sufficient to start. For failover, you need two or more nodes.

## The homelab

The compose file of the stack expects the Docker network of your Traefik as an external network with the name `traefik`. If your network has a different name, set `TRAEFIK_NETWORK` in `.env`.

The mesh agent makes the WireGuard interface `hg-mesh` in a network namespace of its own. Thus the kernel of the homelab host must have WireGuard. WireGuard is part of Linux 5.6 and later.

## SSH access to the nodes

HomeGAP accepts two types of SSH access:

- Root, or a user with sudo, with a password or a key. The add-node wizard prepares the rest. See [Add a node](add-node.md).
- A user with passwordless sudo and a key that you configure yourself. See [Prepare the servers](servers.md).

## Network access

The orchestrator needs outbound access to these destinations:

- The nodes, over SSH.
- The Bunny API at `api.bunny.net`. The config key `bunny.api_url` sets the URL (default `https://api.bunny.net`).
- The ACME server of Let's Encrypt, for the certificate of the nodes. The config key `acme.directory` sets the URL.
- The nameservers of the zone, for DNS queries.

The mesh agent of the homelab needs outbound UDP access to the mesh port of each node (`mesh.port`, default `51821`).

The Docker daemon of the homelab and the nodes need access to the container registries.

The homelab needs no inbound access. For the ports that a node opens, see [Prepare the servers](servers.md#the-firewall-on-the-node).
