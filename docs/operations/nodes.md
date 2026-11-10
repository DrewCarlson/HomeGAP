# Manage nodes

This page tells you how to add, remove, re-provision and pause nodes, and how to work on a node by hand.

## Add a region

Use the add-node wizard, or add an entry to `nodes[]` in the config. HomeGAP provisions the node and adds it to DNS on the next pass. See [Add a node](../getting-started/add-node.md).

## Remove a region

1. Remove the node from the config. Or click **Remove** on the **Nodes** page, which writes the config again. The DNS records of the node drain on the next pass.
2. The stack on the node continues to run. When you are ready, click **Confirm decommission**.
3. Type the node id at the prompt "Type the node id to stop its stack and forget it:".

The API call is `POST /api/nodes/{id}/decommission?confirm={id}`.

HomeGAP removes only its own records. In cluster mode, the decommission also does these steps:

- It removes the homelab site and the exit site of the node from the Pangolin of the cluster.
- It removes the exit node of the node from the Pangolin of the cluster.
- It removes the member of the node from the vote, and deletes its data.

## Re-provision a node

Click **Re-provision** on the **Nodes** page. The API call is `POST /api/nodes/{id}/provision`.

You can destroy and recreate a VPS safely, because HomeGAP can build each node again from nothing. A rebuilt server has a new host key. In the wizard, click **Forget the old host key and retry**.

## Pause

You can pause at three levels:

- Everything, with **Pause** in the header.
- One node, with **Pause** on the **Nodes** page.
- One service, with **Pause** on the **Services** page, or with the label `homegap.pause`.

HomeGAP observes a paused target, but it never changes it. It keeps the DNS records of paused nodes and services. The API call is `POST /api/pause` with a body like this:

```json
{"scope": "node", "id": "na1", "paused": true}
```

The `scope` is `global`, `node` or `service`.

## The management host

Newt and the Pangolin client must reach the Pangolin API of each node. HomeGAP routes `https://<management_host>/api/v1` on each node. The default of `management_host` is `pangolin-<id>.<domain>`. This name is not part of the wildcard passthrough, and it has its own plain DNS record.

The Pangolin ports 3000, 3002 and 3003 on the node listen only on loopback. By default, the web UI of Pangolin is not public. To open it, use SSH port forwarding:

1. Open the tunnel:
    ```bash
    ssh -L 3002:127.0.0.1:3002 deploy@203.0.113.10
    ```
2. Open `http://localhost:3002` in your browser.

`dashboard_public: true` routes the web UI publicly on the management host. In standalone mode, `auth: sso` and `auth: pin` need this. See [Pangolin sign-in for services](../services/authentication.md).

## Work on a node by hand

The stack is in `/opt/homegap` (`pangolin.install_dir`). If the management user is in the `docker` group, it can run `docker compose` there.

!!! warning "Caution"
    A change that you make by hand is drift. HomeGAP repairs it on the next node check. To keep the change, pause the node first.

## When the homelab loses every node

If two or more nodes are configured and none of them answers over SSH, the dashboard shows a banner. The log records the start and the end. The nodes continue to serve. If the outage continues, the nodes also fail the database over.

If the mesh probes show that the nodes reach each other during all of the outage, the problem is in the connection of the homelab.

## Tunnel slots

Each node gets a permanent tunnel slot, which the orchestrator keeps in its state. The slot sets these values:

- The tunnel subnet, `100.89.<128 + 4·slot>.0/22`.
- The kernel interface, `hgnewt<slot>`.
- The local ports.

Thus connectors for several nodes can run together in the `tunnels` container. A new node takes the lowest free slot. A removal or a new order of nodes never moves another node.

Pangolin fixes the subnet when a node registers its exit node for the first time. The orchestrator compares the slot of each node with its live site subnet.

!!! warning "Caution"
    If two nodes share a subnet, HomeGAP reports an error. This can occur when both nodes are older than tunnel slots. Before you enable `tunnels.native`, create the Pangolin database of one of the nodes again.
