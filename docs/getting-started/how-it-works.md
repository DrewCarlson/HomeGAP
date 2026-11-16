# How HomeGAP works

This page gives the model of the system: the parts, the path of a request, the VPN, the reconcile loop and failover. Later pages give the details.

## The parts

| Part | What runs there |
| --- | --- |
| Homelab | Docker, the homelab Traefik (the **front door**), your services and the HomeGAP stack. |
| Nodes | Traefik (ports 80 and 443), the maintenance page (`homegap-maintenance`) and the agent of HomeGAP (`homegap-agent`). With `node_stack.crowdsec`, also CrowdSec. |
| Bunny DNS | The DNS zone for your domain, with geo routing and health monitors. |

The HomeGAP stack on the homelab has these containers:

| Container | Job |
| --- | --- |
| `orchestrator` | The control plane (`homegap run`). It reads the labels and the config, and it writes the nodes, the mesh and the DNS records. |
| `homegap-mesh` | A small container that holds the network namespace of the mesh. |
| `homegap-mesh-agent` | Keeps the WireGuard interface, the forwards to your containers and the home end of the VPN in that namespace. |
| `homegap-sign-in` | The public sign-in for the apps of the VPN. |
| `docker-proxy` | A read-only filter on the Docker socket. The orchestrator reads containers and events through it. |

The **mesh** is a private WireGuard network (`hg-mesh`) between the homelab and each node. It is always on. Every route from a node to the homelab goes over it. The homelab has no inbound ports: it dials out to each node, so it works behind NAT. For the addresses and the keys, see [The mesh](../reference/mesh.md).

## The path of a request

This is the default path: the front door in `passthrough` mode.

1. A viewer resolves a name. Bunny DNS answers with the nearest healthy node.
2. The viewer connects to that node on port 443.
3. The Traefik of the node reads the TLS server name. It matches the root domain or any single label under the domain.
4. Traefik sends the stream, still encrypted, to the mesh address of the homelab.
5. The mesh namespace of the homelab forwards the stream to the homelab Traefik.
6. The homelab Traefik ends TLS and routes the request to the service.

The node never sees plaintext. Authelia and the other parts of your homelab continue to work without changes.

There are two other paths. With `homegap.tls: terminate`, the node ends TLS for the name. HomeGAP orders the certificate of the node from Let's Encrypt with the DNS challenge, only for the names that a node terminates. For a raw TCP or UDP port, the kernel of the node forwards the packets over the mesh. See [Traffic paths](../services/traffic-paths.md) and [Raw ports](../services/raw-ports.md).

The orchestrator never carries traffic. It talks to the nodes over SSH, to the mesh agent through a shared folder, and to Bunny through the Bunny API.

## Sign-in and VPN

HomeGAP has its own sign-in for the Pangolin apps and the `olm` client. The address of the sign-in is `sign_in.url`, and each node serves it. A user signs in with a HomeGAP password or through an OpenID Connect provider.

A device of the VPN connects to a node, and the node forwards it to the homelab. Private services get names that only the devices of the VPN resolve and reach. The roles of the user decide which private services a device can reach. HomeGAP denies everything else.

A device can also select an exit. Then all its traffic leaves for the internet from a node or from the homelab. See [Sign-in and VPN](../vpn/index.md).

## Reconciliation

The orchestrator is a reconciler. It moves the actual state toward the **desired state**. The desired state is the Docker labels and the config file, and nothing else.

The work has independent domains. A failure in one domain does not stop the others.

| Domain | What it does |
| --- | --- |
| Discovery | Reads the `homegap.*` labels from Docker, through the `docker-proxy` container. |
| Nodes | Uses SSH to change a bare Ubuntu VPS into a node: Docker, `ufw`, the stack and a final check. A drift check at an interval (default `5m`) finds changes from outside HomeGAP. |
| Mesh | Gives each node a permanent slot and a mesh address. Keeps the WireGuard peers of the homelab and of each node. |
| Routes | Writes the routes of the Traefik of each node, and the forwards of raw ports. Traefik loads the routes without a restart. |
| DNS | Keeps the Bunny geo record sets for the root, the wildcard and each dedicated hostname. Also keeps a plain record for the management name of each node, `node-<id>.<domain>`. |

A **pass** is one run of the reconcile loop. Passes start in three ways:

- On Docker events, after a debounce (default `2s`).
- At an interval, as a full resync (default every `60s`).
- On demand, with **Resync** on the dashboard.

## Health and failover

Each geo record has a Bunny monitor (`bunny.monitor`, default `http`). Bunny sends a request to `http://<node-ip>/`. The node sends this request over the mesh to the health check path of the front door (`homegap.healthcheck`). Thus a node is healthy only when the node is up, its mesh link is up and the origin answers.

Bunny removes the offline members from a record set. Viewers then resolve to the next-nearest node. The records have a TTL of 60 seconds (`bunny.ttl`), so a failover also waits for the caches of the resolvers.

When the homelab is down, a node serves the maintenance page for the names for which the node ends TLS. The node checks the homelab every 2 seconds. After three failed checks in a row, it answers with the page and HTTP status 503. For a passthrough name, the node closes the connection at once, because it holds no certificate for the name.

When the homelab is down, a node also signs devices in for the exits only. The exits through a node continue to work. The private services of the homelab do not.

For the monitors and the routing options, see [DNS routing and health checks](../configuration/dns.md). For each failure and its effect, see [Failure modes](../operations/failure-modes.md).
