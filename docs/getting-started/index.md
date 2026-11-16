# Getting started

This page gives the full order of setup. Each step links to the page with the details.

## The order of setup

1. Read [How HomeGAP works](how-it-works.md).
2. Check the [Requirements](requirements.md).
3. [Prepare Bunny DNS](bunny-dns.md): a zone and an API key.
4. [Prepare the servers](servers.md): one VPS for each region.
5. [Install on the homelab](install.md): the stack, the config and the secrets. Start in dry run.
6. [Add a node](add-node.md) with the wizard on the dashboard, or by hand.
7. [Publish a service](first-service.md) and check it.
8. Configure the [sign-in](../vpn/sign-in.md) for the VPN.
9. Add users on the **Access** page. See [Users, machines and devices](../vpn/access.md).
10. [Connect the Pangolin apps](../vpn/pangolin-apps.md).

Steps 8 to 10 are necessary only for the VPN and for private services.

## What the orchestrator does after you start it

You do the steps above one time. After that, the orchestrator does this work by itself:

- It provisions each node: Docker, `ufw`, Traefik, the maintenance page and the agent of HomeGAP.
- It keeps the private mesh between the homelab and each node.
- It orders the certificate of the nodes from Let's Encrypt, for the names that a node terminates.
- It writes the routes of the Traefik of each node from the labels.
- It creates the Bunny geo records with their monitors. A new node gets DNS records only after its stack is up.

## Before production

Do these checks on the test domain:

- All nodes are green on the dashboard.
- Each name resolves to the correct node in each region.
- Failover works.
- The change between `passthrough` and `terminate` works.

When all checks pass, set `domain` and `bunny.zone` to your production domain and its zone. Then move that domain to Bunny DNS.
