# Clustered Pangolin

In cluster mode, the Pangolin of each node is one instance of a single Pangolin EE cluster. This page tells you how the instances share the work.

## The image

Each node runs the PostgreSQL build of the configured image. The tag `ee-<version>` becomes `ee-postgresql-<version>`. The default of `pangolin.pangolin_image` is `docker.io/fosrl/pangolin:ee-1.26.0`.

## Reads and writes

Pangolin sends writes to the current primary through `cluster-proxy`. It reads from the database member on its own node. A sign-in, and the use of the session at once, works through a node approximately 300 ms from the primary. The end-to-end tests check this with added latency on the mesh.

## Sites and resources

Each node is its own region. The homelab gets one site for each node, with the name `<site_name>-<node id>`, for example `homelab-na1`. HomeGAP creates each site through its node. Thus the connector of the site always uses the exit node of that node.

Each service is one resource with a target on the site of every node. HomeGAP applies the resources one time in each pass, through the first node that is ready. A node routes only through its own target. Thus every node keeps its own tunnel to the homelab, and no node relays through another node.

Some things stay separate on each node:

- The private resources list the site of every node that serves them. A client uses the nearest node when it connects.
- Each node has its own health resource, `hg-health-<node id>`, and its own speed test host.
- The passthrough ports are the same on every node.

DNS sends a hostname only to a node that has its own target for it.

Traefik reads the routes of Pangolin from a file (`traefik.file_mode`). Each node writes this file for its own sites. The passthrough routes of HomeGAP are in the same directory.

## The client address

The clients use one address, `<client_hostname>.<domain>` (default `pangolin.<domain>`). Every node serves the login pages and the API of Pangolin on this address. A geo record with a health monitor sends it to the nearest healthy node. Thus sign-in and device enrollment work while the homelab is down.

Passthrough never captures this address.

## Gerbil

The API of Gerbil is available only over the mesh, on the mesh address of the node. Thus the other Pangolin instances of the cluster can reach it. Docker starts after the mesh interface.

## One admin, one API key, one license

The first node creates the admin, the org and the API key of the cluster. HomeGAP keeps the admin and the server secret in the state of the orchestrator and on every node. Thus you can recover them from any node.

HomeGAP activates `pangolin.license_key_env` one time, through the first node that can activate it. Then it tells the other nodes to read the license again. HomeGAP ignores a `license_key_env` on a node. See [Pangolin EE licenses](../configuration/licenses.md).

## Upgrades

HomeGAP updates the nodes one at a time. The first node that starts a new Pangolin version migrates the database. Before this, it saves a dump of the database. If that update fails, the rollback also restores the dump. See [Node updates and rollback](../operations/node-updates.md).

## Removal of a node

When you decommission a node, HomeGAP also removes what the node left in the Pangolin of the cluster, on the next pass:

- The homelab site of the node.
- The exit site of the node.
- The exit node that its Gerbil registered. HomeGAP finds it by its region, which is the node id.

HomeGAP removes the leftovers of nodes from earlier removals in the same way. See [Manage nodes](../operations/nodes.md).
