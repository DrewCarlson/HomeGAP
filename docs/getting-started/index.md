# Getting started

This page gives the full order of setup. Each step links to the page with the details.

## The order of setup

1. Read [How HomeGAP works](how-it-works.md).
2. Check the [Requirements](requirements.md).
3. Choose [standalone or cluster mode](choose-a-mode.md).
4. [Prepare Bunny DNS](bunny-dns.md): a zone and an API key.
5. [Prepare the servers](servers.md): one VPS for each region.
6. [Install on the homelab](install.md): the compose file, the config and the secrets. Start in dry run.
7. [Add a node](add-node.md) with the wizard on the dashboard, or by hand.
8. [Publish a service](first-service.md) and check it.

In cluster mode, do two more steps. Add users on the **Access** page ([Users and devices](../cluster/access.md)). Then [connect the Pangolin apps](../cluster/pangolin-apps.md).

## What the orchestrator does after you start it

You do the steps above one time. After that, the orchestrator does this work by itself:

- In cluster mode, it prepares the private mesh and the shared database. The homelab member comes first, then each node.
- It provisions each node: Docker, `ufw`, Pangolin EE, Gerbil and Traefik. It also installs the secrets and the certificate, creates the admin account, the org and the API key, and activates the license.
- It creates the homelab sites on the Pangolin of each node, and one Newt connector for each node.
- It creates the Pangolin resources, and the Bunny geo records with their monitors.

## Before production

Do these checks on the test domain:

- All nodes are green on the dashboard.
- Each name resolves to the correct node in each region.
- Failover works.
- The change between `passthrough` and `terminate` works.

When all checks pass, set `domain` to your production domain. Then move that domain to Bunny DNS.
