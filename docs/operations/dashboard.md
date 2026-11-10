# The dashboard

The orchestrator serves a dashboard on `dashboard.bind`. This page tells you how to reach it safely and what each page shows.

!!! danger "Warning"
    If the dashboard is public, anybody can control every node. Reach it only through a private Pangolin resource or an SSH tunnel. See [Private services](../services/private.md).

## Access

An optional bearer token protects `/api`. Set it with `dashboard.auth_token_env` or `dashboard.auth_token_file`. When a token is set, the dashboard asks for it and keeps it in the browser. A change of the token needs a restart of the orchestrator.

The dashboard updates live. It uses server-sent events (`/api/events`), not polling.

## The header

The header shows the state of the pass, and two controls:

- **Resync** queues a pass now.
- **Pause** stops all changes everywhere. HomeGAP then only observes. **Resume** starts the changes again.

<!-- SCREENSHOT: docs/img/dashboard/header.png | the header with the pass state, Resync and Pause -->

## The pages

### Overview

"Health of nodes, tunnels, services and DNS at a glance". The page has a world map with the nodes and the homelab. See [Maps and locations](maps.md).

<!-- SCREENSHOT: docs/img/dashboard/overview.png | the Overview page with the world map -->

### Nodes

"Regional edge nodes provisioned over SSH". For each node, the page shows:

- The reachability.
- The health of Docker, Pangolin, Gerbil and Traefik, with one check for each container.
- The expiry of the certificate.
- The state of the tunnel.
- The last provision, and the drift.
- The facts of the node, for example the OS, the kernel, `tcp_cc` and `edge tcp`.

The actions on a node are **Re-provision**, **Pause** or **Resume**, and **Remove**. After a removal, the action is **Confirm decommission**. During a held rollout, the node also has **Retry update** and **Skip update**. See [Manage nodes](nodes.md) and [Node updates and rollback](node-updates.md).

The **Add node** button opens the wizard. See [Add a node](../getting-started/add-node.md). In cluster mode, the page also has the **Cluster mesh** and **Cluster database** panels. See [Cluster mesh](../cluster/mesh.md) and [Cluster database](../cluster/database.md).

<!-- SCREENSHOT: docs/img/dashboard/nodes.png | the Nodes page with the node table and its checks -->

### Services

"Containers published through homegap.* labels". The page shows the health of each service in each region, and the label errors and warnings. Each service has **Pause** or **Resume**.

<!-- SCREENSHOT: docs/img/dashboard/services.png | the Services page with the health in each region -->

### DNS

"Bunny DNS records managed by HomeGAP". The page shows the records, the state of their monitors and if they match the desired state.

<!-- SCREENSHOT: docs/img/dashboard/dns.png | the DNS page with the records and the monitor state -->

### Tunnels

"Newt connectors from the homelab to each node". The page shows the state of each process, its restarts and its throughput.

<!-- SCREENSHOT: docs/img/dashboard/tunnels.png | the Tunnels page with one connector for each node -->

### Access

"Who can sign in to the Pangolin apps, and their devices". This page is available only in cluster mode. See [Users and devices](../cluster/access.md).

### Speed test

"Latency and throughput through the real traffic path". See [Speed tests](speed-tests.md).

<!-- SCREENSHOT: docs/img/dashboard/speedtest.png | the Speed test page with the map and the results -->

### Activity

"Reconcile log, newest first". The log is grouped by domain.

<!-- SCREENSHOT: docs/img/dashboard/activity.png | the Activity page with the reconcile log -->

### Diagnostics

"Container logs and a read-only snapshot of each node". See [Diagnostics and troubleshooting](troubleshooting.md).

<!-- SCREENSHOT: docs/img/dashboard/diagnostics.png | the Diagnostics page with the logs of a container -->

### Config

"Desired vs. actual state, secrets and the config file". The page shows:

- The diff between the desired state and the actual state.
- The effective desired state.
- Each referenced secret, as present or missing. The page never shows the values.
- The config file.

The **Locations** page is under **Config**. See [Maps and locations](maps.md).

<!-- SCREENSHOT: docs/img/dashboard/config.png | the Config page with the diff and the secrets -->

## Node checks

The Nodes page shows these checks:

| Check | What it shows |
| --- | --- |
| `ssh` | If the orchestrator can reach the node over SSH. |
| `docker` | If Docker is on the node. |
| One check for each container | `running`, with its Docker health in parentheses. `starting` is degraded, and `unhealthy` is down. |
| `gerbil-udp` | If Gerbil answers on 51820/udp. |
| `pangolin-api` | If the orchestrator can reach the Integration API of Pangolin on the node. |
| `tunnel` | If the homelab site on the node is online, with the Newt version. |
| `mode` | In dry run, it shows that HomeGAP does no remote access. |
| `cluster-proxy` | In cluster mode, where HAProxy sends PostgreSQL and Valkey. See [Cluster database](../cluster/database.md#the-cluster-proxy-check). |
| `ee-license` | The state of the EE license. |

## Banners

The dashboard shows a banner in these cases:

- A node update fails and holds the rollout. See [Node updates and rollback](node-updates.md).
- Two or more nodes are configured, and none of them answers over SSH. The banner tells you that the homelab lost every node. The log records the start and the end. The nodes continue to serve.

## Documentation

The **Documentation** link at the bottom of the sidebar opens these docs at `/docs/`, for the version that runs.
