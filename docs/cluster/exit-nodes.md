# Exit nodes

With exit nodes, the Pangolin apps can send all the traffic of a device through HomeGAP, as a VPN does. This page tells you how to enable the exits and how they work.

## Enable the exits

Add `cluster.exit_nodes` to the config. The person on the device then selects where the traffic leaves for the internet.

```yaml
cluster:
  exit_nodes:
    nodes: true        # "Exit via <node>" for each node
    homelab: true      # "Exit via homelab"
    roles: [Member]    # who can use them (Admin always can)
```

## Exit via a node

Each node gets a second Pangolin site, `exit-<node>`. The Newt connector of this site runs on the node itself, in `<install_dir>/exit`. It uses the host network and talks to the Pangolin of the node on loopback. The image is `cluster.exit_nodes.newt_image`.

The connector makes the connections of the person from the node. Thus the traffic leaves with the address of that node. This exit continues to work while the homelab is down.

## Exit via homelab

This is one exit on the homelab site of every node. The device relays through the nearest node, and the connector of the homelab makes the connections. Thus the traffic leaves with your home address. This exit needs the homelab.

## How the exits work

The person selects an exit in the Pangolin client. Pangolin calls these exits "exit nodes". While an exit is selected, the default route of the device goes through it.

The exits are Pangolin private resources of the type `exit-node`: `hg-exit-<node>` and `hg-exit-homelab`. They open no new ports. The connector of the node dials out, like all other connectors.

If you disable `nodes` or `homelab`, HomeGAP removes those exits. For `nodes`, it also removes the connector and its site.

The roles in `roles` can use the exits. The `Admin` role can always use them.

## Logs

To see the logs of the exit connector on a node, open the **Diagnostics** page and select the service `newt`. See [Diagnostics and troubleshooting](../operations/troubleshooting.md).
