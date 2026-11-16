# Node updates and rollback

HomeGAP updates the stacks of the nodes one at a time, and it rolls back a failed update. This page tells you how an update works and how to recover from a failure.

## Provisioning in the background

Provisioning runs in the background. A slow node never delays the health checks, the mesh or DNS for the other nodes. Examples of a slow node are a first install or a slow Docker download.

During provisioning, the **Nodes** page shows the current phase of the node, for example `writing and starting the stack (2 of 3)`. The phases are:

1. `preparing the host`: the preflight checks, Docker, the firewall and the TCP tuning.
2. `backing up the stack`: only for an update.
3. `writing and starting the stack`: HomeGAP writes the files and runs `docker compose up -d`. Then it waits up to 2 minutes for Traefik to answer.
4. `verifying`: the containers `traefik`, `homegap-maintenance` and `homegap-agent` must run, and a process must listen on port 443.

## A rolling update

Some changes alter the stack of nodes that are already healthy. Examples are a new image version, `node_stack.crowdsec` and `node_stack.tcp_tuning`. HomeGAP sends such a change to one node at a time:

1. HomeGAP saves the stack that runs on the node, in `<install_dir>/.homegap-rollback`. The copy has `compose.yml`, `.env` and the `config` folder. It does not have the logs of Traefik or the data of CrowdSec.
2. HomeGAP updates the node. The node must then pass the `verifying` phase.
3. If a step fails, HomeGAP restores the saved stack and starts it again. The node continues to serve on the old stack.

After an update that succeeds, HomeGAP removes the Docker images that no container on the node uses.

The routes of the services, the spec of the agent and the certificate are not a part of the stack. HomeGAP writes them to a node when they change, with no rollout. No container starts again for them.

## A failed update

A failed update holds the rollout. HomeGAP does not update other nodes, and the dashboard shows the banner "Update rollout held." with the node. To recover, do one of these actions:

- Correct the cause on the node. Then click **Retry update**. HomeGAP applies the update to that node again.
- Click **Skip update**. The node stays on its current stack, and the rollout continues with the other nodes.
- Change the config. HomeGAP tries the new desired stack on the failed node first, and then on the other nodes. If you revert the change, the failure clears.

The API calls are `POST /api/nodes/{id}/update/retry` and `POST /api/nodes/{id}/update/skip`.

If the rollback also fails, the **Nodes** page shows the node with `update failed` and the provisioning error. Correct the cause on the node. Then click **Retry update**.

## What does not wait

New nodes and drift repairs do not wait for a rollout.

If the first provisioning of a node fails, HomeGAP tries again after 5 minutes. To try again immediately, click **Re-provision**.

The steps on the host (Docker, the firewall and the TCP tuning) are idempotent. HomeGAP does not roll them back.

## Drift

Every `reconcile.node_check_interval` (default 5 minutes), HomeGAP checks each node over SSH. It compares each file of the stack with the file that it wrote, and it checks that the three containers run. It repairs the changes that somebody makes outside HomeGAP. To keep a change that you make by hand, pause the node. See [Manage nodes](nodes.md#pause).

## Preview the stack of a node

To see the stack that HomeGAP writes to a node, run this command. The output does not show the secrets. It reads only the config file, so it has no services from labels.

```bash
docker compose exec orchestrator homegap render-node na1
```

## Move the SSH port of a node

To move the SSH server of a node to a different port, change `nodes[].ssh_port` in the config. HomeGAP moves the server in the next pass. A reason to do this is a service that needs port 22 on the nodes. See [Raw TCP and UDP ports](../services/raw-ports.md).

HomeGAP keeps a way in at each step:

1. Over the old port, it makes the SSH server listen on the old port and on the new port. It opens the new port in the firewall.
2. It connects on the new port.
3. Over the new port, it makes the SSH server drop the old port. The firewall closes the old port in the same pass.

If the connection on the new port fails, HomeGAP restores the old ports. The activity log has the error "the SSH port stays N", and HomeGAP tries the move again in each pass. Until the move is complete, HomeGAP uses the old port for the node. A cause of a failed connection is a firewall of your provider that does not have the new port open.

HomeGAP does not move the port during a dry run, a pause of all changes or a pause of the node.

The ports are in the file `/etc/ssh/sshd_config.d/00-homegap-port.conf` on the node.

!!! warning "Caution"
    Keep the console of your provider ready for the first move of a node. It is the way in if the SSH server and HomeGAP disagree about the port.
