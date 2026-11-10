# How HomeGAP works

This page gives the model of the system: the parts, the path of a request, the reconcile loop and failover. Later pages give the details.

## The parts

| Part | What runs there |
| --- | --- |
| Homelab | Docker, the homelab Traefik (the **front door**), your services, the orchestrator (`homegap run`) and the tunnel supervisor (`homegap tunnels`, the `tunnels` container). In cluster mode, also a cluster member with PostgreSQL, Valkey and etcd. |
| Nodes | Pangolin EE, Gerbil (WireGuard) and Traefik. In cluster mode, also a cluster member. |
| Bunny DNS | The DNS zone for your domain, with geo routing and health monitors. |

The homelab has no inbound ports. All connections between the homelab and the nodes start at the homelab.

## The path of a request

This is the default path: the front door in `passthrough` mode.

1. A viewer resolves a name. Bunny DNS answers with the nearest healthy node.
2. The viewer connects to that node on port 443.
3. A Traefik TCP router on the node reads the TLS SNI. It matches the root domain or any single label under the domain.
4. The router forwards the stream, still encrypted, into a Pangolin TCP resource.
5. The stream goes through the WireGuard tunnel to the homelab Traefik. The Newt connector for that node opens this tunnel outbound from home.
6. The homelab Traefik ends TLS and routes the request to the service.

The node never sees plaintext. Authelia and the other parts of your homelab continue to work without changes. For the other paths, see [Traffic paths](../services/traffic-paths.md).

The orchestrator is the control plane. It reads the Docker labels and writes the connector specs for the tunnel supervisor. It talks to the nodes over SSH and through the Pangolin API, and to Bunny through the Bunny API. It never carries traffic.

## Reconciliation

The orchestrator is a reconciler. It moves the actual state toward the **desired state**. The desired state is the Docker labels and the config file, and nothing else.

The work has six independent domains. A failure in one domain never stops the others.

| Domain | What it does |
| --- | --- |
| Discovery | Reads the `homegap.*` labels from the Docker socket (a read-only mount). If discovery fails, the orchestrator skips the Resources and DNS domains for that pass. An empty view can never delete anything. |
| Nodes | Uses SSH to change a bare Ubuntu VPS into a Pangolin EE node. The steps are preflight, Docker, `ufw`, the compose stack, the secrets, a health wait, the bootstrap and a final check. |
| Tunnels | Keeps a Pangolin site for the homelab on each node. Writes one Newt connector spec for each node, for the tunnel supervisor. The spec has the addresses of the node's management host, the same values as its DNS records. The supervisor pins them in the hosts file of the `tunnels` container, so a connector does not depend on public DNS. |
| Resources | Creates the Pangolin resources through blueprints, one for each resource, with fixed `hg-*` niceIds. Writes the Traefik routes of each node: SNI passthrough, health and the management API. |
| DNS | Keeps the Bunny geo record sets for the root, the wildcard and each dedicated hostname. Also keeps a plain record for the management host of each node. Asks the nameservers of the zone directly if they serve each plain record. |
| Observability | Collects the health of nodes, tunnels, resources and DNS for the dashboard. |

In the Nodes domain, the bootstrap is headless. It creates the admin, the org, an API key with the least privilege and the license. Each step is idempotent. A drift check at an interval repairs changes from outside HomeGAP.

In the Resources domain, the orchestrator deletes only stale `hg-*` resources. It never touches a resource that it does not manage. Traefik on the node reloads the routes without a restart.

A **pass** is one run of the reconcile loop. Passes start in three ways:

- On Docker events, after a debounce (default `2s`).
- At an interval, as a full resync (default every `60s`).
- On demand, with **Resync** on the dashboard.

Each pass applies only the minimum difference.

## Health and failover

Each geo record has a Bunny HTTP monitor. Bunny sends a request to `http://<node-ip>/` every 30 seconds from three regions. On the node, this request goes through the tunnel to the health check path of the front door (`homegap.healthcheck`). Thus a node is healthy only when the node is up, its tunnel is up and the origin answers.

- If the origin is down, the probe gets a 502.
- If the tunnel is down, the probe fails within 5 seconds.

Bunny removes the offline members from a record set. Viewers then resolve to the next-nearest node. When all members are offline, Bunny returns all of them (fail-open).

A real failover takes the health interval, plus the 60-second TTL, plus the time that resolvers cache the answer. This is approximately 1 to 5 minutes.

When the homelab is down, the nodes show a "back shortly" page for passthrough sites. The page has HTTP status 503 and starts approximately 15 seconds after the nodes lose the homelab. Raw ports and resources that the node terminates fail.

For the monitors and the routing options, see [DNS routing and health checks](../configuration/dns.md).

## Standalone and cluster

Without the `cluster:` key, each node runs an independent Pangolin. With `cluster:`, the Pangolin of each node is one instance of a single Pangolin EE cluster. The cluster has a shared database with a member on the homelab and on every node.

The members connect over a private WireGuard mesh. The homelab holds the database primary while it is up. While it is down, the nodes elect one of themselves. To compare the two modes, see [Standalone or cluster](choose-a-mode.md).
