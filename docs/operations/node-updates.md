# Node updates and rollback

HomeGAP updates the stacks of the nodes one at a time, and it rolls back a failed update. This page tells you how an update works and how to recover from a failure.

## Provisioning in the background

Provisioning runs in the background. A slow node never delays the health checks, the tunnels or DNS for the other nodes. Examples of a slow node are a first install or a slow Docker download. During provisioning, the node shows `provisioning…` or `updating…` on the dashboard.

## A rolling update

Some changes alter the stack of nodes that are already healthy. Examples are a new image version, the MTU, the tuning and raw ports. HomeGAP sends such a change to one node at a time:

1. HomeGAP saves the running stack on the node, in `<install_dir>/.homegap-rollback`. The copy has every managed file. When the Pangolin image changes, it also has the database of Pangolin, because a newer version migrates it.
2. HomeGAP updates the node. The node must then pass these checks:
    - The health check of Pangolin.
    - The API bootstrap.
    - The final checks: the UDP ports of Gerbil and the Integration API.
    - If its tunnel is up before the update, the tunnel must connect again.
3. If a step fails, HomeGAP restores the saved stack and restarts it. The node continues to serve on the old stack.

In cluster mode, the first node that starts a new Pangolin version also saves a dump of the database. If that update fails, the rollback restores the dump.

## A failed update

A failed update holds the rollout. HomeGAP does not update other nodes, and the dashboard shows a banner with the error. To recover, do one of these actions:

- Correct the cause on the node. Then click **Retry update**. HomeGAP applies the update to that node again.
- Click **Skip update**. The node stays on its current stack, and the rollout continues with the other nodes.
- Change the config. HomeGAP tries the new desired stack on the failed node first, and then on the other nodes. If you revert the change, the failure clears.

## What does not wait

New nodes and drift repairs do not wait for a rollout.

If the first provisioning of a node fails, HomeGAP tries again after five minutes. To try again at once, click **Re-provision**.

The steps on the host (Docker, the firewall and the TCP tuning) are idempotent. HomeGAP does not roll them back.

## Drift

Every `reconcile.node_check_interval` (default 5 minutes), HomeGAP checks each node over SSH. It repairs the changes that somebody makes outside HomeGAP. To keep a change that you make by hand, pause the node. See [Manage nodes](nodes.md#pause).

## Preview the stack of a node

To see the stack that HomeGAP writes to a node, run this command. The output does not show the secrets.

```bash
docker compose exec orchestrator homegap render-node na1
```
