# Manage nodes

This page tells you how to add, remove, re-provision and pause nodes, and how to work on a node by hand.

## Add a node

Use the add-node wizard, or add an entry to `nodes[]` in the config. HomeGAP provisions the node on the next pass. It adds the node to DNS only after the stack of the node is up. See [Add a node](../getting-started/add-node.md).

The API call is `POST /api/nodes`. It adds the node to the config file.

## Remove a node

A removal has two parts. First you remove the node from the config, and its DNS records drain. Then you decommission the node, and HomeGAP removes its stack from the server.

1. Click **Remove** on the **Nodes** page. Or remove the node from `nodes[]` in the config file. The DNS records of the node drain on the next pass.
2. Make sure that the node has the status `pending decommission`. Its stack continues to run.
3. Click **Decommission**.
4. Type the node id to confirm.

The API calls are `DELETE /api/nodes/{id}` and `POST /api/nodes/{id}/decommission?confirm={id}`.

Until the decommission, you can undo the removal. The dashboard offers **Undo** for 30 seconds. After that, add the node to the config again with the same id. The node stays in the mesh until the decommission, so it needs no new setup.

The decommission does these steps on the node:

1. It stops the stack and removes its containers and volumes.
2. It removes the exit interface (`hg-exit`) and the mesh interface (`hg-mesh`).
3. It removes the install directory, with the data and the secrets of the stack.

Then HomeGAP removes the node from its state. The server itself continues to run. Delete it at your provider. If you add the node again, it starts as a new node.

!!! danger "Warning"
    A decommission deletes the install directory of the node (`/opt/homegap` by default). You cannot undo it.

### If the teardown fails

If HomeGAP cannot connect to the node, or a step of the teardown fails, the node stays on the **Nodes** page with the status `teardown failed`. The activity log shows the error. The stack and the secrets of the node can still be on the server.

- To try again, click **Decommission again**.
- If the server is gone, click **Forget node**. HomeGAP then removes the node from its list and does not connect to the server. The API call is `POST /api/nodes/{id}/decommission?confirm={id}&force=true`.

!!! danger "Warning"
    **Forget node** leaves the stack and the secrets on a server that still runs. Use it only after you delete the server at the provider.

## Re-provision a node

Click **Re-provision** on the **Nodes** page. HomeGAP runs each provisioning step again: it writes the stack and restarts the containers whose files changed. The node continues to serve during the run. The API call is `POST /api/nodes/{id}/provision`.

HomeGAP can build each node again from an empty server, so you can destroy and recreate a VPS. A rebuilt server has a new host key. In the wizard, click **Forget the old host key and retry**.

## Pause

You can pause at three levels:

- Everything, with **Pause all** at the bottom of the menu.
- One node, with **Pause** on the **Nodes** page.
- One service, with **Pause service** on the **Services** page, or with the label `homegap.pause`.

The dashboard asks you to confirm each pause. A resume needs no confirmation.

| Level | What HomeGAP does during the pause |
| --- | --- |
| Everything | It only observes. It applies nothing to the nodes, DNS or the mesh. |
| A node | It does not provision the node and does not repair its drift. Config changes and updates for the node wait. |
| A service | It holds the DNS records of the service. The routes of the service stay on the nodes. |

The API call is `POST /api/pause` with a body like this:

```json
{"scope": "node", "id": "na1", "paused": true}
```

The `scope` is `global`, `node` or `service`.

## The management name

Each node has a management name, `node-<id>.<domain>` by default. To use a different name, set `nodes[].management_host`. The name has its own plain DNS record, with the address of that node only. The name is on the certificate of the nodes, and the `tls-mgmt` check reads the certificate that the node serves for it.

## Work on a node by hand

The stack is in `/opt/homegap` (`node_stack.install_dir`). To open a shell there, use the **Terminal** action of the node. See [The dashboard](dashboard.md#terminal).

!!! warning "Caution"
    A change that you make by hand is drift. HomeGAP repairs it on the next node check. To keep the change, pause the node first.

## When the homelab loses every node

If the config has two or more nodes and none of them answers over SSH, the dashboard shows a banner. The activity log records the start and the end. The nodes continue to serve.

Two providers rarely fail at the same moment, so the first thing to check is the internet connection of the homelab. If the **Mesh** panel shows handshakes between the nodes during the outage, the problem was the connection of the homelab. See [Failure modes](failure-modes.md).

## Node slots

Each node gets a permanent slot, which the orchestrator keeps in its state. The slot gives the node its mesh address and its exit address. A new node takes the lowest free slot. A removal or a new order of nodes never moves a different node. HomeGAP has 32 slots, so 32 nodes is the limit. See [Mesh](../reference/mesh.md).
