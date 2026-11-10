# Standalone or cluster

HomeGAP has two modes. This page helps you choose one before you deploy.

!!! warning "Caution"
    Choose the mode when you deploy. HomeGAP does not support the addition or removal of `cluster:` on a deployment that runs. A node that HomeGAP provisions in one mode keeps the stack of that mode until you remove the node and add it again.

## Standalone mode

In **standalone mode**, the config has no `cluster:` key. Each node runs its own Pangolin with its own SQLite database.

Private services and users are different on each node. If you need EE features, each node needs its own EE license key, because a key binds to one Pangolin instance. Standalone mode is simpler and has fewer containers.

## Cluster mode

In **cluster mode**, the config has a `cluster:` key. All nodes are one Pangolin EE cluster with one org, one set of users and one license.

The cluster uses a shared database with a member on the homelab and on every node. The database uses PostgreSQL under Patroni, Valkey with Sentinel, etcd for the vote and HAProxy. The members connect over a private WireGuard mesh, `hg-mesh`.

The Pangolin apps connect to `pangolin.<domain>`. Bunny DNS sends this name to the nearest healthy node. Thus the apps continue to work while the homelab is down, and sign-in also works. You manage users and devices on the **Access** page, and you can offer exit nodes.

The cluster uses the PostgreSQL build of Pangolin. The image tag `ee-<version>` becomes `ee-postgresql-<version>`.

We recommend cluster mode with two or more nodes. The vote needs a majority. With the homelab and only one node, the loss of either one stops all writes to the database.

## Comparison

| | Standalone | Cluster |
| --- | --- | --- |
| Pangolin instances | One independent Pangolin on each node. | One Pangolin EE cluster on all nodes. |
| Users and sign-in | A different set on each node. | One set of users for all nodes. |
| EE license keys | One key for each node. | One key for the cluster. |
| Homelab down | Public homelab services are down. The Pangolin of each node keeps its own state. | Public homelab services are down. The apps stay connected. New sign-ins work approximately 30 seconds later, when the nodes elect a primary. |
| Extra containers | None. | Each node runs five: etcd, PostgreSQL with Patroni, Valkey, Valkey Sentinel and HAProxy. The nodes also run a mesh probe. The homelab member runs four: etcd, PostgreSQL with Patroni, Valkey and Valkey Sentinel (no HAProxy). |
| Exit nodes | Not available. | Available. |
| **Access** page | Not available. | Available. |

## Enable cluster mode

To use all the defaults, add this line to the config file:

```yaml
cluster: {}
```

For the options, see the [Configuration reference](../configuration/reference.md). For the full description of the cluster, see [Cluster mode](../cluster/index.md).
