# Glossary

This page defines the terms that the HomeGAP docs use.

**Blueprint**
: A Pangolin file that declares resources. HomeGAP applies one blueprint for each resource, and an optional base blueprint. See [Pangolin options](../configuration/pangolin.md#the-base-blueprint).

**Bunny DNS**
: The DNS service of bunny.net. It sends each viewer to the nearest healthy node. See [DNS routing and health checks](../configuration/dns.md).

**Cluster mode**
: The mode with a `cluster:` key in the config. All nodes are one Pangolin EE cluster on a shared database. See [Cluster mode](../cluster/index.md).

**Connector**
: A Newt process that connects the homelab to one node through WireGuard. The tunnel supervisor runs one connector for each node. See [How HomeGAP works](../getting-started/how-it-works.md).

**Desired state**
: The Docker labels and the config file, and nothing else. The orchestrator moves the actual state toward it. See [How HomeGAP works](../getting-started/how-it-works.md#reconciliation).

**Drift**
: A change on a node that HomeGAP did not make. The node check repairs it. See [Node updates and rollback](../operations/node-updates.md#drift).

**Exit node**
: An exit to the internet for the Pangolin apps, through a node or through the homelab. See [Exit nodes](../cluster/exit-nodes.md).

**Front door**
: The homelab Traefik container with the root domain in `homegap.expose`. It claims the root and, with `homegap.wildcard`, `*.<domain>`. See [Services](../services/index.md).

**Geo record set**
: A set of DNS records for one name, one for each node, with geo routing and a health monitor. See [DNS routing and health checks](../configuration/dns.md#the-records).

**Gerbil**
: The WireGuard server of Pangolin on each node.

**Health resource**
: A resource on each node that sends the Bunny probe through the tunnel to the front door. See [Traffic paths](../services/traffic-paths.md#health-resource).

**Homelab**
: The home servers with Docker, the homelab Traefik and the services. It has no inbound ports.

**Management host**
: The name of the Pangolin API of a node, by default `pangolin-<id>.<domain>`. See [Manage nodes](../operations/nodes.md#the-management-host).

**Mesh**
: The private WireGuard network `hg-mesh` between the homelab and every node, in cluster mode. See [Cluster mesh](../cluster/mesh.md).

**Named service**
: A service under `homegap.services.<name>.` on a container that can also have other services. Its key is `<host slug>--<name>`. See [Named services](../services/labels.md#named-services).

**Newt**
: The tunnel client of Pangolin. HomeGAP runs it on the homelab as connectors. With `cluster.exit_nodes.nodes`, each node also runs one as its exit connector.

**niceId**
: The fixed id of a Pangolin resource. The ids of the resources that HomeGAP manages start with `hg-`. HomeGAP deletes only stale `hg-*` resources.

**Node**
: A VPS that runs Pangolin, Gerbil and Traefik. Node ids are like `na1`, `eu1` and `as1`. See [Prepare the servers](../getting-started/servers.md).

**Olm**
: The Pangolin client in the Pangolin apps.

**Orchestrator**
: The `homegap run` process on the homelab. It reads the labels and manages the nodes, the tunnels and DNS. It is never in the traffic path of your services. It serves only its own pages: the dashboard and, if enabled, the status page and the speed test.

**Pangolin EE**
: The Enterprise Edition of Pangolin, by Fossorial. HomeGAP needs it for cluster mode and for wildcard resources in `terminate` mode. See [Pangolin EE licenses](../configuration/licenses.md).

**Pass**
: One run of the reconcile loop. See [How HomeGAP works](../getting-started/how-it-works.md#reconciliation).

**Passthrough**
: The TLS mode where the node reads only the SNI and forwards the encrypted stream to the homelab. See [Traffic paths](../services/traffic-paths.md).

**Port list**
: A list of raw ports in `homegap.port`, with entries like `7881/tcp`, `3478/tcp+udp` or `2222:22/tcp`. See [Port lists](../services/labels.md#port-lists).

**Primary and replica**
: The primary is the database member that accepts writes. A replica follows the primary. See [Cluster database](../cluster/database.md).

**Private path**
: A route that only Pangolin clients use to reach a public service, over a separate Docker network. See [Client addresses and private-only services](../services/client-addresses.md).

**Private resource**
: A Pangolin resource that only the Pangolin client can reach. See [Private services](../services/private.md).

**Rollout**
: An update of the node stacks, one node at a time. See [Node updates and rollback](../operations/node-updates.md).

**Site**
: A Pangolin object for a network that a connector serves. The homelab has a site on each node. See [Clustered Pangolin](../cluster/pangolin.md#sites-and-resources).

**Slot**
: The permanent tunnel slot of a node. It sets the tunnel subnet, the interface and the mesh address. See [Manage nodes](../operations/nodes.md#tunnel-slots).

**Standalone mode**
: The mode without a `cluster:` key. Each node runs its own Pangolin with SQLite. See [Standalone or cluster](../getting-started/choose-a-mode.md).

**Switch-back**
: The move of the database primary back to the homelab, after the homelab stays caught up for `cluster.primary.switch_back_after`. See [Cluster database](../cluster/database.md#switch-back).

**Terminate**
: The TLS mode where the node ends TLS. Pangolin sign-in needs it. See [Traffic paths](../services/traffic-paths.md#front-door-terminate).

**Tunnel supervisor**
: The `tunnels` container (`homegap tunnels`). It runs one Newt connector for each node. See [Install on the homelab](../getting-started/install.md).

**Vote**
: The etcd quorum of the cluster. It needs a majority to elect a primary. See [Cluster database](../cluster/database.md#failover).
