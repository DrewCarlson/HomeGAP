# Requirements

This page lists what you must have before you install HomeGAP.

| Component | Requirement |
| --- | --- |
| Homelab | Docker with Compose, and a Traefik instance in front of your services. |
| Regional nodes | A VPS with Ubuntu 22.04, 24.04, 26.04 or 26.10 (x86_64 or aarch64), a public IPv4 address (IPv6 is optional) and SSH access. |
| DNS | A Bunny DNS zone and a Bunny API key. |
| Pangolin EE license | Only for cluster mode and for wildcard resources in `terminate` mode. Use one key for a cluster, or one key for each node without a cluster. See [Pangolin EE licenses](../configuration/licenses.md). |
| Nodes for the cluster | Two or more. Then the cluster can elect a primary, and sign-in continues to work, while the homelab is down. |

## The homelab

The homelab compose file expects the Docker network of your Traefik as an external network with the name `traefik`. If your network has a different name, change the name in the compose file.

Cluster mode needs WireGuard in the kernel of the homelab host, for the mesh interface `hg-mesh`. The setting `tunnels.native: true` also needs it. WireGuard is part of Linux 5.6 and later.

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

The Docker daemon of the homelab and the nodes need access to the container registries.

The orchestrator needs no inbound access.
