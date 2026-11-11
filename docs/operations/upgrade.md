# Upgrade the homelab

Use `deploy/upgrade.sh` to upgrade HomeGAP on the homelab. The script keeps access up during the upgrade. This page tells you what the script does and how to use it.

## Run the script

Put `upgrade.sh` next to your compose file. Use it instead of `docker compose pull && docker compose up -d`:

```bash
./upgrade.sh
```

The script gives its arguments to every `docker compose` call. If your compose file has a different name, give it as an argument:

```bash
./upgrade.sh -f homelab-compose.yml
```

| Option | What it does |
| --- | --- |
| `--tunnels` | Recreates the `tunnels` container, also if its version does not change. |
| `--no-pull` | Uses the images that are already on the host. |
| `-h`, `--help` | Shows the help. |

## What the script does

1. It pulls the new images.
2. It recreates the orchestrator. Traffic never goes through the orchestrator, so this does not stop access. The script waits up to 120 seconds for the orchestrator to start. Then it recreates the mesh agent.
3. It moves the database primary and the Valkey primary to a node, and holds them there (`homegap maintenance start --minutes 30`).
4. It recreates the cluster containers of the homelab one at a time. It waits up to 5 minutes for each container to serve again.
5. It recreates the tunnel connectors, but only if their code or Newt is different.
6. It ends the hold (`homegap maintenance end`). The primaries return to the homelab after the switch-back delay of the cluster.

If the script cannot move the primaries, it stops and does not touch the cluster containers. Correct the cause and run the script again.

If a cluster container does not serve after 5 minutes, the script stops. The primaries stay on the nodes for 30 minutes at most.

## The mesh container

The script never recreates `homegap-mesh`. The mesh and every container in its network namespace restart with it. The script does recreate `homegap-mesh-agent`. The mesh interface stays up without the agent.

## The tunnels container

The `tunnels` container restarts every Newt connector at once. Thus every node loses the homelab for a few seconds.

The script recreates it only when the tunnels version of the new image is different. This version covers the code of the tunnel supervisor, its platform layer, the dependency versions and Newt. The script also recreates it when it does not run, or with `--tunnels`.

Until then, the container continues to run the previous image. Thus Docker also keeps that image.

## Merged compose projects

If the HomeGAP services are part of a different compose project with other names, set these environment variables:

| Variable | Default |
| --- | --- |
| `HG_ORCHESTRATOR` | `orchestrator` |
| `HG_TUNNELS` | `tunnels` |
| `HG_CLUSTER` | `homegap-etcd:etcd homegap-sentinel:sentinel homegap-valkey:valkey homegap-db:patroni` |

`HG_CLUSTER` is a list of `service:role` pairs, separated by spaces, in the order of the restarts.

## Standalone mode

The script asks the orchestrator to start maintenance only if the compose file has cluster services. In standalone mode, the orchestrator answers that the cluster is not enabled. The script pulls the images of the cluster containers, but it does not recreate them. It does not end a hold. It still updates the orchestrator and the tunnels.

If the compose file has no cluster services, the script skips the cluster steps.

## After the upgrade

After the upgrade, the cluster members on the nodes follow the cluster image of the homelab, pinned to its registry digest. HomeGAP updates one node in each pass, and only while every member serves and no maintenance runs. See [Cluster database](../cluster/database.md#updates-of-the-node-members).

The script does not update the node stacks (the Pangolin images). They follow the config. See [Node updates and rollback](node-updates.md).
