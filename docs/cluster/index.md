# Cluster mode

In cluster mode, all nodes run one Pangolin EE cluster. This page tells you what cluster mode gives you, what it needs and how to enable it.

## What cluster mode is

With `cluster:` in the config, the Pangolin of each node is one instance of a single Pangolin EE cluster. The instances use a shared database with a member on the homelab and on every node. The members connect over a private WireGuard mesh, `hg-mesh`. The cluster has one org, one set of users and one license.

## What you get

- The Pangolin apps connect to `pangolin.<domain>`, and DNS sends them to the nearest healthy node. The apps continue to work while the homelab is down, and sign-in also works. See [Clustered Pangolin](pangolin.md) and [Connect the Pangolin apps](pangolin-apps.md).
- The **Access** page on the dashboard manages users and devices. See [Users and devices](access.md).
- The Pangolin apps can send all traffic of a device through a node or through the homelab. See [Exit nodes](exit-nodes.md).
- One EE license key covers all nodes. See [Pangolin EE licenses](../configuration/licenses.md).

## The parts

Read the pages in this order. Each part builds on the part before it.

1. [Cluster mesh](mesh.md): the private WireGuard network between the members.
2. [Cluster database](database.md): PostgreSQL, Valkey and the vote.
3. [Clustered Pangolin](pangolin.md): the Pangolin instances on the shared database.
4. [Users and devices](access.md): the **Access** page.
5. [Connect the Pangolin apps](pangolin-apps.md): the devices of your users.
6. [Exit nodes](exit-nodes.md): exits to the internet for the apps.

!!! warning "Caution"
    Choose the mode when you deploy. HomeGAP does not support the addition or removal of `cluster:` on a deployment that runs. A node that HomeGAP provisions in one mode keeps the stack of that mode until you remove the node and add it again.

## Requirements

- Two or more nodes. The vote needs a majority. With the homelab and only one node, the loss of either one stops all writes.
- A decision about the role of the homelab, before the first start. The homelab can hold a copy of the database (the default) or only vote. See [The homelab as a vote only](database.md#the-homelab-as-a-vote-only).
- WireGuard in the kernel of the homelab host. It is part of Linux 5.6 and later.
- The cluster services of the shipped compose file: `homegap-mesh`, `homegap-mesh-agent`, `homegap-mesh-route`, `homegap-etcd`, `homegap-db`, `homegap-valkey` and `homegap-sentinel`. They start only with the profile `cluster`.

## Enable cluster mode

Cluster mode has two switches. The config tells the orchestrator to build the cluster. The profile tells Docker Compose to start the cluster services of the homelab. The orchestrator has no control of Docker, so it cannot start them.

1. Add this line to `.env`:

    ```text
    COMPOSE_PROFILES=cluster
    ```

2. Add `cluster:` to the config. To use all the defaults, add this line:

    ```yaml
    cluster: {}
    ```

3. Start the cluster services:

    ```bash
    docker compose up -d
    ```

If the config has `cluster:` and the cluster services do not run, the orchestrator cannot find the mesh agent. Then the orchestrator writes an error for the mesh of the homelab to its log. The error gives the line for `.env`. With the profile and without `cluster:` in the config, the cluster services start and wait.

The config has these keys:

| Key | Default | Rules |
| --- | --- | --- |
| `cluster.mesh.subnet` | `10.77.0.0/24` | A network address with a prefix from /16 to /26. Must not overlap `100.64.0.0/10`, the tunnel ranges of Pangolin. Must have space for the maximum of 32 nodes. |
| `cluster.mesh.port` | `51821` | A UDP port. Must be different from 51820 and 21820. |
| `cluster.homelab_role` | `database` | `database` or `vote`. See [The homelab as a vote only](database.md#the-homelab-as-a-vote-only). |
| `cluster.image` | `ghcr.io/drewcarlson/homegap-cluster:<tag>`. The tag is the value of `HOMEGAP_VERSION`. Without that variable, the tag is the version of the orchestrator. | Must not be empty. |
| `cluster.primary.preferred` | `homelab` | `homelab` is the only accepted value. |
| `cluster.primary.switch_back_after` | `10m` | The time that the homelab must stay caught up before it becomes the primary again. |
| `cluster.client_hostname` | `pangolin` | The first label of the client address, `<client_hostname>.<domain>`. |
| `cluster.exit_nodes.nodes` | `false` | Offers an exit through each node. |
| `cluster.exit_nodes.homelab` | `false` | Offers an exit through the homelab. |
| `cluster.exit_nodes.roles` | `[]` | The roles that can use the exits. |
| `cluster.exit_nodes.newt_image` | `docker.io/fosrl/newt:1.20.0` | The connector image for the exit of each node. |
| `cluster.tunnel_dns.resolvers` | `[8.8.8.8]` | The IP addresses of the DNS servers that the Pangolin apps can reach through the tunnel. Each app must have one of them as its upstream DNS server. An empty list disables this. See [DNS through the tunnel](pangolin-apps.md#dns-through-the-tunnel). |
| `cluster.tunnel_dns.roles` | `[Member]` | The roles that can use these DNS servers. |

## The order of first setup

HomeGAP prepares the cluster in this order:

1. HomeGAP prepares the mesh and the shared database. The homelab member comes first, then each node.
2. HomeGAP provisions the nodes one at a time until the Pangolin of the cluster exists. Thus only one node migrates the empty database.
3. The stack of each node waits until its database member is `streaming`.
